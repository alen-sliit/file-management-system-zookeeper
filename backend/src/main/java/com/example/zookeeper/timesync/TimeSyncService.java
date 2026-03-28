package com.example.zookeeper.timesync;

import java.net.URL;

/**
 * Entry point for running the time-sync demo from a packaged JAR.
 *
 * <p>Optional args: {@code [serverId] [ntpPollSeconds]} — defaults {@code standalone} and {@code 5}.</p>
 */
public final class TimeSyncService {

    private TimeSyncService() {
    }

    public static void main(String[] args) throws InterruptedException {
        URL cfg = TimeSyncService.class.getClassLoader().getResource(NTPManager.CONFIG_RESOURCE);
        System.out.println("[TimeSync] config: " + NTPManager.CONFIG_RESOURCE
                + " -> " + (cfg != null ? cfg : "MISSING (built-in defaults)"));

        String serverId = args.length > 0 ? args[0] : "standalone";
        long pollSec = 5;
        if (args.length > 1) {
            try {
                pollSec = Long.parseLong(args[1]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid poll seconds, using default 5");
            }
        }

        SkewHandler timeSync = new SkewHandler(serverId, null, 300, pollSec);
        timeSync.start();
        Thread.sleep(1500);
        System.out.println("Corrected time: " + timeSync.getCurrentTimeInstant());
        System.out.println("[TimeSync] measured |offset| = " + Math.abs(timeSync.getClockOffsetMillis()) + " ms");
        timeSync.handleSkew(timeSync.getClockOffsetMillis());
        System.out.println(timeSync.getHealthReport());
        timeSync.stop();
    }
}
