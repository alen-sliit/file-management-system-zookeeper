package com.example.zookeeper.file.service;

/**
 * TODO checklist for file services.
 */
public final class Todo {
    private Todo() {
    }

    public static final String[] ITEMS = {
            "TODO: Implement file upload orchestration service.",
            "TODO: Implement file download service with replica fallback.",
            "TODO: Add version-aware read and write logic.",
            "TODO: Add retry policy for transient replication failures.",
            "TODO: Add idempotency support for repeated client requests."
    };
}
