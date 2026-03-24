package com.example.zookeeper.common.exception;

/**
 * TODO checklist for exception handling.
 */
public final class Todo {
    private Todo() {
    }

    public static final String[] ITEMS = {
            "TODO: Define domain exceptions for leader redirect and replication failure.",
            "TODO: Map exceptions to stable HTTP status codes.",
            "TODO: Add retryable vs non-retryable error classification.",
            "TODO: Add correlation IDs to error responses.",
            "TODO: Ensure stack traces are not leaked to API clients."
    };
}
