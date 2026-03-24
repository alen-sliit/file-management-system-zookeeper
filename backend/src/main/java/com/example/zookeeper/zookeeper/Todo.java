package com.example.zookeeper.zookeeper;

/**
 * TODO checklist for ZooKeeper integration.
 */
public final class Todo {
    private Todo() {
    }

    public static final String[] ITEMS = {
            "TODO: Implement ZooKeeper client manager with automatic reconnect.",
            "TODO: Create root znodes (/fms/leader, /fms/nodes, /fms/files, /fms/locks).",
            "TODO: Add safe create-if-not-exists helpers for znodes.",
            "TODO: Register and process watchers for membership and election.",
            "TODO: Handle session expiration and rebuild ephemeral nodes."
    };
}
