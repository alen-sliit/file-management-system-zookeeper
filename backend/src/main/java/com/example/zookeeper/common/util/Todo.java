package com.example.zookeeper.common.util;

/**
 * TODO checklist for utility helpers.
 */
public final class Todo {
    private Todo() {
    }

    public static final String[] ITEMS = {
            "TODO: Add JSON utility for metadata serialization.",
            "TODO: Add checksum utility helpers.",
            "TODO: Add clock utility for drift-aware timestamps.",
            "TODO: Add path utility for safe local file paths.",
            "TODO: Add retry utility with exponential backoff."
    };
}
