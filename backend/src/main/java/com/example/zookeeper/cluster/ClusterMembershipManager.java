package com.example.zookeeper.cluster;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.imps.CuratorFrameworkState;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Watcher;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Cluster membership manager backed by ZooKeeper.
 *
 * Responsibilities:
 * - Register this backend as an ephemeral node under /fms/nodes
 * - Keep in-memory view of active nodes
 * - Watch membership changes and refresh automatically
 * - Read current leader from /fms/leader
 * - Mark node state updates (ACTIVE, UNHEALTHY, DRAINING, etc.)
 */
public class ClusterMembershipManager {

    private static final Logger logger = Logger.getLogger(ClusterMembershipManager.class.getName());

    private static final String BASE_PATH = "/fms";
    private static final String NODES_PATH = BASE_PATH + "/nodes";
    private static final String LEADER_PATH = BASE_PATH + "/leader";

    private final String connectString;
    private final int sessionTimeoutMs;

    private CuratorFramework client;
    private final Map<String, NodeInfo> nodes = new ConcurrentHashMap<>();

    public ClusterMembershipManager(String connectString, int sessionTimeoutMs) {
        this.connectString = connectString;
        this.sessionTimeoutMs = sessionTimeoutMs;
    }

    public synchronized void connect() throws Exception {
        if (client != null && client.getState() == CuratorFrameworkState.STARTED) {
            return;
        }

        client = CuratorFrameworkFactory.builder()
                .connectString(connectString)
                .sessionTimeoutMs(sessionTimeoutMs)
                .connectionTimeoutMs(5000)
                .retryPolicy(new ExponentialBackoffRetry(1000, 5))
                .build();

        client.start();
        if (!client.blockUntilConnected(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Could not connect to ZooKeeper");
        }

        ensureBasePaths();
        refreshNodes();
        watchMembership();
    }

    public void close() {
        if (client != null) {
            client.close();
        }
        nodes.clear();
    }

    public String registerNode(String nodeId, String host, int port, String state) {
        ensureStarted();
        String path = NODES_PATH + "/node-" + nodeId;
        NodeInfo info = new NodeInfo(nodeId, host, port, state, Instant.now().toEpochMilli());

        try {
            byte[] payload = info.toPayload().getBytes(StandardCharsets.UTF_8);
            if (client.checkExists().forPath(path) == null) {
                client.create().withMode(CreateMode.EPHEMERAL).forPath(path, payload);
            } else {
                client.setData().forPath(path, payload);
            }

            nodes.put(nodeId, info);
            return path;
        } catch (Exception ex) {
            throw new RuntimeException("Failed to register node", ex);
        }
    }

    public void markNodeState(String nodeId, String newState) {
        ensureStarted();
        String path = NODES_PATH + "/node-" + nodeId;

        try {
            if (client.checkExists().forPath(path) == null) {
                throw new IllegalStateException("Node does not exist: " + nodeId);
            }

            byte[] data = client.getData().forPath(path);
            NodeInfo existing = NodeInfo.fromPayload(nodeId, new String(data, StandardCharsets.UTF_8));
            NodeInfo updated = new NodeInfo(existing.nodeId, existing.host, existing.port, newState,
                    Instant.now().toEpochMilli());

            client.setData().forPath(path, updated.toPayload().getBytes(StandardCharsets.UTF_8));
            nodes.put(nodeId, updated);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to update node state", ex);
        }
    }

    public List<NodeInfo> getNodes() {
        return Collections.unmodifiableList(new ArrayList<>(nodes.values()));
    }

    public String getLeader() {
        ensureStarted();
        try {
            if (client.checkExists().forPath(LEADER_PATH) == null) {
                return "";
            }
            byte[] data = client.getData().forPath(LEADER_PATH);
            return new String(data, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to read leader", ex);
        }
    }

    private void ensureBasePaths() {
        for (String path : List.of(BASE_PATH, NODES_PATH, LEADER_PATH)) {
            try {
                if (client.checkExists().forPath(path) == null) {
                    client.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT).forPath(path);
                }
            } catch (KeeperException.NodeExistsException ignored) {
            } catch (Exception ex) {
                logger.log(Level.WARNING, "Failed ensuring path " + path, ex);
            }
        }
    }

    private void watchMembership() {
        if (client == null || client.getState() != CuratorFrameworkState.STARTED) {
            return;
        }

        try {
            client.getChildren().usingWatcher((Watcher) event -> {
                if (event.getType() == Watcher.Event.EventType.NodeChildrenChanged) {
                    if (client != null && client.getState() == CuratorFrameworkState.STARTED) {
                        refreshNodes();
                        watchMembership();
                    }
                }
            }).forPath(NODES_PATH);
        } catch (Exception ex) {
            logger.log(Level.WARNING, "Failed to watch membership", ex);
        }
    }

    private void refreshNodes() {
        if (client == null || client.getState() != CuratorFrameworkState.STARTED) {
            return;
        }
        try {
            List<String> current = client.getChildren().forPath(NODES_PATH);
            Map<String, NodeInfo> fresh = new ConcurrentHashMap<>();

            for (String child : current) {
                String path = NODES_PATH + "/" + child;
                byte[] data = client.getData().forPath(path);
                String nodeId = child.startsWith("node-") ? child.substring("node-".length()) : child;
                NodeInfo info = NodeInfo.fromPayload(nodeId, new String(data, StandardCharsets.UTF_8));
                fresh.put(nodeId, info);
            }

            nodes.clear();
            nodes.putAll(fresh);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to refresh nodes", ex);
        }
    }

    private void ensureStarted() {
        if (client == null || client.getState() != CuratorFrameworkState.STARTED) {
            throw new IllegalStateException("Cluster manager is not connected");
        }
    }

    public static final class NodeInfo {
        public final String nodeId;
        public final String host;
        public final int port;
        public final String state;
        public final long updatedAtEpochMs;

        public NodeInfo(String nodeId, String host, int port, String state, long updatedAtEpochMs) {
            this.nodeId = nodeId;
            this.host = host;
            this.port = port;
            this.state = state;
            this.updatedAtEpochMs = updatedAtEpochMs;
        }

        public String toPayload() {
            return "host=" + host + ";port=" + port + ";state=" + state + ";updatedAt=" + updatedAtEpochMs;
        }

        public static NodeInfo fromPayload(String nodeId, String payload) {
            String host = "localhost";
            int port = 0;
            String state = "UNKNOWN";
            long updatedAt = 0L;

            String[] parts = payload.split(";");
            for (String part : parts) {
                String[] kv = part.split("=", 2);
                if (kv.length != 2) {
                    continue;
                }
                if ("host".equals(kv[0])) {
                    host = kv[1];
                } else if ("port".equals(kv[0])) {
                    try {
                        port = Integer.parseInt(kv[1]);
                    } catch (NumberFormatException ignored) {
                    }
                } else if ("state".equals(kv[0])) {
                    state = kv[1];
                } else if ("updatedAt".equals(kv[0])) {
                    try {
                        updatedAt = Long.parseLong(kv[1]);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }

            return new NodeInfo(nodeId, host, port, state, updatedAt);
        }
    }
}
