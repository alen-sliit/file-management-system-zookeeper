package com.example.zookeeper.file.controller;

/**
 * TODO checklist for file REST controllers.
 */
public final class Todo {
    private Todo() {
    }

    public static final String[] ITEMS = {
            "TODO: Implement POST /files/upload endpoint.",
            "TODO: Implement GET /files/{fileId} endpoint.",
            "TODO: Implement GET /files/{fileId}/metadata endpoint.",
            "TODO: Validate request payloads and return clear errors.",
            "TODO: Route write operations to current leader node."
    };
}
