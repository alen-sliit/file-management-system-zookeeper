package com.example.zookeeper.file.replication;

/**
 * TODO checklist for replication and consistency.
 */
public final class Todo {
    private Todo() {
    }

    public static final String[] ITEMS = {
            "TODO: Select RF=3 target replicas for each upload.",
            "TODO: Replicate writes asynchronously from leader to followers.",
            "TODO: Track per-replica ack status and retry queue.",
            "TODO: Implement read-repair for stale replicas.",
            "TODO: Add conflict resolution policy for concurrent writes."
    };
}
