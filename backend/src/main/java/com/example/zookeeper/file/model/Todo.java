package com.example.zookeeper.file.model;

/**
 * TODO checklist for file models and DTOs.
 */
public final class Todo {
    private Todo() {
    }

    public static final String[] ITEMS = {
            "TODO: Define FileMetadata model (fileId, version, replicas, checksum).",
            "TODO: Define NodeInfo model for replica placement.",
            "TODO: Define upload and download response DTOs.",
            "TODO: Add serialization contract for ZooKeeper metadata payloads.",
            "TODO: Add model validation constraints."
    };
}
