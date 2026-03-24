package com.example.zookeeper.recovery;

/**
 * TODO checklist for node and data recovery.
 */
public final class Todo {
    private Todo() {
    }

    public static final String[] ITEMS = {
            "TODO: Detect node rejoin events from membership updates.",
            "TODO: Compare local file versions against cluster metadata.",
            "TODO: Fetch and apply missing updates from healthy replicas.",
            "TODO: Verify checksums before marking node as healthy.",
            "TODO: Measure and log full catch-up completion time."
    };
}
