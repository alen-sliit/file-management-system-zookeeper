package com.example.zookeeper.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Represents metadata for a file stored in the distributed storage system.
 * This is stored in ZooKeeper to track file locations and versions.
 */
public class FileMetadata {
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());
    
    private String fileId;           // Unique identifier for the file
    private String filename;         // Original filename
    private long size;               // File size in bytes
    private Instant createdAt;       // Creation timestamp
    private Instant modifiedAt;      // Last modification timestamp
    private String owner;            // Server ID that owns the file
    private int version;             // Version number for conflict detection
    private List<String> locations;  // Server IDs where file is stored
    private boolean deleted;          // Soft delete flag
    
    public FileMetadata() {
        this.locations = new ArrayList<>();
        this.version = 1;
        this.deleted = false;
    }
    
    public FileMetadata(String filename, long size, String owner) {
        this();
        this.fileId = UUID.randomUUID().toString();
        this.filename = filename;
        this.size = size;
        this.createdAt = Instant.now();
        this.modifiedAt = Instant.now();
        this.owner = owner;
    }
    
    // Getters and setters
    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }
    
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    
    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }
    
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    
    public Instant getModifiedAt() { return modifiedAt; }
    public void setModifiedAt(Instant modifiedAt) { this.modifiedAt = modifiedAt; }
    
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    
    public List<String> getLocations() { return locations; }
    public void setLocations(List<String> locations) { this.locations = locations; }
    
    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }
    
    // Utility methods
    public void addLocation(String serverId) {
        if (!locations.contains(serverId)) {
            locations.add(serverId);
        }
    }
    
    public void removeLocation(String serverId) {
        locations.remove(serverId);
    }
    
    public String toJson() {
        try {
            return objectMapper.writeValueAsString(this);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize metadata", e);
        }
    }
    
    public static FileMetadata fromJson(String json) {
        try {
            return objectMapper.readValue(json, FileMetadata.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize metadata", e);
        }
    }
    
    @Override
    public String toString() {
        return String.format("FileMetadata{id='%s', name='%s', size=%d, version=%d, locations=%s}",
                fileId, filename, size, version, locations);
    }
}
