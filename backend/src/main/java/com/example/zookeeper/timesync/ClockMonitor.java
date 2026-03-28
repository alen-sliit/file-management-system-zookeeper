package com.example.zookeeper.timesync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Periodically syncs with NTP via {@link NTPManager}. Safe to use alongside ZooKeeper:
 * zxid orders state changes; this only stabilizes wall-clock timestamps for metadata.
 */
public class ClockMonitor {

    private static final Logger logger = LoggerFactory.getLogger(ClockMonitor.class);

    private final String serverId;
    private final NTPManager ntpManager;
    private final long pollIntervalMillis;

    private volatile boolean running;
    private Thread worker;
    private volatile boolean synchronizedFlag;

    /**
     * @param pollIntervalSeconds how often to query NTP
     */
    public ClockMonitor(String serverId, NTPManager ntpManager, long pollIntervalSeconds) {
        this.serverId = serverId;
        this.ntpManager = ntpManager;
        this.pollIntervalMillis = Math.max(1L, pollIntervalSeconds) * 1000L;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        worker = new Thread(this::loop, "clock-monitor-" + serverId);
        worker.setDaemon(true);
        worker.start();
    }

    public void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            worker = null;
        }
    }

    private void loop() {
        while (running) {
            try {
                ntpManager.syncWithNTP();
                synchronizedFlag = ntpManager.wasLastSyncSuccessful();
                if (!synchronizedFlag) {
                    logger.warn("[TimeSync] NTP sync did not update offset on server {}", serverId);
                }
            } catch (Exception e) {
                synchronizedFlag = false;
                logger.warn("[TimeSync] NTP sync error on server {}: {}", serverId, e.getMessage());
            }
            try {
                Thread.sleep(pollIntervalMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public long getCurrentTimeMillis() {
        return ntpManager.getCorrectedTime().toEpochMilli();
    }

    public Instant getCurrentTimeInstant() {
        return ntpManager.getCorrectedTime();
    }

    /** True if the last completed sync obtained an NTP offset */
    public boolean isSynchronized() {
        return synchronizedFlag;
    }

    public long getClockOffsetMillis() {
        return ntpManager.getOffset();
    }

    public void forceSync() {
        ntpManager.syncWithNTP();
        synchronizedFlag = ntpManager.wasLastSyncSuccessful();
    }

    public Map<String, Object> getSyncStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("serverId", serverId);
        m.put("synchronized", synchronizedFlag);
        m.put("offsetMillis", ntpManager.getOffset());
        m.put("pollIntervalMillis", pollIntervalMillis);
        return m;
    }
}
