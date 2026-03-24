package com.example.zookeeper.election;

/**
 * TODO checklist for leader election.
 */
public final class Todo {
    private Todo() {
    }

    public static final String[] ITEMS = {
            "TODO: Create ephemeral sequential candidate node under /fms/leader.",
            "TODO: Determine leader by smallest sequence number.",
            "TODO: Watch predecessor node to avoid herd effect.",
            "TODO: Publish leadership state changes to application services.",
            "TODO: Add leader handover test for active write workload."
    };
}
