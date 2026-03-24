package com.example.zookeeper.file;

/**
 * TODO checklist for file domain module.
 */
public final class Todo {
    private Todo() {
    }

    public static final String[] ITEMS = {
            "TODO: Define file lifecycle states (UPLOADING, AVAILABLE, DELETED).",
            "TODO: Enforce file ID and naming conventions.",
            "TODO: Add end-to-end orchestration from upload to replication.",
            "TODO: Add authorization hooks for file operations.",
            "TODO: Add audit logging for file access and changes."
    };
}
