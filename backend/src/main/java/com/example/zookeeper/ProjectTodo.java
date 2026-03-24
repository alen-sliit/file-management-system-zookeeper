package com.example.zookeeper;

/**
 * Top-level implementation checklist for the distributed file management system.
 */
public final class ProjectTodo {
    private ProjectTodo() {
    }

    public static final String[] ITEMS = {
            "TODO: Convert App.java demo into a long-running backend service.",
            "TODO: Add REST endpoints for file upload, download, and metadata.",
            "TODO: Wire leader election so only leader accepts write requests.",
            "TODO: Enforce RF=3 replication for every stored file.",
            "TODO: Add tests for failover, rejoin, and concurrent writes."
    };
}
