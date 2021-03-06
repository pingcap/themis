package org.apache.hadoop.hbase.themis.cp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.*;
import org.apache.hadoop.hbase.KeyValue.Type;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.coprocessor.CoprocessorException;
import org.apache.hadoop.hbase.coprocessor.CoprocessorService;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.master.ThemisMasterObserver;
import org.apache.hadoop.hbase.protobuf.ProtobufUtil;
import org.apache.hadoop.hbase.protobuf.ResponseConverter;
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.HRegion.RowLock;
import org.apache.hadoop.hbase.regionserver.Store;
import org.apache.hadoop.hbase.regionserver.ThemisRegionObserver;
import org.apache.hadoop.hbase.themis.columns.Column;
import org.apache.hadoop.hbase.themis.columns.ColumnMutation;
import org.apache.hadoop.hbase.themis.columns.ColumnUtil;
import org.apache.hadoop.hbase.themis.cp.generated.ThemisProtos;
import org.apache.hadoop.hbase.themis.cp.generated.ThemisProtos.ThemisBatchGetResponse;
import org.apache.hadoop.hbase.themis.cp.generated.ThemisProtos.EraseLockRequest;
import org.apache.hadoop.hbase.themis.cp.generated.ThemisProtos.EraseLockResponse;
import org.apache.hadoop.hbase.themis.cp.generated.ThemisProtos.LockExpiredRequest;
import org.apache.hadoop.hbase.themis.cp.generated.ThemisProtos.LockExpiredResponse;
import org.apache.hadoop.hbase.themis.cp.generated.ThemisProtos.ThemisBatchCommitSecondaryRequest;
import org.apache.hadoop.hbase.themis.cp.generated.ThemisProtos.ThemisBatchCommitSecondaryResponse;
import org.apache.hadoop.hbase.themis.cp.generated.ThemisProtos.ThemisBatchPrewriteSecondaryRequest;
import org.apache.hadoop.hbase.themis.cp.generated.ThemisProtos.ThemisBatchPrewriteSecondaryResponse;
import org.apache.hadoop.hbase.themis.cp.generated.ThemisProtos.ThemisCommit;
import org.apache.hadoop.hbase.themis.cp.generated.ThemisProtos.ThemisCommitRequest;
import org.apache.hadoop.hbase.themis.cp.generated.ThemisProtos.ThemisCommitResponse;
import org.apache.hadoop.hbase.themis.cp.generated.ThemisProtos.ThemisGetRequest;
import org.apache.hadoop.hbase.themis.cp.generated.ThemisProtos.ThemisPrewrite;
import org.apache.hadoop.hbase.themis.cp.generated.ThemisProtos.ThemisPrewriteRequest;
import org.apache.hadoop.hbase.themis.cp.generated.ThemisProtos.ThemisPrewriteResponse;
import org.apache.hadoop.hbase.themis.cp.generated.ThemisProtos.ThemisPrewriteResult;
import org.apache.hadoop.hbase.themis.cp.generated.ThemisProtos.ThemisService;
import org.apache.hadoop.hbase.themis.exception.TransactionExpiredException;
import org.apache.hadoop.hbase.themis.lock.ThemisLock;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.metrics.util.MetricsTimeVaryingRate;

import com.google.common.collect.Lists;
import com.google.protobuf.HBaseZeroCopyByteString;
import com.google.protobuf.RpcCallback;
import com.google.protobuf.RpcController;
import com.google.protobuf.Service;

public class ThemisEndpoint extends ThemisService implements CoprocessorService, Coprocessor {
  private static final Log LOG = LogFactory.getLog(ThemisEndpoint.class);
  private static final byte[] EMPTY_BYTES = new byte[0];
  private static final int DEFAULT_THREAD_COUNT = Runtime.getRuntime().availableProcessors() * 5;
  private static final int DEFAULT_THEMIS_BATCH_GET_THREAD_COUNT = DEFAULT_THREAD_COUNT;

  private RegionCoprocessorEnvironment env;

  private static ThreadPoolExecutor batchGetThreadPool = new ThreadPoolExecutor(
          DEFAULT_THEMIS_BATCH_GET_THREAD_COUNT, DEFAULT_THEMIS_BATCH_GET_THREAD_COUNT, 10,
          TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(), new ThreadFactory() {
    public Thread newThread(Runnable r) {
      Thread t = new Thread(r);
      t.setName("themis-batch-get-thread-" + System.currentTimeMillis());
      return t;
    }
  });

  static {
    batchGetThreadPool.allowCoreThreadTimeOut(true);
  }
  
  public void start(CoprocessorEnvironment env) throws IOException {
    // super.start(env);
    if (!(env instanceof RegionCoprocessorEnvironment)) {
      throw new CoprocessorException("Must be loaded on a table region!");
    }

    this.env = (RegionCoprocessorEnvironment) env;
    ColumnUtil.init(env.getConfiguration());
    TransactionTTL.init(env.getConfiguration());
  }

  public void stop(CoprocessorEnvironment env) throws IOException {
  }

  public Service getService() {
    return this;
  }

  public Result themisGet(Get get, long startTs, boolean ignoreLock) throws IOException {
    HRegion region = env.getRegion();
    ThemisCpUtil.prepareGet(get, region.getTableDesc().getFamilies());
    checkFamily(get);
    checkReadTTL(System.currentTimeMillis(), startTs, get.getRow());
    Get lockAndWriteGet = ThemisCpUtil.constructLockAndWriteGet(get, startTs);
    Result result = ThemisCpUtil.removeNotRequiredLockColumns(
            get.getFamilyMap(),
            getFromRegion(region, lockAndWriteGet,
                    ThemisCpStatistics.getThemisCpStatistics().getLockAndWriteLatency));
    Pair<List<KeyValue>, List<KeyValue>> lockAndWriteKvs = ThemisCpUtil
            .seperateLockAndWriteKvs(result.list());
    List<KeyValue> lockKvs = lockAndWriteKvs.getFirst();
    if (!ignoreLock && lockKvs.size() != 0) {
      // return lock columns when encounter conflict lock
      return new Result(lockKvs);
    }
    List<KeyValue> putKvs = ThemisCpUtil.getPutKvs(lockAndWriteKvs.getSecond());
    if (putKvs.size() != 0) {
      Get dataGet = ThemisCpUtil.constructDataGetByPutKvs(putKvs, get.getFilter());
      return getFromRegion(region, dataGet,
              ThemisCpStatistics.getThemisCpStatistics().getDataLatency);
    }
    // no such version or no such row
    return null;
  }

  @Override
  public void themisGet(RpcController controller, ThemisGetRequest request,
      RpcCallback<org.apache.hadoop.hbase.protobuf.generated.ClientProtos.Result> callback) {
    // first get lock and write columns to check conflicted lock and get commitTs
    ClientProtos.Result clientResult = ProtobufUtil.toResult(new Result());
    try {
      Get clientGet = ProtobufUtil.toGet(request.getGet());
      Result result = themisGet(clientGet, request.getStartTs(), request.getIgnoreLock());
      if (result != null) {
        clientResult = ProtobufUtil.toResult(result);
      }
    } catch (IOException e) {
      LOG.error("themisGet fail", e);
      // Call ServerRpcController#getFailedOn() to retrieve this IOException at client side.
      ResponseConverter.setControllerException(controller, e);
    }
    callback.run(clientResult);
  }
  
  public static void checkReadTTL(long currentMs, long startTs, byte[] row)
      throws TransactionExpiredException {
    if (!TransactionTTL.transactionTTLEnable) {
      return;
    }
    long expiredTimestamp = TransactionTTL.getExpiredTimestampForReadByCommitColumn(currentMs);
    if (startTs < TransactionTTL.getExpiredTimestampForReadByCommitColumn(currentMs)) {
      throw new TransactionExpiredException("Expired Read Transaction, read transaction start Ts:"
          + startTs + ", expired Ts:" + expiredTimestamp + ", currentMs=" + currentMs + ", row="
          + Bytes.toString(row));
    }
  }
  
  public static void checkWriteTTL(long currentMs, long startTs, byte[] row)
      throws TransactionExpiredException {
    if (!TransactionTTL.transactionTTLEnable) {
      return;
    }
    long expiredTimestamp = TransactionTTL.getExpiredTimestampForWrite(currentMs);
    if (startTs < expiredTimestamp) {
      throw new TransactionExpiredException(
          "Expired Write Transaction, write transaction start Ts:" + startTs + ", expired Ts:"
              + expiredTimestamp + ", currentMs=" + currentMs + ", row=" + Bytes.toString(row));
    }
  }
  
  protected Result getFromRegion(HRegion region, Get get, MetricsTimeVaryingRate latency)
      throws IOException {
    long beginTs = System.nanoTime();
    try {
      return region.get(get);
    } finally {
      ThemisCpStatistics.updateLatency(latency, beginTs,
        "row=" + Bytes.toStringBinary(get.getRow()));
    }
  }

  @Override
  public void commitRow(RpcController controller, ThemisCommitRequest request,
      RpcCallback<ThemisCommitResponse> callback) {
    commitRow(controller, request, callback, false);
  }

  @Override
  public void commitSingleRow(RpcController controller, ThemisCommitRequest request,
      RpcCallback<ThemisCommitResponse> callback) {
    commitRow(controller, request, callback, true);
  }
  
  protected void commitRow(RpcController controller, ThemisCommitRequest request,
      RpcCallback<ThemisCommitResponse> callback, boolean isSingleRow) {
    boolean result = false;
    try {
      ThemisCommit commit = request.getThemisCommit();
      result = commitRow(commit.getRow().toByteArray(),
        ColumnMutation.toColumnMutations(commit.getMutationsList()), commit.getPrewriteTs(),
        commit.getCommitTs(), commit.getPrimaryIndex(), isSingleRow);
    } catch(IOException e) {
      LOG.error("commitRow fail", e);
      ResponseConverter.setControllerException(controller, e);
    }
    ThemisCommitResponse.Builder builder = ThemisCommitResponse.newBuilder();
    builder.setResult(result);
    callback.run(builder.build());
  }

  @Override
  public void getLockAndErase(RpcController controller, EraseLockRequest request,
      RpcCallback<EraseLockResponse> callback) {
    byte[] lock = null;
    try {
      lock = getLockAndErase(request.getRow().toByteArray(), request.getFamily()
          .toByteArray(), request.getQualifier().toByteArray(), request.getPrewriteTs());
    } catch(IOException e) {
      LOG.error("getLockAndErase fail", e);
      ResponseConverter.setControllerException(controller, e);
    }
    EraseLockResponse.Builder builder = EraseLockResponse.newBuilder();
    if (lock != null) {
      builder.setLock(HBaseZeroCopyByteString.wrap(lock));
    }
    callback.run(builder.build());
  }

  @Override
  public void prewriteRow(RpcController controller, ThemisPrewriteRequest request,
      RpcCallback<ThemisPrewriteResponse> callback) {
    prewritePrimaryRow(controller, request, callback, false);
  }

  @Override
  public void prewriteSingleRow(RpcController controller, ThemisPrewriteRequest request,
      RpcCallback<ThemisPrewriteResponse> callback) {
    prewritePrimaryRow(controller, request, callback, true);
  }
  
  @Override
  public void batchPrewriteSecondaryRows(RpcController controller, ThemisBatchPrewriteSecondaryRequest request,
      RpcCallback<ThemisBatchPrewriteSecondaryResponse> callback) {

    ThemisBatchPrewriteSecondaryResponse.Builder builder = ThemisBatchPrewriteSecondaryResponse.newBuilder();
    List<ThemisPrewriteResult> results = null;
    try {
      results = batchPrewriteSecondaryRows(request);
    } catch (IOException e) {
      ResponseConverter.setControllerException(controller, new IOException(e));
    }
    if (results != null && results.size() > 0) {
      for (ThemisPrewriteResult r : results) {
        builder.addThemisPrewriteResult(r);
      }
    }
    callback.run(builder.build());
  }

  protected void prewritePrimaryRow(RpcController controller, ThemisPrewriteRequest request,
      RpcCallback<ThemisPrewriteResponse> callback, boolean isSingleRow) {
    ThemisPrewriteResponse.Builder builder = ThemisPrewriteResponse.newBuilder();
    ThemisPrewriteResult result = prewriteRow(controller,
            request.getThemisPrewrite(),
            request.getPrewriteTs(),
            request.getPrimaryLock().toByteArray(),
            request.getSecondaryLock().toByteArray(), request.getPrimaryIndex(), isSingleRow);
    if (result != null) {
      builder.setThemisPrewriteResult(result);
    }
    callback.run(builder.build());
  }

  private List<ThemisPrewriteResult> batchPrewriteSecondaryRows(ThemisBatchPrewriteSecondaryRequest request) throws IOException {
    HRegion region = env.getRegion();
    List<ThemisPrewriteResult> results = new ArrayList<>();
    List<Mutation> puts = new ArrayList<>();
    List<RowLock> rowLocks = new ArrayList<>();
    byte[] lockBytes = request.getSecondaryLock().toByteArray();
    try {
      for (ThemisPrewrite prewrite : request.getThemisPrewriteList()) {
        rowLocks.add(region.getRowLock(prewrite.getRow().toByteArray()));
      }
      // check themis lock and put
      for (ThemisPrewrite prewrite : request.getThemisPrewriteList()) {
        // check mutations
        List<ColumnMutation> mutations = ColumnMutation.toColumnMutations(prewrite.getMutationsList());
        for (ColumnMutation mutation : mutations) {
          // TODO : make sure, won't encounter a lock with the same timestamp
          ThemisPrewriteResult conflict = checkPrewriteConflict(region, prewrite.getRow().toByteArray(),
              mutation, request.getPrewriteTs());
          if (conflict != null) {
            results.add(conflict);
          }
        }
        if (results.size() > 0) {
          return results;
        }
        Put prewritePut = new Put(prewrite.getRow().toByteArray());
        for (int i = 0; i < mutations.size(); ++i) {
          ColumnMutation m = mutations.get(i);
          // get lock and set lock Type
          ThemisLock lock;
          lock = ThemisLock.parseFromByte(lockBytes);
          lock.setType(m.getType());
          if (lock.getType().equals(Type.Put)) {
            prewritePut.add(m.getFamily(), m.getQualifier(), request.getPrewriteTs(),
                m.getValue());
          }
          Column lockColumn = ColumnUtil.getLockColumn(m);
          prewritePut.add(lockColumn.getFamily(), lockColumn.getQualifier(), request.getPrewriteTs(),
              ThemisLock.toByte(lock));
        }
        puts.add(prewritePut);
      }
      // batch put
      region.mutateRowsWithLocks(puts, Collections.<byte[]>emptySet());
    } catch (IOException e) {
      throw e;
    } finally {
      // release all locks
      for (RowLock lock : rowLocks) {
        lock.release();
      }
    }
    return null;
  }

  private ThemisPrewriteResult prewriteRow(RpcController controller, ThemisPrewrite prewrite,
                                           long prewriteTs, byte[] primaryLock,
                                           byte[] secondaryLock, int primaryIndex, boolean isSingleRow) {
    ThemisPrewriteResult conflict = null;
    try {
      conflict = prewriteRow(prewrite.getRow().toByteArray(),
          ColumnMutation.toColumnMutations(prewrite.getMutationsList()), prewriteTs,
          primaryLock, secondaryLock,
          primaryIndex, isSingleRow);
    } catch (IOException e) {
      LOG.error("prewrite fail", e);
      ResponseConverter.setControllerException(controller, e);
    }
    if (conflict != null) {
      return conflict;
    }
    return null;
  }

  abstract class MutationCallable<R> {
    private final byte[] row;
    public MutationCallable(byte[] row) {
      this.row = row;
    }
    
    public abstract R doMutation(HRegion region, RowLock rowLock) throws IOException;
    
    public R run() throws IOException {
      HRegion region = env.getRegion();
      RowLock rowLock = region.getRowLock(row);
      // wait for all previous transactions to complete (with lock held)
      // region.getMVCC().completeMemstoreInsert(region.getMVCC().beginMemstoreInsert());
      try {
        return doMutation(region, rowLock);
      } finally {
        rowLock.release();
      }
    }
  }
  
  protected void checkPrimaryLockAndIndex(final byte[] lockBytes, final int primaryIndex)
      throws IOException {
    // primaryIndex must be -1 when lockBytes is null, while primaryIndex must not be -1
    // when lockBytse is not null
    if (((lockBytes == null || lockBytes.length == 0) && primaryIndex != -1)
        || ((lockBytes != null && lockBytes.length != 0) && primaryIndex == -1)) {
      throw new IOException("primaryLock is inconsistent with primaryIndex, primaryLock="
          + ThemisLock.parseFromByte(lockBytes) + ", primaryIndex=" + primaryIndex);
    }
  }
  
  protected void checkFamily(final Get get) throws IOException {
    checkFamily(get.getFamilyMap().keySet().toArray(new byte[][]{}));
  }
  
  protected void checkFamily(final List<ColumnMutation> mutations) throws IOException {
    byte[][] families = new byte[mutations.size()][];
    for (int i = 0; i < mutations.size(); ++i) {
      families[i] = mutations.get(i).getFamily();
    }
    checkFamily(families);
  }
  
  protected void checkFamily(final byte[][] families) throws IOException {
    checkFamily(env.getRegion(), families);
  }
  
  protected static void checkFamily(HRegion region, byte[][] families) throws IOException {
    for (byte[] family : families) {
      Store store = region.getStore(family);
      if (store == null) {
        throw new DoNotRetryIOException("family : '" + Bytes.toString(family) + "' not found in table : "
            + region.getTableDesc().getNameAsString());
      }
      String themisEnable = store.getFamily().getValue(ThemisMasterObserver.THEMIS_ENABLE_KEY);
      if (themisEnable == null || !Boolean.parseBoolean(themisEnable)) {
        throw new DoNotRetryIOException("can not access family : '" + Bytes.toString(family)
            + "' because " + ThemisMasterObserver.THEMIS_ENABLE_KEY + " is not set");
      }
    }
  }
  
  public ThemisPrewriteResult prewriteRow(final byte[] row, final List<ColumnMutation> mutations,
      final long prewriteTs, final byte[] primaryLock, final byte[] secondaryLock,
      final int primaryIndex, final boolean singleRow) throws IOException {
    // TODO : use ms enough?
    final long beginTs = System.nanoTime();
    try {
      checkFamily(mutations);
      checkWriteTTL(System.currentTimeMillis(), prewriteTs, row);
      checkPrimaryLockAndIndex(primaryLock, primaryIndex);
      return new MutationCallable<ThemisPrewriteResult>(row) {
        public ThemisPrewriteResult doMutation(HRegion region, RowLock rowLock) throws IOException {
          // firstly, check conflict for each column
          // TODO : check one row one time to improve efficiency?
          for (ColumnMutation mutation : mutations) {
            // TODO : make sure, won't encounter a lock with the same timestamp
            ThemisPrewriteResult conflict = checkPrewriteConflict(region, row, mutation, prewriteTs);
            if (conflict != null) {
              return conflict;
            }
          }
          ThemisCpStatistics.updateLatency(
            ThemisCpStatistics.getThemisCpStatistics().prewriteCheckConflictRowLatency, beginTs,
            false);

          Put prewritePut = new Put(row);
          byte[] primaryQualifier = null;
          for (int i = 0; i < mutations.size(); ++i) {
            boolean isPrimary = false;
            ColumnMutation mutation = mutations.get(i);
            // get lock and set lock Type
            byte[] lockBytes = secondaryLock;
            if (primaryLock != null && i == primaryIndex) {
              lockBytes = primaryLock;
              isPrimary = true;
            }
            ThemisLock lock = ThemisLock.parseFromByte(lockBytes);
            lock.setType(mutation.getType());

            if (!singleRow && lock.getType().equals(Type.Put)) {
              prewritePut.add(mutation.getFamily(), mutation.getQualifier(), prewriteTs,
                mutation.getValue());
            }
            Column lockColumn = ColumnUtil.getLockColumn(mutation);
            prewritePut.add(lockColumn.getFamily(), lockColumn.getQualifier(), prewriteTs,
              ThemisLock.toByte(lock));
            
            if (isPrimary) {
              primaryQualifier = lockColumn.getQualifier();
            }
          }
          if (singleRow) {
            prewritePut.setAttribute(ThemisRegionObserver.SINGLE_ROW_PRIMARY_QUALIFIER,
              primaryQualifier);
          }
          mutateToRegion(region, row, Lists.<Mutation> newArrayList(prewritePut),
            ThemisCpStatistics.getThemisCpStatistics().prewriteWriteLatency);
          return null;
        }
      }.run();
    } finally {
      ThemisCpStatistics.updateLatency(
        ThemisCpStatistics.getThemisCpStatistics().prewriteTotalLatency, beginTs, false);
    }
  }
  
  protected void mutateToRegion(HRegion region, byte[] row, List<Mutation> mutations,
      MetricsTimeVaryingRate latency) throws IOException {
    long beginTs = System.nanoTime();
    try {
      // we have obtained lock, do not need to require lock in mutateRowsWithLocks
      region.mutateRowsWithLocks(mutations, Collections.<byte[]>emptySet());
    } finally {
      ThemisCpStatistics.updateLatency(latency, beginTs, "row=" + Bytes.toStringBinary(row)
          + ", mutationCount=" + mutations.size());
    }
  }
  
  // check lock conflict and new write conflict. return null if no conflicts encountered; otherwise,
  // the first byte[] return the bytes of timestamp which is newer than prewriteTs, the secondary
  // byte[] will return the conflict lock if encounters lock conflict
  protected ThemisPrewriteResult checkPrewriteConflict(HRegion region, byte[] row, Column column,
      long prewriteTs) throws IOException {
    Column lockColumn = ColumnUtil.getLockColumn(column);
    // check no lock exist
    Get get = new Get(row).addColumn(lockColumn.getFamily(), lockColumn.getQualifier());
    Result result = getFromRegion(region, get,
      ThemisCpStatistics.getThemisCpStatistics().prewriteReadLockLatency);
    byte[] existLockBytes = result.isEmpty() ? null : result.list().get(0).getValue();
    boolean lockExpired = existLockBytes == null ? false : isLockExpired(result.list().get(0)
        .getTimestamp());
    // check no newer write exist
    get = new Get(row);
    ThemisCpUtil.addWriteColumnToGet(column, get);
    get.setTimeRange(prewriteTs, Long.MAX_VALUE);
    result = getFromRegion(region, get,
      ThemisCpStatistics.getThemisCpStatistics().prewriteReadWriteLatency);
    Long newerWriteTs = result.isEmpty() ? null : result.list().get(0).getTimestamp();
    ThemisPrewriteResult conflict = judgePrewriteConflict(row, column, existLockBytes, newerWriteTs, lockExpired);
    if (conflict != null) {
      LOG.warn("encounter conflict when prewrite, tableName="
          + Bytes.toString(region.getTableDesc().getName()) + ", row=" + Bytes.toString(row)
          + ", column=" + column + ", prewriteTs=" + prewriteTs);
    }
    return conflict;
  }
  
  protected ThemisPrewriteResult judgePrewriteConflict(byte[] row, Column column, byte[] existLockBytes, Long newerWriteTs,
      boolean lockExpired) {
    if (newerWriteTs != null || existLockBytes != null) {
      newerWriteTs = newerWriteTs == null ? 0 : newerWriteTs;
      existLockBytes = existLockBytes == null ? new byte[0] : existLockBytes;
      byte[] family = EMPTY_BYTES;
      byte[] qualifier = EMPTY_BYTES;
      if (existLockBytes.length != 0) {
        // must return the data column other than lock column
        family = column.getFamily();
        qualifier = column.getQualifier();
      }
      // we also return the conflict family and qualifier, then the client could know which column
      // encounter conflict when prewriting by row
      ThemisPrewriteResult.Builder conflict = ThemisPrewriteResult.newBuilder();
      conflict.setNewerWriteTs(newerWriteTs);
      conflict.setExistLock(HBaseZeroCopyByteString.wrap(existLockBytes));
      conflict.setLockExpired(lockExpired);
      conflict.setQualifier(HBaseZeroCopyByteString.wrap(qualifier));
      conflict.setFamily(HBaseZeroCopyByteString.wrap(family));
      conflict.setRow(HBaseZeroCopyByteString.wrap(row));
      return conflict.build();
    }
    return null;
  }

  public boolean commitRow(final byte[] row, final List<ColumnMutation> mutations,
      final long prewriteTs, final long commitTs, final int primaryIndex, final boolean singleRow)
      throws IOException {
    long beginTs = System.nanoTime();
    try {
      checkFamily(mutations);
      if (primaryIndex != -1) {
        checkWriteTTL(System.currentTimeMillis(), prewriteTs, row);
      }
      return new MutationCallable<Boolean>(row) {
        public Boolean doMutation(HRegion region, RowLock rowLock) throws IOException {
          if (primaryIndex >= 0) {
            // can't commit the transaction if the primary lock has been erased
            ColumnMutation mutation = mutations.get(primaryIndex);
            byte[] lockBytes = readLockBytes(region, row, mutation, prewriteTs,
              ThemisCpStatistics.getThemisCpStatistics().commitPrimaryReadLatency);
            if (lockBytes == null) {
              LOG.warn("primary lock erased, tableName="
                  + Bytes.toString(region.getTableDesc().getName()) + ", row="
                  + Bytes.toString(row) + ", column=" + mutation + ", prewriteTs=" + prewriteTs);
              return false;
            }
            // TODO : for single-row, sanity check secondary lock must hold
          }
          doCommitMutations(region, row, mutations, prewriteTs, commitTs, singleRow);
          return true;
        }
      }.run();
    } finally {
      ThemisCpStatistics.updateLatency(
              ThemisCpStatistics.getThemisCpStatistics().commitTotalLatency, beginTs, false);
    }
  }

  protected void doCommitMutations(HRegion region, byte[] row, List<ColumnMutation> mutations,
      long prewriteTs, long commitTs, boolean singleRow)
      throws IOException {
    List<Mutation> rowMutations = getCommitMutations(row, mutations, prewriteTs, commitTs, singleRow);
    mutateToRegion(region, row, rowMutations, ThemisCpStatistics.getThemisCpStatistics().commitWriteLatency);
  }

  private List<Mutation> getCommitMutations(byte[] row, List<ColumnMutation> mutations, long prewriteTs, long commitTs, boolean singleRow) {
    List<Mutation> rowMutations = new ArrayList<>();
    for (ColumnMutation mutation : mutations) {
      Put writePut = new Put(row);
      Column writeColumn = null;
      if (mutation.getType() == Type.Put) {
        writeColumn = ColumnUtil.getPutColumn(mutation);
        // we do not write data in prewrite-phase for single-row
        if (singleRow) {
          writePut.add(mutation.getFamily(), mutation.getQualifier(), prewriteTs,
            mutation.getValue());
        }
      } else if (mutation.getType() == Type.DeleteColumn) {
        writeColumn = ColumnUtil.getDeleteColumn(mutation);
      }

      // if is not put or delete, then it is lockRow, only lock, has not data change
      if ( writeColumn != null ) {
        writePut.add(writeColumn.getFamily(), writeColumn.getQualifier(), commitTs,
                Bytes.toBytes(prewriteTs));
        rowMutations.add(writePut);
      }

      Column lockColumn = ColumnUtil.getLockColumn(mutation);
      Delete lockDelete = new Delete(row).deleteColumn(lockColumn.getFamily(),
        lockColumn.getQualifier(), prewriteTs);
      setLockFamilyDelete(lockDelete);
      rowMutations.add(lockDelete);
    }
    return rowMutations;
  }

  protected byte[] readLockBytes(HRegion region, byte[] row, Column column, long prewriteTs,
      MetricsTimeVaryingRate latency) throws IOException {
    Column lockColumn = ColumnUtil.getLockColumn(column);
    Get get = new Get(row).addColumn(lockColumn.getFamily(), lockColumn.getQualifier());
    get.setTimeStamp(prewriteTs);
    Result result = getFromRegion(region, get, latency);
    return result.isEmpty() ? null : result.list().get(0).getValue();
  }

  public byte[] getLockAndErase(final byte[] row, final byte[] family, final byte[] qualifier,
      final long prewriteTs) throws IOException {
    return new MutationCallable<byte[]>(row) {
      public byte[] doMutation(HRegion region, RowLock rowLock) throws IOException {
        byte[] lockBytes = readLockBytes(region, row, new Column(family, qualifier),
          prewriteTs, ThemisCpStatistics.getThemisCpStatistics().getLockAndEraseReadLatency);
        if (lockBytes == null) {
          return null;
        }
        
        Column lockColumn = ColumnUtil.getLockColumn(family, qualifier);
        Delete delete = new Delete(row);
        setLockFamilyDelete(delete);
        delete.deleteColumn(lockColumn.getFamily(), lockColumn.getQualifier(), prewriteTs);
        mutateToRegion(region, row, Lists.<Mutation> newArrayList(delete),
        ThemisCpStatistics.getThemisCpStatistics().getLockAndEraseReadLatency);
        return lockBytes;
      }
    }.run();
  }
  
  public static void setLockFamilyDelete(final Delete delete) {
    delete.setAttribute(ThemisRegionObserver.LOCK_FAMILY_DELETE, Bytes.toBytes(true));
  }

  @Override
  public void isLockExpired(RpcController controller, LockExpiredRequest request,
      RpcCallback<LockExpiredResponse> callback) {
    boolean isExpired = false;
    try {
      isExpired = isLockExpired(request.getTimestamp());
    } catch(IOException e) {
      LOG.error("isLockExpired fail", e);
      ResponseConverter.setControllerException(controller, e);
    }
    LockExpiredResponse.Builder builder = LockExpiredResponse.newBuilder();
    builder.setExpired(isExpired);
    callback.run(builder.build());
  }
  
  public boolean isLockExpired(long lockTimestamp) throws IOException {
    long currentMs = System.currentTimeMillis();
    return lockTimestamp < TransactionTTL.getExpiredTimestampForWrite(currentMs);
  }

  @Override
  public void batchCommitSecondaryRows(RpcController controller, ThemisBatchCommitSecondaryRequest request,
      RpcCallback<ThemisBatchCommitSecondaryResponse> callback) {
    ThemisBatchCommitSecondaryResponse.Builder builder = ThemisBatchCommitSecondaryResponse.newBuilder();
    try {
      List<ThemisCommit> commits = request.getThemisCommitList();
      if (commits == null || commits.size() == 0) {
        callback.run(builder.build());
        LOG.warn("has not secondary rows");
        return;
      }
      HRegion region = env.getRegion();
      List<byte[]> rows = new ArrayList<>();
      List<Mutation> allMutations = new ArrayList<>();
      for (ThemisCommit commit : commits) {
        rows.add(commit.getRow().toByteArray());
         List<Mutation> mutations = getCommitMutations(commit.getRow().toByteArray(),
          ColumnMutation.toColumnMutations(commit.getMutationsList()), commit.getPrewriteTs(), commit.getCommitTs(), false);
        allMutations.addAll(mutations);
      }
      region.mutateRowsWithLocks(allMutations, rows);
    } catch (Exception e) {
      LOG.error("batch commit secondary rows fail", e);
      ResponseConverter.setControllerException(controller, new IOException(e));
    }
    callback.run(builder.build());
  }

  @Override
  public void themisBatchGet(RpcController controller, ThemisProtos.ThemisBatchGetRequest request, RpcCallback<ThemisProtos.ThemisBatchGetResponse> callback) {
    ThemisBatchGetResponse.Builder builder = ThemisBatchGetResponse.newBuilder();
    try {
      List<ClientProtos.Get> getList = request.getGetsList();
      if (getList == null || getList.size() == 0) {
        callback.run(builder.build());
        return;
      }
      List<Future<Result>> list = new ArrayList<>();
      for (ClientProtos.Get g : getList) {
        Get clientGet = ProtobufUtil.toGet(g);
        // send to thread pool
        Future<Result> f = batchGetThreadPool.submit(new BatchGetTask(clientGet, request.getStartTs(), request.getIgnoreLock()));
        list.add(f);
      }

      for (Future<Result> future : list ) {
        try {
          Result r = future.get();
          // only return exists kvs
          if (r != null) {
            builder.addRs(ProtobufUtil.toResult(r));
          }
        } catch (Exception e) {
          LOG.error("batch get error", e);
          ResponseConverter.setControllerException(controller, new IOException(e));
        }
      }
    } catch (IOException e) {
      ResponseConverter.setControllerException(controller, e);
    }
    callback.run(builder.build());
  }

  class BatchGetTask implements Callable<Result> {
    private Get get;
    private long startTs;
    private boolean ignoreLock;

    public BatchGetTask(Get get, long startTs, boolean ignoreLock) {
      this.get = get;
      this.startTs = startTs;
      this.ignoreLock = ignoreLock;
    }

    public Result call() throws Exception {
      return themisGet(get, startTs, ignoreLock);
    }
  }

}
