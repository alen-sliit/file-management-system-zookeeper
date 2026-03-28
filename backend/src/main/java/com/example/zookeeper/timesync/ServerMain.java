package com.example.zookeeper.timesync;

/**
 * Standalone demo for time sync (no ZooKeeper required). For full integration see
 * {@link com.example.zookeeper.server.StorageNode}.
 */
public class ServerMain {

    public static void main(String[] args) throws InterruptedException {
        SkewHandler timeSync = new SkewHandler("demo", null, 300, 5);
        timeSync.start();
        Thread.sleep(1500);
        System.out.println("Corrected time: " + timeSync.getCurrentTimeInstant());
        timeSync.handleSkew(timeSync.getClockOffsetMillis());
        System.out.println(timeSync.getHealthReport());
        timeSync.stop();
    }
}
