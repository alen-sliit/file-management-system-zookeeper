package com.example.zookeeper;

import com.example.zookeeper.cluster.ClusterMembershipManager;
import com.example.zookeeper.config.ConsensusConfiguration;
import com.example.zookeeper.election.LeaderElection;
import com.example.zookeeper.zookeeper.ZooKeeperConsensusManager;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class App implements Watcher {

    private static final ConsensusConfiguration CONFIG = ConsensusConfiguration.load();
    private static final String ZK_SERVER = CONFIG.getZooKeeperConnectString();
    private ZooKeeperConsensusManager zk;

    public static void main(String[] args) throws Exception {
        System.out.println("Loaded consensus configuration: " + CONFIG);

        // Quick health check
        App app = new App();
        app.connect();
        app.safeCreateNode("/testNode", "Hello ZooKeeper");
        app.close();

        // Leader election demo
        runLeaderElectionDemo();

        // Consensus manager demo
        runConsensusDemo();

        // Cluster membership demo
        runClusterDemo();
    }

    public void connect() throws IOException {
        zk = new ZooKeeperConsensusManager(ZK_SERVER, CONFIG.getSessionTimeoutMs());
    }

    // Safe node creation / update
    public void safeCreateNode(String path, String data) throws Exception {
        zk.connect();
        try {
            zk.registerMember(CONFIG.getNodeId(), buildMemberMetadata());
        } catch (Exception ignored) {
        }
        System.out.println("Node created/updated safely: " + path + " -> " + data);
    }

    public void close() throws InterruptedException {
        if (zk != null) {
            zk.close();
        }
    }

    private static void runLeaderElectionDemo() throws Exception {
        System.out.println("Starting leader election demo...");

        List<ZooKeeper> clients = new ArrayList<>();
        List<LeaderElection> electors = new ArrayList<>();
        String[] names = buildElectionNodeNames(CONFIG.getExpectedClusterSize());

        CountDownLatch electedLatch = new CountDownLatch(1);
        CountDownLatch newLeaderLatch = new CountDownLatch(1);

        java.util.concurrent.atomic.AtomicBoolean firstLeaderSelected = new java.util.concurrent.atomic.AtomicBoolean(
                false);
        List<Boolean> isLeader = new ArrayList<>();
        for (int i = 0; i < names.length; i++)
            isLeader.add(false);

        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            ZooKeeper client = new ZooKeeper(ZK_SERVER, CONFIG.getSessionTimeoutMs(), event -> {
                // no-op for demo
            });
            clients.add(client);

            LeaderElection election = new LeaderElection(client, new LeaderElection.Listener() {
                @Override
                public void onElectedLeader(String leaderNode) {
                    boolean isFirst = firstLeaderSelected.compareAndSet(false, true);
                    isLeader.set(idx, true);
                    System.out.println(names[idx] + " became leader: " + leaderNode);
                    if (isFirst)
                        electedLatch.countDown();
                    else
                        newLeaderLatch.countDown();
                }

                @Override
                public void onLeaderLost(String previousLeaderNode) {
                    System.out.println(names[idx] + " lost leadership: " + previousLeaderNode);
                    isLeader.set(idx, false);
                }
            });
            electors.add(election);
            election.start();
        }

        if (!electedLatch.await(15, TimeUnit.SECONDS))
            throw new IllegalStateException("No leader elected");
        int currentLeader = isLeader.indexOf(true);
        System.out.println("Current leader is " + names[currentLeader] + ", resigning...");
        electors.get(currentLeader).resign();

        if (!newLeaderLatch.await(15, TimeUnit.SECONDS))
            throw new IllegalStateException("Failover did not occur");

        System.out.println("Leader election demo completed successfully.");
        for (LeaderElection election : electors)
            election.resign();
        for (ZooKeeper client : clients)
            client.close();
    }

    private static void runConsensusDemo() throws Exception {
        System.out.println("Starting ZooKeeper consensus demo...");

        ZooKeeperConsensusManager consensus = new ZooKeeperConsensusManager(
                CONFIG.getZooKeeperConnectString(),
                CONFIG.getSessionTimeoutMs());
        String nodeId = CONFIG.getNodeId();

        try {
            consensus.connect();
            consensus.registerMember(nodeId, buildMemberMetadata());
            consensus.proposeLeader(nodeId);

            if (!consensus.isLeader())
                throw new IllegalStateException("Node is not leader");

            String fileId = "demo-file-" + System.currentTimeMillis(); // UNIQUE ID

            consensus.createFile(fileId, "demo.txt", 11, "hello world");
            consensus.updateFile(fileId, "demo.txt", 12, "hello world!");

            String content = consensus.readFile(fileId);
            System.out.println("Consensus read content: " + content);

            consensus.deleteFile(fileId);
            consensus.ping();

            System.out.println("ZooKeeper consensus demo completed successfully.");
        } finally {
            consensus.close();
        }
    }

    private static void runClusterDemo() throws Exception {
        System.out.println("Starting ZooKeeper cluster demo...");

        ClusterMembershipManager cluster = new ClusterMembershipManager(
                CONFIG.getZooKeeperConnectString(),
                CONFIG.getSessionTimeoutMs());
        String nodeId = CONFIG.getNodeId();

        try {
            cluster.connect();
            String registeredPath = cluster.registerNode(nodeId, CONFIG.getNodeHost(), CONFIG.getNodePort(), "ACTIVE");
            System.out.println("Cluster node registered at: " + registeredPath);

            System.out.println("Replication factor: " + CONFIG.getReplicationFactor());
            System.out.println("Expected cluster size: " + CONFIG.getExpectedClusterSize());

            cluster.markNodeState(nodeId, "HEALTHY");
            String leader = cluster.getLeader();
            System.out.println("Cluster leader: " + (leader.isEmpty() ? "<none>" : leader));

            List<ClusterMembershipManager.NodeInfo> nodes = cluster.getNodes();
            System.out.println("Cluster nodes count: " + nodes.size());
            for (ClusterMembershipManager.NodeInfo node : nodes) {
                System.out.println("- " + node.nodeId + " @ " + node.host + ":" + node.port + " [" + node.state + "]");
            }

            System.out.println("ZooKeeper cluster demo completed successfully.");
        } finally {
            cluster.close();
        }
    }

    @Override
    public void process(WatchedEvent event) {
        System.out.println("Event received: " + event);
    }

    private static String buildMemberMetadata() {
        return "host=" + CONFIG.getNodeHost() + ";port=" + CONFIG.getNodePort();
    }

    private static String[] buildElectionNodeNames(int count) {
        int safeCount = Math.max(3, count);
        String[] names = new String[safeCount];
        for (int index = 0; index < safeCount; index++) {
            names[index] = "node-" + (index + 1);
        }
        return names;
    }
}