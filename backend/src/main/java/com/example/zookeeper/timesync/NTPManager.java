package com.example.zookeeper.timesync;

import org.apache.commons.net.ntp.NTPUDPClient;
import org.apache.commons.net.ntp.TimeInfo;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Queries NTP and tracks offset/skew. ZooKeeper still orders operations via zxid;
 * corrected wall clock is used for metadata timestamps and logging.
 */
public class NTPManager {

    public enum ClockSkewSeverity {
        OK(0),
        WARN(1),
        ERROR(2),
        CRITICAL(3);

        public final int level;

        ClockSkewSeverity(int level) {
            this.level = level;
        }
    }

    private final String ntpServer;
    private long offsetMillis;
    private volatile boolean lastSyncSuccessful;
    private volatile long maxAbsOffsetMs;

    private final long warnThresholdMs;
    private final long severeThresholdMs;

    public NTPManager(String ntpServer) {
        this(ntpServer, loadThresholdsFromClasspath());
    }

    private NTPManager(String ntpServer, long[] thresholds) {
        this.ntpServer = ntpServer;
        this.offsetMillis = 0;
        this.warnThresholdMs = thresholds[0];
        this.severeThresholdMs = thresholds[1];
    }

    private static long[] loadThresholdsFromClasspath() {
        Properties p = new Properties();
        try (InputStream in = NTPManager.class.getClassLoader()
                .getResourceAsStream("time_sync_config.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException ignored) {
            // defaults below
        }
        long warn = parseLong(p.getProperty("drift.threshold.ms"), 50L);
        long severe = parseLong(p.getProperty("skew.severe.threshold.ms"), 100L);
        return new long[] { warn, severe };
    }

    private static long parseLong(String s, long def) {
        if (s == null || s.isBlank()) {
            return def;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** NTP host from {@code time_sync_config.properties} if present, else pool.ntp.org */
    public static NTPManager fromClasspathDefaults() {
        Properties p = new Properties();
        try (InputStream in = NTPManager.class.getClassLoader()
                .getResourceAsStream("time_sync_config.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException ignored) {
        }
        String host = p.getProperty("ntp.server", "pool.ntp.org").trim();
        return new NTPManager(host);
    }

    public void syncWithNTP() {
        NTPUDPClient client = new NTPUDPClient();
        client.setDefaultTimeout(5000);
        try {
            InetAddress hostAddr = InetAddress.getByName(ntpServer);
            TimeInfo info = client.getTime(hostAddr);
            info.computeDetails();
            if (info.getOffset() != null) {
                offsetMillis = info.getOffset();
                long abs = Math.abs(offsetMillis);
                maxAbsOffsetMs = Math.max(maxAbsOffsetMs, abs);
                lastSyncSuccessful = true;
            } else {
                lastSyncSuccessful = false;
            }
        } catch (IOException e) {
            lastSyncSuccessful = false;
            System.err.println("[TimeSync] Failed to sync with NTP server: " + e.getMessage());
        } finally {
            client.close();
        }
    }

    public long getOffset() {
        return offsetMillis;
    }

    public boolean wasLastSyncSuccessful() {
        return lastSyncSuccessful;
    }

    public long getMaxSkewMs() {
        return maxAbsOffsetMs;
    }

    public Instant getCorrectedTime() {
        return Instant.now().plusMillis(offsetMillis);
    }

    public ClockSkewSeverity getSeverity() {
        long abs = Math.abs(offsetMillis);
        if (abs >= severeThresholdMs * 5 || abs >= 500) {
            return ClockSkewSeverity.CRITICAL;
        }
        if (abs >= severeThresholdMs) {
            return ClockSkewSeverity.ERROR;
        }
        if (abs >= warnThresholdMs) {
            return ClockSkewSeverity.WARN;
        }
        return ClockSkewSeverity.OK;
    }

    public boolean isClockSkewAcceptable() {
        return Math.abs(offsetMillis) < severeThresholdMs;
    }

    public Map<String, Object> getPeerClockInfo() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ntpServer", ntpServer);
        m.put("offsetMillis", offsetMillis);
        m.put("lastSyncSuccessful", lastSyncSuccessful);
        m.put("maxAbsOffsetMs", maxAbsOffsetMs);
        m.put("severity", getSeverity().name());
        m.put("warnThresholdMs", warnThresholdMs);
        m.put("severeThresholdMs", severeThresholdMs);
        return m;
    }
}
