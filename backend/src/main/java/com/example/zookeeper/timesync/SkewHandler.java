package com.example.zookeeper.timesync;

import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Time sync facade: NTP polling, skew thresholds, and local write-gating.
 * <p>
 * Under Zab, ZooKeeper assigns a monotonic transaction id (zxid) to each applied change; all replicas
 * agree on the same order of updates. Correctness for which metadata revision wins therefore uses
 * {@link Stat#getMzxid()} (via {@link ZxidOrdering}), not wall-clock timestamps. Clocks here only
 * support human-readable timestamps, logging, caches, and policy such as {@link #canPerformWriteOperation()}.
 * </p>
 */
public class SkewHandler {

    private static final Logger logger = LoggerFactory.getLogger(SkewHandler.class);

    private final String serverId;
    private final ZooKeeper zooKeeper;
    private final NTPManager skewMonitor;
    private final ClockMonitor clockSynchronizer;

    private volatile boolean running;

    /**
     * @param syncIntervalSeconds reserved for policy / logging (NTP poll uses {@code monitorIntervalSeconds} when &gt; 0)
     * @param monitorIntervalSeconds NTP poll period in seconds ({@link com.example.zookeeper.server.StorageNode} passes 30)
     */
    public SkewHandler(String serverId, ZooKeeper zooKeeper,
                       long syncIntervalSeconds, long monitorIntervalSeconds) {
        this.serverId = serverId;
        this.zooKeeper = zooKeeper;
        long pollSec = monitorIntervalSeconds > 0 ? monitorIntervalSeconds : Math.max(1, syncIntervalSeconds);
        this.skewMonitor = NTPManager.fromClasspathDefaults();
        this.clockSynchronizer = new ClockMonitor(serverId, skewMonitor, pollSec);
        if (syncIntervalSeconds > 0) {
            logger.debug("TimeSync policy syncIntervalSeconds={} (poll={}s)", syncIntervalSeconds, pollSec);
        }
    }

    public void start() {
        if (running) {
            logger.warn("TimeSyncService already running for server {}", serverId);
            return;
        }
        clockSynchronizer.forceSync();
        clockSynchronizer.start();
        running = true;
        logger.info("TimeSyncService started for server {}", serverId);
    }

    public void stop() {
        if (!running) {
            return;
        }
        clockSynchronizer.stop();
        running = false;
        logger.info("TimeSyncService stopped for server {}", serverId);
    }

    public long getCurrentTimeMillis() {
        return clockSynchronizer.getCurrentTimeMillis();
    }

    public Instant getCurrentTimeInstant() {
        return clockSynchronizer.getCurrentTimeInstant();
    }

    public boolean isTimeSyncHealthy() {
        return clockSynchronizer.isSynchronized() && skewMonitor.isClockSkewAcceptable();
    }

    public long getClockOffsetMillis() {
        return clockSynchronizer.getClockOffsetMillis();
    }

    public long getMaxClockSkewMs() {
        return skewMonitor.getMaxSkewMs();
    }

    public NTPManager.ClockSkewSeverity getSkewSeverity() {
        return skewMonitor.getSeverity();
    }

    public void forceSyncNow() {
        clockSynchronizer.forceSync();
        logger.info("Forced immediate clock sync for server {}", serverId);
    }

    public boolean isClockSkewAcceptable() {
        return skewMonitor.isClockSkewAcceptable();
    }

    public Map<String, Object> getTimeSyncStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("serverId", serverId);
        status.put("running", running);
        status.put("healthy", isTimeSyncHealthy());
        status.put("synchronizer", clockSynchronizer.getSyncStatus());
        status.put("skewMonitor", skewMonitor.getPeerClockInfo());
        status.put("currentTime", getCurrentTimeInstant().toString());
        status.put("skewSeverity", getSkewSeverity().name());
        status.put("acceptableSkew", isClockSkewAcceptable());
        if (zooKeeper != null) {
            status.put("zookeeperSession", zooKeeper.getSessionId());
            appendSampleZxidStatus(status);
        }
        status.put("ordering",
                "ZooKeeper zxid (Zab) orders metadata updates; NTP does not define revision order.");
        return status;
    }

    /** Optional diagnostic: last-modify zxid on {@code /} as an example cluster-applied transaction id. */
    private void appendSampleZxidStatus(Map<String, Object> status) {
        try {
            if (zooKeeper.getState() != ZooKeeper.States.CONNECTED) {
                return;
            }
            Stat root = zooKeeper.exists("/", false);
            if (root != null) {
                status.put("zkRootMzxid", root.getMzxid());
            }
        } catch (Exception e) {
            logger.debug("Could not read root zxid for status: {}", e.getMessage());
        }
    }

    public void handleSyncFailure() {
        logger.error("Time synchronization FAILURE detected for server {}", serverId);
        try {
            forceSyncNow();
        } catch (Exception e) {
            logger.error("Failed to recover from sync failure for server {}", serverId, e);
        }
        if (!isClockSkewAcceptable()) {
            logger.error("CRITICAL: Clock skew too large for server {} ({} ms). Recommend admin review.",
                    serverId, getMaxClockSkewMs());
        }
    }

    public boolean canPerformWriteOperation() {
        if (!clockSynchronizer.isSynchronized()) {
            logger.warn("Write operation attempted with unsynchronized clock on server {}", serverId);
            return false;
        }
        if (!isClockSkewAcceptable()) {
            logger.error("Write operation REJECTED due to critical clock skew on server {}", serverId);
            return false;
        }
        if (getSkewSeverity().level >= NTPManager.ClockSkewSeverity.ERROR.level) {
            logger.warn("Write allowed but clock skew is ERROR on server {}", serverId);
        }
        return true;
    }

    public boolean canPerformReadOperation() {
        if (getSkewSeverity().level >= NTPManager.ClockSkewSeverity.CRITICAL.level) {
            logger.error("Read with CRITICAL clock skew on server {}", serverId);
        } else if (getSkewSeverity().level >= NTPManager.ClockSkewSeverity.ERROR.level) {
            logger.warn("Read with ERROR-level clock skew on server {}: {} ms",
                    serverId, getMaxClockSkewMs());
        }
        return true;
    }

    public String getHealthReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== TIME SYNCHRONIZATION REPORT for ").append(serverId).append(" ===\n");
        sb.append(String.format("Status: %s\n", running ? "RUNNING" : "STOPPED"));
        sb.append(String.format("Healthy: %s\n", isTimeSyncHealthy()));
        sb.append(String.format("NTP Synchronized: %s\n", clockSynchronizer.isSynchronized()));
        sb.append(String.format("Clock Offset: %d ms\n", getClockOffsetMillis()));
        sb.append(String.format("Max Skew Detected: %d ms\n", getMaxClockSkewMs()));
        sb.append(String.format("Skew Severity: %s\n", getSkewSeverity()));
        sb.append(String.format("Skew Acceptable: %s\n", isClockSkewAcceptable()));
        sb.append(String.format("Current Time: %s\n", getCurrentTimeInstant()));
        sb.append(String.format("Can Write: %s\n", canPerformWriteOperation()));
        sb.append(String.format("Can Read: %s\n", canPerformReadOperation()));
        sb.append("Ordering: revision order follows ZooKeeper mzxid / Zab, not clock time ");
        sb.append("(see ZxidOrdering). Timestamps are informational.\n");
        if (zooKeeper != null) {
            try {
                if (zooKeeper.getState() == ZooKeeper.States.CONNECTED) {
                    Stat root = zooKeeper.exists("/", false);
                    if (root != null) {
                        sb.append(String.format("ZK sample mzxid (on /): %d\n", root.getMzxid()));
                    }
                }
            } catch (Exception e) {
                sb.append("(ZK mzxid sample unavailable)\n");
            }
        }
        return sb.toString();
    }

    public boolean isRunning() {
        return running;
    }

    /** Optional: alert on very large measured offset (e.g. admin / metrics hook). */
    public void handleSkew(long offsetMillis) {
        long severe = 100L;
        try (java.io.InputStream in = SkewHandler.class.getClassLoader()
                .getResourceAsStream("time_sync_config.properties")) {
            if (in != null) {
                java.util.Properties p = new java.util.Properties();
                p.load(in);
                severe = Long.parseLong(p.getProperty("skew.severe.threshold.ms", "100").trim());
            }
        } catch (Exception ignored) {
        }
        if (Math.abs(offsetMillis) > severe) {
            logger.error("[TimeSync] Severe clock skew detected: {} ms", offsetMillis);
        }
    }
}
