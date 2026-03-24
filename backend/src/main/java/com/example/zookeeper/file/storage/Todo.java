package com.example.zookeeper.file.storage;

/**
 * TODO checklist for physical file storage.
 */
public final class Todo {
    private Todo() {
    }

    public static final String[] ITEMS = {
            "TODO: Store binary content with deterministic on-disk layout.",
            "TODO: Add checksum calculation and verification.",
            "TODO: Add chunking strategy for large files.",
            "TODO: Add cleanup for orphaned partial uploads.",
            "TODO: Add disk usage monitoring and backpressure handling."
    };
}
