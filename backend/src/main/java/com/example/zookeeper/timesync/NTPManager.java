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
 * <p>
 * Severity bands are tuned for typical NTP: millisecond-class offsets are normal; only much larger
 * drift is treated as ERROR/CRITICAL or rejected for writes (see {@link #CONFIG_RESOURCE}).
 * </p>
 */
public class NTPManager {

    /**
     * Package-unique resource path so the shaded uber-JAR does not accidentally load another
     * artifact's {@code time_sync_config.properties} from the default (root) classpath.
     */
    public static final String CONFIG_RESOURCE = "com/example/zookeeper/timesync/time_sync_config.properties";

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

    private static final class SkewThresholds {
        final long warnMs;
        final long errorMs;
        final long criticalMs;
        final long writeRejectMs;

        SkewThresholds(long warnMs, long errorMs, long criticalMs, long writeRejectMs) {
            this.warnMs = warnMs;
            this.errorMs = errorMs;
            this.criticalMs = criticalMs;
            this.writeRejectMs = writeRejectMs;
        }
    }

    private final String ntpServer;
    private long offsetMillis;
    private volatile boolean lastSyncSuccessful;
    private volatile long maxAbsOffsetMs;

    private final SkewThresholds thresholds;

    public NTPManager(String ntpServer) {
        this(ntpServer, loadThresholdsFromClasspath());
    }

    private NTPManager(String ntpServer, SkewThresholds thresholds) {
        this.ntpServer = ntpServer;
        this.offsetMillis = 0;
        this.thresholds = thresholds;
    }

    private static SkewThresholds loadThresholdsFromClasspath() {
        Properties p = new Properties();
        try (InputStream in = NTPManager.class.getClassLoader()
                .getResourceAsStream(CONFIG_RESOURCE)) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException ignored) {
            // defaults below
        }
        // WARN: log when drift is noticeable (typical NTP is often under 100 ms).
        long warn = parseLong(p.getProperty("drift.threshold.ms"), 100L);
        // ERROR: only clearly broken sync (seconds+); keeps typical NTP jitter at WARN.
        long error = parseLong(p.getProperty("skew.error.threshold.ms"), 10_000L);
        // CRITICAL: extreme skew only.
        long critical = parseLong(p.getProperty("skew.critical.threshold.ms"), 60_000L);
        // Reject writes only when offset is far worse than typical NTP variance.
        long writeReject = parseLong(p.getProperty("skew.write.reject.threshold.ms"), 30_000L);
        // Backward compat: old key used one bucket for both ERROR labelling and write reject.
        if (p.containsKey("skew.severe.threshold.ms") && !p.containsKey("skew.write.reject.threshold.ms")) {
            long legacy = parseLong(p.getProperty("skew.severe.threshold.ms"), 100L);
            if (legacy < writeReject) {
                writeReject = Math.max(legacy, 500L);
            }
        }
        if (warn >= error) {
            error = warn + 1;
        }
        if (error >= critical) {
            critical = error + 1;
        }
        if (writeReject < error) {
            writeReject = error;
        }
        return new SkewThresholds(warn, error, critical, writeReject);
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
                .getResourceAsStream(CONFIG_RESOURCE)) {
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
        if (abs >= thresholds.criticalMs) {
            return ClockSkewSeverity.CRITICAL;
        }
        if (abs >= thresholds.errorMs) {
            return ClockSkewSeverity.ERROR;
        }
        if (abs >= thresholds.warnMs) {
            return ClockSkewSeverity.WARN;
        }
        return ClockSkewSeverity.OK;
    }

    /**
     * Whether the measured offset is acceptable for coordinating writes from a clock perspective.
     * Uses {@code skew.write.reject.threshold.ms}: normal NTP variance (often tens to low hundreds of ms)
     * does not fail this check; only clearly broken sync does.
     */
    public boolean isClockSkewAcceptable() {
        return Math.abs(offsetMillis) < thresholds.writeRejectMs;
    }

    /** True if {@code absOffsetMillis} would be logged as a severe skew alert (same band as ERROR+). */
    public boolean exceedsSevereAlertThreshold(long absOffsetMillis) {
        return absOffsetMillis >= thresholds.errorMs;
    }

    public long getWarnThresholdMs() {
        return thresholds.warnMs;
    }

    public long getErrorThresholdMs() {
        return thresholds.errorMs;
    }

    public long getCriticalThresholdMs() {
        return thresholds.criticalMs;
    }

    public long getWriteRejectThresholdMs() {
        return thresholds.writeRejectMs;
    }

    public Map<String, Object> getPeerClockInfo() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ntpServer", ntpServer);
        m.put("offsetMillis", offsetMillis);
        m.put("lastSyncSuccessful", lastSyncSuccessful);
        m.put("maxAbsOffsetMs", maxAbsOffsetMs);
        m.put("severity", getSeverity().name());
        m.put("warnThresholdMs", thresholds.warnMs);
        m.put("errorThresholdMs", thresholds.errorMs);
        m.put("criticalThresholdMs", thresholds.criticalMs);
        m.put("writeRejectThresholdMs", thresholds.writeRejectMs);
        return m;
    }
}
