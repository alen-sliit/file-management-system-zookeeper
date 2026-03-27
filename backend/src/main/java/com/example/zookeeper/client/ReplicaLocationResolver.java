package com.example.zookeeper.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Resolves replica locations that are both present on disk and, optionally, on alive servers.
 */
public class ReplicaLocationResolver {
    private final Path storageRoot;

    public ReplicaLocationResolver(Path storageRoot) {
        this.storageRoot = storageRoot;
    }

    public StorageClient.FileMetadata withExistingLocations(StorageClient.FileMetadata metadata) {
        List<String> existing = new ArrayList<>();
        for (String location : metadata.getLocations()) {
            Path replicaPath = storageRoot.resolve(location).resolve(metadata.getFileId());
            if (Files.exists(replicaPath)) {
                existing.add(location);
            }
        }
        metadata.setLocations(existing);
        return metadata;
    }

    public StorageClient.FileMetadata withAvailableLocations(StorageClient.FileMetadata metadata, Set<String> aliveServers) {
        List<String> existing = new ArrayList<>();
        for (String location : metadata.getLocations()) {
            if (!aliveServers.contains(location)) {
                continue;
            }
            Path replicaPath = storageRoot.resolve(location).resolve(metadata.getFileId());
            if (Files.exists(replicaPath)) {
                existing.add(location);
            }
        }
        metadata.setLocations(existing);
        return metadata;
    }
}
