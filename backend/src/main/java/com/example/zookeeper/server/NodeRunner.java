package com.example.zookeeper.server;

import com.example.zookeeper.config.ServerConfig;
import com.example.zookeeper.election.LeaderElection;
import com.example.zookeeper.zookeeper.ZookeeperConnection;
import com.example.zookeeper.config.EnsembleConfig;

import java.io.File;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;

/**
 * Utility to run multiple storage nodes in a single terminal for testing.
 * Useful for quick testing without opening multiple terminals.
 */
public class NodeRunner {
    
    private final List<StorageNodeThread> nodes = new ArrayList<>();
    private final EnsembleConfig ensembleConfig;
    private final boolean autoStartEmbeddedZooKeeper;
    private EmbeddedZooKeeper embeddedZooKeeper;
    
    public NodeRunner(EnsembleConfig ensembleConfig, boolean autoStartEmbeddedZooKeeper) {
        this.ensembleConfig = ensembleConfig;
        this.autoStartEmbeddedZooKeeper = autoStartEmbeddedZooKeeper;
    }
    
    /**
     * Start multiple storage nodes.
     */
    public void startNodes(int count) {
        if (autoStartEmbeddedZooKeeper) {
            try {
                embeddedZooKeeper = new EmbeddedZooKeeper("./zk-data", 2181);
                embeddedZooKeeper.start();
                System.out.println("[NodeRunner] Embedded ZooKeeper started at localhost:2181\n");
            } catch (Exception e) {
                System.out.println("[NodeRunner] Could not start embedded ZooKeeper: " + e.getMessage());
                System.out.println("[NodeRunner] Continuing with existing ZooKeeper at localhost:2181\n");
            }
        }

        System.out.println("Starting " + count + " storage nodes...");
        System.out.println("ZooKeeper ensemble: " + ensembleConfig.getConnectionString());
        System.out.println("Ensemble can tolerate " + ensembleConfig.getTolerableFailures() + " failures\n");
        
        for (int i = 1; i <= count; i++) {
            String serverId = "server-" + i;
            ServerConfig config = new ServerConfig(serverId, ensembleConfig.getConnectionString(), 8080 + i);
            
            StorageNodeThread nodeThread = new StorageNodeThread(config);
            nodes.add(nodeThread);
            nodeThread.start();
            
            // Small delay between startups to make election easier to observe
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        System.out.println("\nAll " + count + " nodes started!");
        System.out.println("Type 'status' to see all nodes, 'kill <server-id>' to kill a node,");
        System.out.println("'restore <server-id>' to restore a killed node, 'quit' to exit.\n");
        
        runCommands();
    }
    
    /**
     * Run interactive commands.
     */
    private void runCommands() {
        Scanner scanner = new Scanner(System.in);
        
        while (true) {
            System.out.print("runner> ");
            String command = scanner.nextLine().trim();
            
            if (command.equals("quit")) {
                stopAllNodes();
                break;
            } else if (command.equals("status")) {
                printStatus();
            } else if (command.startsWith("kill ")) {
                String serverId = command.substring(5);
                killNode(serverId);
            } else if (command.startsWith("restore ")) {
                String serverId = command.substring(8);
                restoreNode(serverId);
            } else {
                System.out.println("Commands: status, kill <id>, restore <id>, quit");
            }
        }
        
        scanner.close();
    }
    
    /**
     * Print status of all nodes.
     */
    private void printStatus() {
        System.out.println("\n--- Node Status ---");
        for (StorageNodeThread node : nodes) {
            String status = node.isRunning() ? "RUNNING" : "KILLED";
            String role = node.getRole();
            System.out.println("  " + node.getServerId() + ": " + status + " | Role: " + role);
        }
        System.out.println("------------------\n");
    }
    
    /**
     * Kill a node (simulate failure).
     */
    private void killNode(String serverId) {
        for (StorageNodeThread node : nodes) {
            if (node.getServerId().equals(serverId)) {
                if (node.isRunning()) {
                    node.kill();
                    System.out.println("Killed " + serverId);
                } else {
                    System.out.println(serverId + " is already dead");
                }
                return;
            }
        }
        System.out.println("Node " + serverId + " not found");
    }
    
    /**
     * Restore a killed node (simulate recovery).
     */
    private void restoreNode(String serverId) {
        for (StorageNodeThread node : nodes) {
            if (node.getServerId().equals(serverId)) {
                if (!node.isRunning()) {
                    node.restart();
                    System.out.println("Restored " + serverId);
                } else {
                    System.out.println(serverId + " is already running");
                }
                return;
            }
        }
        System.out.println("Node " + serverId + " not found");
    }
    
    /**
     * Stop all nodes.
     */
    private void stopAllNodes() {
        System.out.println("Stopping all nodes...");
        for (StorageNodeThread node : nodes) {
            node.stopNode();
        }
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (embeddedZooKeeper != null) {
            try {
                embeddedZooKeeper.stop();
                System.out.println("Embedded ZooKeeper stopped");
            } catch (Exception e) {
                System.err.println("Error stopping embedded ZooKeeper: " + e.getMessage());
            }
        }

        System.out.println("All nodes stopped");
    }

    private static class EmbeddedZooKeeper {
        private final String dataDirPath;
        private final int clientPort;
        private ZooKeeperServer zooKeeperServer;
        private ServerCnxnFactory connectionFactory;

        EmbeddedZooKeeper(String dataDirPath, int clientPort) {
            this.dataDirPath = dataDirPath;
            this.clientPort = clientPort;
        }

        void start() throws Exception {
            File dataDir = new File(dataDirPath);
            File snapDir = new File(dataDir, "snapshot");
            File logDir = new File(dataDir, "log");

            if (!snapDir.exists()) {
                snapDir.mkdirs();
            }
            if (!logDir.exists()) {
                logDir.mkdirs();
            }

            zooKeeperServer = new ZooKeeperServer();
            zooKeeperServer.setTxnLogFactory(new FileTxnSnapLog(logDir, snapDir));
            zooKeeperServer.setTickTime(2000);

            connectionFactory = ServerCnxnFactory.createFactory(new InetSocketAddress("localhost", clientPort), 100);
            connectionFactory.startup(zooKeeperServer);
        }

        void stop() {
            if (connectionFactory != null) {
                connectionFactory.shutdown();
            }
            if (zooKeeperServer != null) {
                zooKeeperServer.shutdown();
            }
        }
    }
    
    /**
     * Thread wrapper for StorageNode to run in background.
     */
    private static class StorageNodeThread extends Thread {
        private final ServerConfig config;
        private StorageNode node;
        private volatile boolean running = false;
        private volatile boolean killed = false;
        private String role = "unknown";
        
        public StorageNodeThread(ServerConfig config) {
            this.config = config;
        }
        
        @Override
        public void run() {
            node = new StorageNode(config);
            running = true;
            
            try {
                // Override the normal console to run without interactive input
                runSilent();
            } catch (Exception e) {
                if (!killed) {
                    System.err.println("Error in " + getServerId() + ": " + e.getMessage());
                }
            }
        }
        
        private void runSilent() throws Exception {
            // Connect to ZooKeeper
            ZookeeperConnection zkConnection = 
                new ZookeeperConnection();
            ZooKeeper zooKeeper = 
                zkConnection.connect(config.getZookeeperAddress());
            
            // Initialize leader election
            LeaderElection leaderElection = 
                new LeaderElection(zooKeeper, getServerId());
            leaderElection.initialize();
            
            // Monitor role
            while (running && !killed) {
                role = leaderElection.isLeader() ? "LEADER" : "FOLLOWER";
                Thread.sleep(2000);
            }
            
            leaderElection.shutdown();
            zkConnection.close();
        }
        
        public String getServerId() {
            return config.getServerId();
        }
        
        public boolean isRunning() {
            return running && !killed;
        }
        
        public String getRole() {
            return role;
        }
        
        public void kill() {
            killed = true;
            running = false;
            if (node != null) {
                // Force shutdown
                this.interrupt();
            }
        }
        
        public void restart() {
            if (!running || killed) {
                killed = false;
                running = false;
                this.start();  // Start new thread
            }
        }
        
        public void stopNode() {
            running = false;
            killed = true;
        }
    }
    
    /**
     * Main method - entry point for running multiple nodes.
     */
    public static void main(String[] args) {
        System.out.println("=== Distributed File System Node Runner ===\n");
        
        // Choose ensemble size
        System.out.println("Select ZooKeeper ensemble:");
        System.out.println("1. Single node (localhost:2181) - for development");
        System.out.println("2. 3-node local ensemble (ports 2181-2183)");
        System.out.println("3. 5-node local ensemble (ports 2181-2185)");
        System.out.print("Choice (1-3): ");
        
        Scanner scanner = new Scanner(System.in);
        int choice = scanner.nextInt();
        boolean autoStartEmbedded = choice == 1;
        
        EnsembleConfig config;
        switch (choice) {
            case 2:
                config = EnsembleConfig.localThreeNode();
                break;
            case 3:
                config = EnsembleConfig.localFiveNode();
                break;
            default:
                config = EnsembleConfig.singleNode();
        }
        
        System.out.print("How many storage nodes to start? (2-5): ");
        int nodeCount = scanner.nextInt();
        nodeCount = Math.min(nodeCount, 5);
        
        NodeRunner runner = new NodeRunner(config, autoStartEmbedded);
        runner.startNodes(nodeCount);
        
        scanner.close();
    }
}
