package com.example.zookeeper.cluster;

/**
 * TODO checklist for cluster membership and discovery.
 */
public final class Todo {
    private Todo() {
    }

    public static final String[] ITEMS = {
            "TODO: Register each backend node as ephemeral /fms/nodes/node-<id>.",
            "TODO: Track host, port, and state in node metadata.",
            "TODO: Watch membership changes and refresh in-memory view.",
            "TODO: Expose GET /cluster/leader and GET /cluster/nodes.",
            "TODO: Mark unhealthy nodes and route around them."
    };
}
