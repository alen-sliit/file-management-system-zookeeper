package com.example.zookeeper.zookeeper;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ZooKeeperConsensusManager {

    private static final Logger logger = Logger.getLogger(ZooKeeperConsensusManager.class.getName());

    private static final String BASE_PATH = "/fms";
    private static final String MEMBERS_PATH = BASE_PATH + "/members";
    private static final String LEADER_PATH = BASE_PATH + "/leader";
    private static final String FILES_PATH = BASE_PATH + "/files";
    private static final String LOCKS_PATH = BASE_PATH + "/locks";

    private final String connectString;
    private final int sessionTimeoutMs;
    private CuratorFramework client;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean leader = new AtomicBoolean(false);
    private final Map<String, String> members = new ConcurrentHashMap<>();

    public ZooKeeperConsensusManager(String connectString, int sessionTimeoutMs) {
        this.connectString = connectString;
        this.sessionTimeoutMs = sessionTimeoutMs;
    }

    public synchronized void connect() throws Exception {
        if (client != null && client.getZookeeperClient().isConnected())
            return;

        client = CuratorFrameworkFactory.builder()
                .connectString(connectString)
                .sessionTimeoutMs(sessionTimeoutMs)
                .connectionTimeoutMs(3000)
                .retryPolicy(new ExponentialBackoffRetry(1000, 5))
                .build();

        client.start();
        client.blockUntilConnected(10, TimeUnit.SECONDS);

        ensureBasePaths();
    }

    private void ensureBasePaths() {
        try {
            for (String p : List.of(BASE_PATH, MEMBERS_PATH, LEADER_PATH, FILES_PATH, LOCKS_PATH)) {
                if (client.checkExists().forPath(p) == null) {
                    client.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT).forPath(p);
                    logger.info("Created path " + p);
                }
            }
        } catch (KeeperException.NodeExistsException ignore) {
        } catch (Exception ex) {
            logger.log(Level.WARNING, null, ex);
        }
    }

    public boolean isLeader() {
        return leader.get();
    }

    public void close() {
        if (client != null)
            client.close();
    }

    public String registerMember(String nodeId, String metadata) {
        try {
            String path = MEMBERS_PATH + "/" + nodeId;
            if (client.checkExists().forPath(path) == null)
                client.create().withMode(CreateMode.EPHEMERAL).forPath(path, metadata.getBytes(StandardCharsets.UTF_8));
            else
                client.setData().forPath(path, metadata.getBytes(StandardCharsets.UTF_8));
            return path;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public void proposeLeader(String nodeId) {
        try {
            if (client.checkExists().forPath(LEADER_PATH) != null)
                client.setData().forPath(LEADER_PATH, nodeId.getBytes(StandardCharsets.UTF_8));
            else
                client.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT)
                        .forPath(LEADER_PATH, nodeId.getBytes(StandardCharsets.UTF_8));
            leader.set(true);
        } catch (Exception ex) {
            leader.set(false);
        }
    }

    public void createFile(String fileId, String name, long size, String content) {
        requireLeader();
        String filePath = FILES_PATH + "/" + fileId;
        try {
            if (client.checkExists().forPath(filePath) != null) {
                updateFile(fileId, name, size, content);
                return;
            }
            client.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT).forPath(filePath);
            String metadata = buildMetadata(name, size, 0);
            client.inTransaction()
                    .create().forPath(filePath + "/meta", metadata.getBytes(StandardCharsets.UTF_8))
                    .and()
                    .create().forPath(filePath + "/content", content.getBytes(StandardCharsets.UTF_8))
                    .and()
                    .create().forPath(filePath + "/deleted", "false".getBytes(StandardCharsets.UTF_8))
                    .and().commit();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public void updateFile(String fileId, String name, long size, String content) {
        requireLeader();
        String filePath = FILES_PATH + "/" + fileId;
        InterProcessMutex lock = new InterProcessMutex(client, LOCKS_PATH + "/" + fileId);
        try {
            if (!lock.acquire(5, TimeUnit.SECONDS))
                throw new IllegalStateException("Could not acquire lock");
            String metadata = buildMetadata(name, size, Instant.now().toEpochMilli());
            client.inTransaction()
                    .setData().forPath(filePath + "/meta", metadata.getBytes(StandardCharsets.UTF_8))
                    .and()
                    .setData().forPath(filePath + "/content", content.getBytes(StandardCharsets.UTF_8))
                    .and().commit();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        } finally {
            try {
                lock.release();
            } catch (Exception ignored) {
            }
        }
    }

    public void deleteFile(String fileId) {
        requireLeader();
        String filePath = FILES_PATH + "/" + fileId;
        try {
            client.setData().forPath(filePath + "/deleted", "true".getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public String readFile(String fileId) {
        String filePath = FILES_PATH + "/" + fileId;
        try {
            String deleted = new String(client.getData().forPath(filePath + "/deleted"), StandardCharsets.UTF_8);
            if (Boolean.parseBoolean(deleted))
                throw new IllegalStateException("File is deleted");
            return new String(client.getData().forPath(filePath + "/content"), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public void ping() {
        try {
            client.checkExists().forPath(BASE_PATH);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private void requireLeader() {
        if (!isLeader())
            throw new IllegalStateException("Only leader can execute writes");
    }

    private String buildMetadata(String name, long size, long timestamp) {
        return "name=" + name + ",size=" + size + ",timestamp=" + timestamp;
    }
}