package com.example.zookeeper.config;

/**
 * TODO checklist for configuration.
 */
public final class Todo {
    private Todo() {
    }

    public static final String[] ITEMS = {
            "TODO: Read ZooKeeper hosts from environment variables.",
            "TODO: Add session timeout and connection timeout configuration.",
            "TODO: Add replication factor and cluster size configuration.",
            "TODO: Add storage root path and max upload size settings.",
            "TODO: Validate required configuration values at startup."
    };
}
