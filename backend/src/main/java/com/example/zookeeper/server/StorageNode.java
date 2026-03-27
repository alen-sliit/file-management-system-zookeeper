package com.example.zookeeper.server;


import com.example.zookeeper.config.ServerConfig;
import com.example.zookeeper.election.ConsensusMonitor;
import com.example.zookeeper.election.LeaderElection;
import com.example.zookeeper.zookeeper.ZookeeperConnection;

import org.apache.zookeeper.ZooKeeper;

import java.util.Scanner;

/**
 * Main class for each storage node in the distributed file system.
 * Each node runs as a separate process and participates in leader election.
 */
public class StorageNode {
    
    private final ServerConfig config;
    private ZookeeperConnection zkConnection;
    private ZooKeeper zooKeeper;
    private LeaderElection leaderElection;
    private ConsensusMonitor monitor;
    private boolean running = true;
    
    public StorageNode(ServerConfig config) {
        this.config = config;
    }
    
    /**
     * Start the storage node.
     */
    public void start() throws Exception {
        System.out.println("\n========================================");
        System.out.println("Starting Storage Node: " + config.getServerId());
        System.out.println("ZooKeeper: " + config.getZookeeperAddress());
        System.out.println("Storage Path: " + config.getStoragePath());
        System.out.println("========================================\n");
        
        // Create storage directory if it doesn't exist
        createStorageDirectory();
        
        // Connect to ZooKeeper
        zkConnection = new ZookeeperConnection();
        zooKeeper = zkConnection.connect(config.getZookeeperAddress());
        
        // Initialize leader election
        leaderElection = new LeaderElection(zooKeeper, config.getServerId());
        leaderElection.initialize();

        // Initialize consensus monitor
        monitor = new ConsensusMonitor(zooKeeper, config.getServerId());
        
        // Print initial status
        printStatus();
        
        // Start interactive console
        runConsole();
    }
    
    /**
     * Create local storage directory.
     */
    private void createStorageDirectory() {
        java.io.File dir = new java.io.File(config.getStoragePath());
        if (!dir.exists()) {
            dir.mkdirs();
            System.out.println("Created storage directory: " + config.getStoragePath());
        }
    }
    
    /**
     * Print current server status.
     */
    private void printStatus() {
        try {
            System.out.println("\n--- Current Status ---");
            System.out.println("Server ID: " + config.getServerId());
            System.out.println("Role: " + (leaderElection.isLeader() ? "LEADER" : "FOLLOWER"));
            System.out.println("Active Servers: " + leaderElection.getActiveServers());
            System.out.println("Current Leader: " + leaderElection.getCurrentLeader());
            System.out.println("----------------------\n");
        } catch (Exception e) {
            System.err.println("Error getting status: " + e.getMessage());
        }
    }

    public void printHealthReport() {
        if (monitor != null) {
            System.out.println(monitor.getHealthReport());
        }
    }
    
    /**
     * Run interactive console for testing.
     */
    private void runConsole() {
        try (Scanner scanner = new Scanner(System.in)) {
            System.out.println("Commands:");
            System.out.println("  status   - Show current status");
            System.out.println("  leader   - Check if I am leader");
            System.out.println("  servers  - List all active servers");
            System.out.println("  health   - Show consensus health report");
            System.out.println("  quit     - Shutdown this node");
            System.out.println();
            
            while (running) {
                System.out.print(config.getServerId() + "> ");
                String command = scanner.nextLine().trim().toLowerCase();
                
                try {
                    switch (command) {
                        case "status":
                            printStatus();
                            break;
                            
                        case "leader":
                            if (leaderElection.isLeader()) {
                                System.out.println("YES! I am the LEADER!");
                            } else {
                                System.out.println("NO. I am a follower. Leader is: " + 
                                                  leaderElection.getCurrentLeader());
                            }
                            break;
                            
                        case "servers":
                            System.out.println("Active servers: " + leaderElection.getActiveServers());
                            break;

                        case "health":
                            printHealthReport();
                            break;
                            
                        case "quit":
                            System.out.println("Shutting down...");
                            running = false;
                            break;
                            
                        default:
                            System.out.println("Unknown command. Try: status, leader, servers, quit");
                    }
                } catch (Exception e) {
                    System.err.println("Error: " + e.getMessage());
                }
            }
        }
        
        shutdown();
    }
    
    /**
     * Gracefully shut down the node.
     */
    private void shutdown() {
        try {
            if (leaderElection != null) {
                leaderElection.shutdown();
            }
            if (zkConnection != null) {
                zkConnection.close();
            }
            System.out.println("Storage node " + config.getServerId() + " stopped.");
        } catch (Exception e) {
            System.err.println("Error during shutdown: " + e.getMessage());
        }
    }
    
    /**
     * Main method - entry point for each storage node.
     * Usage: java StorageNode <server-id> [zookeeper-address] [storage-port]
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: java StorageNode <server-id> [zookeeper-address] [storage-port]");
            System.out.println("Example: java StorageNode server-1");
            System.out.println("Example: java StorageNode server-2 localhost:2181");
            System.out.println("Example: java StorageNode server-3 localhost:2181 8083");
            System.exit(1);
        }
        
        String serverId = args[0];
        String zkAddress = args.length >= 2 ? args[1] : "localhost:2181";
        int storagePort = args.length >= 3 ? Integer.parseInt(args[2]) : derivePort(serverId);

        ServerConfig config = new ServerConfig(serverId, zkAddress, storagePort);
        StorageNode node = new StorageNode(config);
        
        // Add shutdown hook for clean exit
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutdown signal received...");
            node.shutdown();
        }));
        
        try {
            node.start();
        } catch (Exception e) {
            System.err.println("Failed to start storage node: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static int derivePort(String serverId) {
        int end = serverId.length() - 1;
        while (end >= 0 && Character.isDigit(serverId.charAt(end))) {
            end--;
        }

        if (end == serverId.length() - 1) {
            return 8080;
        }

        String suffixText = serverId.substring(end + 1);
        int suffix = Integer.parseInt(suffixText);
        return suffix > 0 ? 8080 + suffix : 8080;
    }
}
