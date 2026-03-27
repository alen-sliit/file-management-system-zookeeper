package com.example.zookeeper.config;

import java.util.Arrays;
import java.util.List;

/**
 * Configuration for ZooKeeper ensemble (multiple servers).
 * Handles connection strings and quorum information.
 */
public class EnsembleConfig {
    
    private final List<String> servers;
    private final int quorumSize;
    private final String connectionString;
    
    /**
     * Create ensemble configuration.
     * @param servers List of server addresses (e.g., ["localhost:2181", "localhost:2182", "localhost:2183"])
     */
    public EnsembleConfig(List<String> servers) {
        this.servers = servers;
        this.quorumSize = (servers.size() / 2) + 1;  // Majority = (n/2) + 1
        this.connectionString = String.join(",", servers);
    }
    
    /**
     * Create a 3-server local ensemble for testing.
     */
    public static EnsembleConfig localThreeNode() {
        return new EnsembleConfig(Arrays.asList(
            "localhost:2181",
            "localhost:2182",
            "localhost:2183"
        ));
    }
    
    /**
     * Create a 5-server local ensemble for testing.
     */
    public static EnsembleConfig localFiveNode() {
        return new EnsembleConfig(Arrays.asList(
            "localhost:2181",
            "localhost:2182",
            "localhost:2183",
            "localhost:2184",
            "localhost:2185"
        ));
    }
    
    /**
     * Create a single-server config for development.
     */
    public static EnsembleConfig singleNode() {
        return new EnsembleConfig(Arrays.asList("localhost:2181"));
    }
    
    public List<String> getServers() { return servers; }
    public int getQuorumSize() { return quorumSize; }
    public String getConnectionString() { return connectionString; }
    public int getTotalServers() { return servers.size(); }
    
    /**
     * Check how many failures this ensemble can tolerate.
     */
    public int getTolerableFailures() {
        return servers.size() - quorumSize;
    }
    
    @Override
    public String toString() {
        return "EnsembleConfig{" +
                "servers=" + servers +
                ", quorumSize=" + quorumSize +
                ", tolerableFailures=" + getTolerableFailures() +
                '}';
    }
}