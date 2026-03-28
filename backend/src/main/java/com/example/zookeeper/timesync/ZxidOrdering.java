package com.example.zookeeper.timesync;

import org.apache.zookeeper.data.Stat;

/**
 * Ordering helper aligned with ZooKeeper / Zab: each mutation has a transaction id (zxid).
 * <p>
 * The cluster agrees on a single total order of updates via zxid; wall-clock time is not needed to
 * decide which metadata write is newer. Application metadata timestamps remain useful for logging
 * and UI, not for correctness of ordering among replicas.
 * </p>
 */
public final class ZxidOrdering {

    private ZxidOrdering() {
    }

    /**
     * True if {@code candidate} is strictly newer than {@code current} in ZooKeeper apply order.
     * Uses {@link Stat#getMzxid()} (zxid of the last change to this znode data or structure),
     * then ZK data {@link Stat#getVersion()}, then application {@code FileMetadata} version as a
     * final tie-breaker.
     */
    public static boolean isMetadataRevisionNewer(Stat candidateStat, int candidateAppVersion,
                                                    Stat currentStat, int currentAppVersion) {
        if (candidateStat == null) {
            return false;
        }
        if (currentStat == null) {
            return true;
        }
        long c = candidateStat.getMzxid();
        long b = currentStat.getMzxid();
        if (c != b) {
            return c > b;
        }
        int cv = candidateStat.getVersion();
        int bv = currentStat.getVersion();
        if (cv != bv) {
            return cv > bv;
        }
        return candidateAppVersion > currentAppVersion;
    }
}
