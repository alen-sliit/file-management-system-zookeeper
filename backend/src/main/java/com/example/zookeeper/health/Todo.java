package com.example.zookeeper.health;

/**
 * TODO checklist for health and observability.
 */
public final class Todo {
    private Todo() {
    }

    public static final String[] ITEMS = {
            "TODO: Add heartbeat and liveness checks per node.",
            "TODO: Track read latency, write latency, and failover time.",
            "TODO: Add endpoint for cluster and replication health summary.",
            "TODO: Log structured events for failures and recoveries.",
            "TODO: Add alerts for repeated replication retry failures."
    };
}
