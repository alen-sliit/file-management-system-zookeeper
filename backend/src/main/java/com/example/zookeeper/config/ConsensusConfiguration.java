package com.example.zookeeper.config;

/**
 * Central configuration for ZooKeeper consensus, leader election, and cluster
 * membership.
 */
public final class ConsensusConfiguration {

    private final String zooKeeperConnectString;
    private final int sessionTimeoutMs;
    private final int connectionTimeoutMs;
    private final String nodeId;
    private final String nodeHost;
    private final int nodePort;
    private final int replicationFactor;
    private final int expectedClusterSize;

    private ConsensusConfiguration(
            String zooKeeperConnectString,
            int sessionTimeoutMs,
            int connectionTimeoutMs,
            String nodeId,
            String nodeHost,
            int nodePort,
            int replicationFactor,
            int expectedClusterSize) {
        this.zooKeeperConnectString = zooKeeperConnectString;
        this.sessionTimeoutMs = sessionTimeoutMs;
        this.connectionTimeoutMs = connectionTimeoutMs;
        this.nodeId = nodeId;
        this.nodeHost = nodeHost;
        this.nodePort = nodePort;
        this.replicationFactor = replicationFactor;
        this.expectedClusterSize = expectedClusterSize;
    }

    public static ConsensusConfiguration load() {
        String zooKeeperConnectString = readString("ZK_CONNECT", "localhost:2181");
        int sessionTimeoutMs = readInt("ZK_SESSION_TIMEOUT_MS", 3000);
        int connectionTimeoutMs = readInt("ZK_CONNECTION_TIMEOUT_MS", 5000);
        String nodeId = readString("NODE_ID", "node-demo");
        String nodeHost = readString("NODE_HOST", "localhost");
        int nodePort = readInt("NODE_PORT", 8080);
        int replicationFactor = readInt("REPLICATION_FACTOR", 3);
        int expectedClusterSize = readInt("CLUSTER_SIZE", 3);

        ConsensusConfiguration configuration = new ConsensusConfiguration(
                zooKeeperConnectString,
                sessionTimeoutMs,
                connectionTimeoutMs,
                nodeId,
                nodeHost,
                nodePort,
                replicationFactor,
                expectedClusterSize);
        configuration.validate();
        return configuration;
    }

    public void validate() {
        if (zooKeeperConnectString == null || zooKeeperConnectString.trim().isEmpty()) {
            throw new IllegalStateException("ZK_CONNECT must not be empty");
        }
        if (sessionTimeoutMs <= 0) {
            throw new IllegalStateException("ZK_SESSION_TIMEOUT_MS must be positive");
        }
        if (connectionTimeoutMs <= 0) {
            throw new IllegalStateException("ZK_CONNECTION_TIMEOUT_MS must be positive");
        }
        if (nodeId == null || nodeId.trim().isEmpty()) {
            throw new IllegalStateException("NODE_ID must not be empty");
        }
        if (nodeHost == null || nodeHost.trim().isEmpty()) {
            throw new IllegalStateException("NODE_HOST must not be empty");
        }
        if (nodePort <= 0 || nodePort > 65535) {
            throw new IllegalStateException("NODE_PORT must be between 1 and 65535");
        }
        if (replicationFactor <= 0) {
            throw new IllegalStateException("REPLICATION_FACTOR must be positive");
        }
        if (expectedClusterSize <= 0) {
            throw new IllegalStateException("CLUSTER_SIZE must be positive");
        }
    }

    public String getZooKeeperConnectString() {
        return zooKeeperConnectString;
    }

    public int getSessionTimeoutMs() {
        return sessionTimeoutMs;
    }

    public int getConnectionTimeoutMs() {
        return connectionTimeoutMs;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getNodeHost() {
        return nodeHost;
    }

    public int getNodePort() {
        return nodePort;
    }

    public int getReplicationFactor() {
        return replicationFactor;
    }

    public int getExpectedClusterSize() {
        return expectedClusterSize;
    }

    @Override
    public String toString() {
        return "ConsensusConfiguration{" +
                "zooKeeperConnectString='" + zooKeeperConnectString + '\'' +
                ", sessionTimeoutMs=" + sessionTimeoutMs +
                ", connectionTimeoutMs=" + connectionTimeoutMs +
                ", nodeId='" + nodeId + '\'' +
                ", nodeHost='" + nodeHost + '\'' +
                ", nodePort=" + nodePort +
                ", replicationFactor=" + replicationFactor +
                ", expectedClusterSize=" + expectedClusterSize +
                '}';
    }

    private static String readString(String key, String defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return value.trim();
    }

    private static int readInt(String key, int defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("Invalid integer for " + key + ": " + value, ex);
        }
    }
}
