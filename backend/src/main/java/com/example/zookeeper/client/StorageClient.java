package com.example.zookeeper.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.zookeeper.*;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Client for the distributed storage system.
 * Handles communication with ZooKeeper and storage servers.
 */
public class StorageClient {
    private static final Logger logger = LoggerFactory.getLogger(StorageClient.class);
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());
    
    // ZooKeeper connection
    private volatile ZooKeeper zooKeeper;
    private final String zookeeperAddress;
    private final Object reconnectLock;
    private volatile boolean closing;
    private final StorageAvailabilityPolicy availabilityPolicy;
    private final ReplicaLocationResolver locationResolver;
    
    // Discovery and caching
    private String currentLeader;
    private final Map<String, FileMetadata> metadataCache;
    private final Map<String, Long> cacheTimestamps;
    
    // ZooKeeper paths
    private static final String LEADER_CURRENT_PATH = "/leader/current";
    private static final String FILES_PATH = "/files";
    private static final String STORAGE_SERVERS_PATH = "/storage-servers";
    private static final int ZK_SESSION_TIMEOUT_MS = 3000;
    private static final int ZK_CONNECT_TIMEOUT_MS = 5000;
    private static final int REPLICATION_FACTOR = 3;
    private static final Path STORAGE_ROOT = Paths.get("./storage").toAbsolutePath().normalize();
    
    // Cache TTL (5 seconds)
    private static final long CACHE_TTL_MS = 5000;
    
    public StorageClient(String zookeeperAddress) throws Exception {
        this.zookeeperAddress = zookeeperAddress;
        this.metadataCache = new ConcurrentHashMap<>();
        this.cacheTimestamps = new ConcurrentHashMap<>();
        this.reconnectLock = new Object();
        this.closing = false;
        this.availabilityPolicy = new StorageAvailabilityPolicy(3, REPLICATION_FACTOR);
        this.locationResolver = new ReplicaLocationResolver(STORAGE_ROOT);
        
        this.zooKeeper = connectZooKeeper();
        
        // Discover initial leader
        refreshLeader();
        
        logger.info("StorageClient initialized. Leader: {}", currentLeader);
    }
    
    /**
     * Refresh the current leader from ZooKeeper
     */
    private void refreshLeader() throws Exception {
        Stat stat = zooKeeper.exists(LEADER_CURRENT_PATH, false);
        if (stat != null) {
            byte[] data = zooKeeper.getData(LEADER_CURRENT_PATH, false, stat);
            currentLeader = new String(data);
            logger.info("Leader refreshed: {}", currentLeader);
        } else {
            currentLeader = null;
            logger.warn("No leader found in ZooKeeper");
        }
    }

    private ZooKeeper connectZooKeeper() throws Exception {
        CountDownLatch connectedSignal = new CountDownLatch(1);
        ZooKeeper zk = new ZooKeeper(zookeeperAddress, ZK_SESSION_TIMEOUT_MS, new Watcher() {
            @Override
            public void process(WatchedEvent event) {
                if (event.getType() == Event.EventType.None &&
                        event.getState() == Event.KeeperState.SyncConnected) {
                    logger.info("Connected to ZooKeeper");
                    connectedSignal.countDown();
                } else if (event.getType() == Event.EventType.None &&
                        event.getState() == Event.KeeperState.Expired) {
                    logger.warn("ZooKeeper session expired");
                    if (!closing) {
                        new Thread(() -> {
                            try {
                                reconnectZooKeeper();
                            } catch (Exception e) {
                                logger.error("Failed to reconnect after session expiration", e);
                            }
                        }, "zk-session-reconnect").start();
                    }
                } else if (event.getType() == Event.EventType.NodeDeleted &&
                        LEADER_CURRENT_PATH.equals(event.getPath())) {
                    logger.warn("Leader node deleted, refreshing...");
                    try {
                        withSessionRecovery("leader refresh", () -> {
                            refreshLeader();
                            return null;
                        });
                    } catch (Exception e) {
                        logger.error("Failed to refresh leader", e);
                    }
                }
            }
        });

        boolean connected = connectedSignal.await(ZK_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (!connected || !zk.getState().isConnected()) {
            try {
                zk.close();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            throw new IOException("Could not connect to ZooKeeper within timeout");
        }
        return zk;
    }

    private void reconnectZooKeeper() throws Exception {
        synchronized (reconnectLock) {
            if (closing) {
                return;
            }

            ZooKeeper oldZk = this.zooKeeper;
            ZooKeeper newZk = connectZooKeeper();
            this.zooKeeper = newZk;

            if (oldZk != null) {
                try {
                    oldZk.close();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            metadataCache.clear();
            cacheTimestamps.clear();
            currentLeader = null;
            refreshLeader();
            logger.info("Reconnected to ZooKeeper and refreshed leader state");
        }
    }

    @FunctionalInterface
    private interface ZkOperation<T> {
        T run() throws Exception;
    }

    private <T> T withSessionRecovery(String operationName, ZkOperation<T> operation) throws Exception {
        try {
            return operation.run();
        } catch (KeeperException.SessionExpiredException e) {
            logger.warn("ZooKeeper session expired during {}. Reconnecting and retrying once.", operationName);
            reconnectZooKeeper();
            return operation.run();
        }
    }
    
    /**
     * Upload a file to the distributed storage
     */
    public boolean uploadFile(String filename, byte[] content) throws Exception {
        return withSessionRecovery("upload", () -> {
            logger.info("Uploading file: {} ({} bytes)", filename, content.length);

            // Ensure we know the leader
            if (currentLeader == null) {
                refreshLeader();
            }

            if (currentLeader == null) {
                throw new Exception("No leader available for upload");
            }

            List<String> aliveServers = getAliveServers();
            availabilityPolicy.ensureUploadQuorum(aliveServers);

            FileMetadata metadata = new FileMetadata(filename, content.length, currentLeader);
            String metadataPath = FILES_PATH + "/" + metadata.getFileId();

            // Write primary on leader directory, then replicate to other servers.
            writeReplica(currentLeader, metadata.getFileId(), content);
            metadata.addLocation(currentLeader);

            int needed = Math.max(0, REPLICATION_FACTOR - 1);
            int replicated = 0;
            for (String server : aliveServers) {
                if (server.equals(currentLeader)) {
                    continue;
                }
                if (replicated >= needed) {
                    break;
                }
                try {
                    writeReplica(server, metadata.getFileId(), content);
                    metadata.addLocation(server);
                    replicated++;
                } catch (Exception e) {
                    logger.warn("Failed to replicate {} to {}", metadata.getFileId(), server, e);
                }
            }

            try {
                availabilityPolicy.ensureReplicationSatisfied(metadata.getLocations().size());
            } catch (Exception e) {
                rollbackReplicas(metadata.getLocations(), metadata.getFileId());
                throw e;
            }

            zooKeeper.create(metadataPath, metadata.toJson().getBytes(),
                    ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

            metadataCache.remove(filename);
            cacheTimestamps.remove(filename);
            return true;
        });
    }
    
    /**
     * Download a file from the distributed storage
     */
    public byte[] downloadFile(String filename) throws Exception {
        return withSessionRecovery("download", () -> {
            logger.info("Downloading file: {}", filename);
            Set<String> aliveServers = availabilityPolicy.requireAliveServers(getAliveServers(), "download");

            // Get file metadata (from cache or ZooKeeper)
            FileMetadata metadata = getFileMetadata(filename);
            if (metadata == null) {
                logger.warn("File not found: {}", filename);
                return null;
            }

            if (metadata.isDeleted()) {
                logger.warn("File has been deleted: {}", filename);
                return null;
            }

            // Validate locations against actual file presence to avoid stale/fake replicas.
            metadata = locationResolver.withAvailableLocations(metadata, aliveServers);

            // Try each location until we find a working server
            for (String serverId : metadata.getLocations()) {
                try {
                    byte[] content = readReplica(serverId, metadata.getFileId());
                    if (content != null) {
                        logger.info("Downloaded {} from server {} ({} bytes)",
                                filename, serverId, content.length);
                        return content;
                    }
                } catch (Exception e) {
                    logger.warn("Failed to download from server {}: {}", serverId, e.getMessage());
                    // Continue to next replica
                }
            }

            logger.error("Could not download file {} from any replica", filename);
            return null;
        });
    }
    
    /**
     * Delete a file from the distributed storage
     */
    public boolean deleteFile(String filename) throws Exception {
        return withSessionRecovery("delete", () -> {
            logger.info("Deleting file: {}", filename);

            if (currentLeader == null) {
                refreshLeader();
            }

            if (currentLeader == null) {
                throw new Exception("No leader available for delete");
            }

            MetadataNode node = findLatestMetadataNodeByFilename(filename);
            if (node == null) {
                return false;
            }

            for (String location : node.metadata.getLocations()) {
                deleteReplica(location, node.metadata.getFileId());
            }

            zooKeeper.delete(FILES_PATH + "/" + node.nodeId, node.stat.getVersion());
            metadataCache.remove(filename);
            cacheTimestamps.remove(filename);
            return true;
        });
    }
    
    /**
     * List all files in the storage system
     */
    public List<FileInfo> listFiles() throws Exception {
        return withSessionRecovery("listFiles", () -> {
            List<FileInfo> files = new ArrayList<>();
            Set<String> aliveServers = availabilityPolicy.requireAliveServers(getAliveServers(), "list");

            List<String> children = zooKeeper.getChildren(FILES_PATH, false);

            for (String nodeId : children) {
                String metadataPath = FILES_PATH + "/" + nodeId;
                Stat stat = zooKeeper.exists(metadataPath, false);

                if (stat != null) {
                    byte[] data = zooKeeper.getData(metadataPath, false, stat);
                    FileMetadata metadata = FileMetadata.fromJson(new String(data));

                    if (!metadata.isDeleted()) {
                        files.add(new FileInfo(locationResolver.withAvailableLocations(metadata, aliveServers)));
                    }
                }
            }

            return files;
        });
    }
    
    /**
     * Get metadata for a specific file
     */
    public FileMetadata getFileMetadata(String filename) throws Exception {
        return withSessionRecovery("getFileMetadata", () -> {
            // Check cache first
            Long timestamp = cacheTimestamps.get(filename);
            if (timestamp != null && (System.currentTimeMillis() - timestamp) < CACHE_TTL_MS) {
                FileMetadata cached = metadataCache.get(filename);
                if (cached != null) {
                    logger.debug("Cache hit for file: {}", filename);
                    return cached;
                }
            }

            MetadataNode node = findLatestMetadataNodeByFilename(filename);
            if (node == null) {
                return null;
            }
            FileMetadata metadata = locationResolver.withExistingLocations(node.metadata);

            // Update cache
            metadataCache.put(filename, metadata);
            cacheTimestamps.put(filename, System.currentTimeMillis());

            return metadata;
        });
    }
    
    /**
     * Get system statistics
     */
    public Map<String, Object> getSystemStats() throws Exception {
        return withSessionRecovery("getSystemStats", () -> {
            Map<String, Object> stats = new HashMap<>();

            List<FileInfo> files = listFiles();
            long totalSize = 0;
            for (FileInfo file : files) {
                totalSize += file.getSize();
            }

            List<String> servers = zooKeeper.getChildren(STORAGE_SERVERS_PATH, false);

            stats.put("totalFiles", files.size());
            stats.put("totalSize", totalSize);
            stats.put("activeServers", servers.size());
            stats.put("leader", getCurrentLeader());

            return stats;
        });
    }
    
    /**
     * Get list of alive servers
     */
    public List<String> getAliveServers() throws Exception {
        return withSessionRecovery("getAliveServers", () -> zooKeeper.getChildren(STORAGE_SERVERS_PATH, false));
    }
    
    /**
     * Get current leader
     */
    public String getCurrentLeader() {
        try {
            withSessionRecovery("getCurrentLeader", () -> {
                refreshLeader();
                return null;
            });
        } catch (Exception e) {
            logger.warn("Failed to refresh leader", e);
        }
        return currentLeader;
    }
    
    /**
     * Get ZooKeeper address
     */
    public String getZookeeperAddress() {
        return zookeeperAddress;
    }
    
    /**
     * Close the client and release resources
     */
    public void close() throws Exception {
        closing = true;
        ZooKeeper zk = zooKeeper;
        if (zk != null) {
            zk.close();
        }
        logger.info("StorageClient closed");
    }
    
    private Path getServerStoragePath(String serverId) {
        return STORAGE_ROOT.resolve(serverId);
    }

    private void writeReplica(String serverId, String fileId, byte[] content) throws IOException {
        Path replicaPath = getServerStoragePath(serverId).resolve(fileId);
        Files.createDirectories(replicaPath.getParent());
        Files.write(replicaPath, content);
    }

    private byte[] readReplica(String serverId, String fileId) throws IOException {
        Path replicaPath = getServerStoragePath(serverId).resolve(fileId);
        if (!Files.exists(replicaPath)) {
            return null;
        }
        return Files.readAllBytes(replicaPath);
    }

    private void deleteReplica(String serverId, String fileId) {
        Path replicaPath = getServerStoragePath(serverId).resolve(fileId);
        try {
            Files.deleteIfExists(replicaPath);
        } catch (IOException e) {
            logger.warn("Failed deleting replica {} on {}", fileId, serverId, e);
        }
    }

    private void rollbackReplicas(List<String> locations, String fileId) {
        for (String location : locations) {
            deleteReplica(location, fileId);
        }
    }

    private MetadataNode findLatestMetadataNodeByFilename(String filename) throws Exception {
        List<String> children = zooKeeper.getChildren(FILES_PATH, false);
        MetadataNode latest = null;

        for (String nodeId : children) {
            String metadataPath = FILES_PATH + "/" + nodeId;
            Stat stat = zooKeeper.exists(metadataPath, false);
            if (stat == null) {
                continue;
            }

            byte[] data = zooKeeper.getData(metadataPath, false, stat);
            FileMetadata metadata = FileMetadata.fromJson(new String(data));
            if (metadata.isDeleted() || !filename.equals(metadata.getFilename())) {
                continue;
            }

            if (latest == null || isNewer(metadata, latest.metadata)) {
                latest = new MetadataNode(nodeId, stat, metadata);
            }
        }
        return latest;
    }

    private boolean isNewer(FileMetadata candidate, FileMetadata current) {
        Instant candidateTime = candidate.getModifiedAt() != null ? candidate.getModifiedAt() : candidate.getCreatedAt();
        Instant currentTime = current.getModifiedAt() != null ? current.getModifiedAt() : current.getCreatedAt();
        if (candidateTime == null) {
            return false;
        }
        if (currentTime == null) {
            return true;
        }
        return candidateTime.isAfter(currentTime);
    }

    private static class MetadataNode {
        private final String nodeId;
        private final Stat stat;
        private final FileMetadata metadata;

        private MetadataNode(String nodeId, Stat stat, FileMetadata metadata) {
            this.nodeId = nodeId;
            this.stat = stat;
            this.metadata = metadata;
        }
    }
    
    // Inner classes
    
    /**
     * File metadata class
     */
    public static class FileMetadata {
        private String fileId;
        private String filename;
        private long size;
        private Instant createdAt;
        private Instant modifiedAt;
        private String owner;
        private int version;
        private List<String> locations;
        private boolean deleted;
        
        public FileMetadata() {
            this.locations = new ArrayList<>();
            this.version = 1;
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
        
        public void addLocation(String serverId) {
            if (!locations.contains(serverId)) {
                locations.add(serverId);
            }
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
    }
    
    /**
     * File info class (lightweight version for listing)
     */
    public static class FileInfo {
        private final String filename;
        private final long size;
        private final Instant modifiedAt;
        
        public FileInfo(FileMetadata metadata) {
            this.filename = metadata.getFilename();
            this.size = metadata.getSize();
            this.modifiedAt = metadata.getModifiedAt();
        }
        
        public String getFilename() { return filename; }
        public long getSize() { return size; }
        public Instant getModifiedAt() { return modifiedAt; }
    }
}
