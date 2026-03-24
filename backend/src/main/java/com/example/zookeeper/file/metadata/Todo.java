package com.example.zookeeper.file.metadata;

/**
 * TODO checklist for metadata persistence and versioning.
 */
public final class Todo {
    private Todo() {
    }

    public static final String[] ITEMS = {
            "TODO: Persist file metadata in /fms/files/<fileId> znode.",
            "TODO: Use optimistic version checks for metadata updates.",
            "TODO: Implement metadata read cache with invalidation.",
            "TODO: Add metadata consistency checks on startup.",
            "TODO: Add metadata migration path for schema updates."
    };
}
