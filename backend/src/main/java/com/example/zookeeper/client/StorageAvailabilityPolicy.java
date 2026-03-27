package com.example.zookeeper.client;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Encapsulates availability and quorum checks for storage operations.
 */
public class StorageAvailabilityPolicy {
    private final int minMajorityServers;
    private final int replicationFactor;

    public StorageAvailabilityPolicy(int minMajorityServers, int replicationFactor) {
        this.minMajorityServers = minMajorityServers;
        this.replicationFactor = replicationFactor;
    }

    public Set<String> requireAliveServers(List<String> aliveServers, String operation) throws Exception {
        if (aliveServers == null || aliveServers.isEmpty()) {
            throw new Exception("No active storage servers available for " + operation);
        }
        return new HashSet<>(aliveServers);
    }

    public void ensureUploadQuorum(List<String> aliveServers) throws Exception {
        if (aliveServers == null || aliveServers.size() < minMajorityServers) {
            throw new Exception("Upload requires at least " + minMajorityServers + " active storage servers");
        }
    }

    public void ensureReplicationSatisfied(int successfulCopies) throws Exception {
        if (successfulCopies < replicationFactor) {
            throw new Exception("Upload failed: could not satisfy replication factor " + replicationFactor);
        }
    }
}
