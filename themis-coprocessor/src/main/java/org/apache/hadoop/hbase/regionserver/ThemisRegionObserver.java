package org.apache.hadoop.hbase.regionserver;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.CoprocessorEnvironment;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.Durability;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.coprocessor.BaseRegionObserver;
import org.apache.hadoop.hbase.coprocessor.ObserverContext;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.master.ThemisMasterObserver;
import org.apache.hadoop.hbase.regionserver.ScanInfo;
import org.apache.hadoop.hbase.regionserver.compactions.CompactionRequest;
import org.apache.hadoop.hbase.regionserver.wal.WALEdit;
import org.apache.hadoop.hbase.themis.columns.ColumnUtil;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.zookeeper.ZooKeeperWatcher;

public class ThemisRegionObserver extends BaseRegionObserver {
  private static final Log LOG = LogFactory.getLog(ThemisRegionObserver.class);
  public static final String THEMIS_DELETE_THEMIS_DELETED_DATA_WHEN_COMPACT = "themis.delete.themis.deleted.data.when.compact";
  public static final String SINGLE_ROW_PRIMARY_QUALIFIER = "_themisSingleRowPrewritePrimaryQualifier_";
  public static final String LOCK_FAMILY_DELETE = "_themisLockFamilyDelete_";
  
  private boolean expiredDataCleanEnable;
  protected boolean deleteThemisDeletedDataWhenCompact;

  @Override
  public void start(CoprocessorEnvironment e) throws IOException {
    super.start(e);
    ColumnUtil.init(e.getConfiguration());
    expiredDataCleanEnable = e.getConfiguration().getBoolean(
      ThemisMasterObserver.THEMIS_EXPIRED_DATA_CLEAN_ENABLE_KEY, true);
    deleteThemisDeletedDataWhenCompact =  e.getConfiguration().getBoolean(
      THEMIS_DELETE_THEMIS_DELETED_DATA_WHEN_COMPACT, false);
    if (expiredDataCleanEnable) {
      LOG.info("themis expired data clean enable, deleteThemisDeletedDataWhenCompact=" + deleteThemisDeletedDataWhenCompact);
    }
  }
  
  @Override
  public void prePut(final ObserverContext<RegionCoprocessorEnvironment> c, final Put put,
      final WALEdit edit, final Durability durability) throws IOException {
    byte[] primaryQualifier = put.getAttribute(SINGLE_ROW_PRIMARY_QUALIFIER);
    if (primaryQualifier != null) {
      HRegion region = c.getEnvironment().getRegion();
      List<KeyValue> kvs = put.getFamilyMap().get(ColumnUtil.LOCK_FAMILY_NAME);
      if (kvs.size() != put.size() || kvs.size() == 0) {
        throw new IOException(
            "contain no-lock family kvs when do prewrite for single row transaction, put=" + put);
      }

      HStore lockStore = (HStore)region.getStore(ColumnUtil.LOCK_FAMILY_NAME);
      long addedSize = 0;
      
      // we must make sure all the kvs of lock family be written to memstore at the same time,
      // if not, secondary lock kvs might be written firstly, snapshot and flushed while primary
      // kv not, which will break the atomic of transaction if region server is crashed before
      // primary kv flushed(although this seems won't cause problem single row transaction of themis)
      lockStore.lock.readLock().lock();
      try {
        // we must write lock for primary firstly
        int primaryIndex = -1;
        for (int i = 0; i < kvs.size(); ++i) {
          if (Bytes.equals(primaryQualifier, kvs.get(i).getQualifier())) {
            primaryIndex = i;
          }
        }

        if (primaryIndex < 0) {
          throw new IOException("can't find primary for single row transaction, primaryQualifier="
              + Bytes.toString(primaryQualifier) + ", put=" + put);
        }

        kvs.get(primaryIndex).setMvccVersion(0); // visible by any read
        addedSize += lockStore.memstore.add(kvs.get(primaryIndex));

        // then, we write secondaries' locks
        for (int i = 0; i < kvs.size(); ++i) {
          if (i != primaryIndex) {
            kvs.get(i).setMvccVersion(0);
            addedSize += lockStore.memstore.add(kvs.get(i));
          }
        }
      } finally {
        lockStore.lock.readLock().unlock();
      }
      // TODO : we don't do requestFlush judge here because lock family's write only take small part
      //        of memory. There is a corner case when there are only prewrites for single row transaction,
      //        we need to avoid memstore exceeds upper bound in this situation
      // TODO : keep region size consistent with memestore size(move to finally)
      region.addAndGetGlobalMemstoreSize(addedSize);
      c.bypass();
    }
  }

  @Override
  public InternalScanner preFlushScannerOpen(final ObserverContext<RegionCoprocessorEnvironment> c,
      final Store store, final KeyValueScanner memstoreScanner, final InternalScanner s)
      throws IOException {
    if (expiredDataCleanEnable
        && (ThemisMasterObserver.isThemisEnableFamily(store.getFamily()) || ColumnUtil
            .isCommitFamily(store.getFamily().getName()))) {
      InternalScanner scanner = getScannerToCleanExpiredThemisData(store, store.getScanInfo(),
        Collections.singletonList(memstoreScanner), ScanType.COMPACT_RETAIN_DELETES, ((HStore)store).getHRegion()
            .getSmallestReadPoint(), HConstants.OLDEST_TIMESTAMP, false);
      if (scanner != null) {
        return scanner;
      }
    }
    return s;
  }
  
  @Override
  public InternalScanner preCompactScannerOpen(final ObserverContext<RegionCoprocessorEnvironment> c,
      final Store store, List<? extends KeyValueScanner> scanners, final ScanType scanType,
      final long earliestPutTs, final InternalScanner s, CompactionRequest request)
      throws IOException {
    if (expiredDataCleanEnable
        && (ThemisMasterObserver.isThemisEnableFamily(store.getFamily()) || ColumnUtil
            .isCommitFamily(store.getFamily().getName()))) {
      InternalScanner scanner = getScannerToCleanExpiredThemisData(store, store.getScanInfo(),
        scanners, scanType, ((HStore)store).getHRegion().getSmallestReadPoint(), earliestPutTs, true);
      if (scanner != null) {
        return scanner;
      }
    }
    return s;
  }
  
  protected InternalScanner getScannerToCleanExpiredThemisData(final Store store,
      final ScanInfo scanInfo, final List<? extends KeyValueScanner> scanners,
      final ScanType scanType, final long smallestReadPoint, final long earliestPutTs,
      final boolean isCompact)
      throws IOException {
    long cleanTs = Long.MIN_VALUE;
    ZooKeeperWatcher zk = ((HStore)store).getHRegion().getRegionServerServices().getZooKeeper();
    try {
      cleanTs = ThemisMasterObserver.getThemisExpiredTsFromZk(zk);
    } catch (Exception e) {
      LOG.error("themis region oberver get cleanTs fail, region="
          + store.getRegionInfo().getEncodedName() + ", family="
          + store.getFamily().getNameAsString() + ", scanType=" + scanType, e);
      return null;
    }
    if (cleanTs == Long.MIN_VALUE) {
      LOG.warn("can't get a valid cleanTs, region=" + store.getRegionInfo().getEncodedName()
          + ", family=" + store.getFamily().getNameAsString() + ", scanType=" + scanType
          + ", please check zk path:" + ThemisMasterObserver.getThemisExpiredTsZNodePath(zk)
          + " is valid");
      return null;
    }

    Scan scan = new Scan();
    scan.setMaxVersions(store.getScanInfo().getMaxVersions());
    ThemisExpiredDataCleanFilter filter = null;
    if (deleteThemisDeletedDataWhenCompact && isCompact) {
      filter = new ThemisExpiredDataCleanFilter(cleanTs, ((HStore)store).getHRegion());
    } else {
      filter = new ThemisExpiredDataCleanFilter(cleanTs);
    }
    
    scan.setFilter(filter);
    InternalScanner scanner = new StoreScanner(store, scanInfo, scan, scanners, scanType,
        smallestReadPoint, earliestPutTs);
    LOG.info("themis clean data, add expired data clean filter for region="
        + store.getRegionInfo().getEncodedName() + ", family="
        + store.getFamily().getNameAsString() + ", ScanType=" + scanType + ", smallestReadPoint="
        + smallestReadPoint + ", earliestPutTs=" + earliestPutTs + ", cleanTs=" + cleanTs);
    return scanner;
  }
}
