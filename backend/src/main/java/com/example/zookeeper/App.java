package com.example.zookeeper;

import com.example.zookeeper.election.LeaderElection;
import org.apache.zookeeper.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class App implements Watcher {

    private static final String ZK_SERVER = "localhost:2181";
    private ZooKeeper zk;

    public static void main(String[] args) throws Exception {
        // Quick health check: create a node
        App app = new App();
        app.connect();
        if (app.zk.exists("/testNode", false) == null) {
            app.createNode("/testNode", "Hello ZooKeeper");
        }
        app.close();

        // Leader election demo
        runLeaderElectionDemo();
    }

    public void connect() throws IOException {
        zk = new ZooKeeper(ZK_SERVER, 3000, this);
    }

    public void createNode(String path, String data) throws Exception {
        zk.create(
                path,
                data.getBytes(),
                ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        System.out.println("Node created: " + path);
    }

    public void close() throws InterruptedException {
        if (zk != null) {
            zk.close();
        }
    }

    private static void runLeaderElectionDemo() throws Exception {
        System.out.println("Starting leader election demo (please ensure ZooKeeper is running on localhost:2181)...");

        List<ZooKeeper> clients = new ArrayList<>();
        List<LeaderElection> electors = new ArrayList<>();
        String[] names = { "node-1", "node-2", "node-3" };

        CountDownLatch electedLatch = new CountDownLatch(1);
        CountDownLatch failoverLatch = new CountDownLatch(1);
        CountDownLatch newLeaderLatch = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean firstLeaderSelected = new java.util.concurrent.atomic.AtomicBoolean(
                false);

        List<Boolean> isLeader = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            isLeader.add(false);
        }

        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            ZooKeeper client = new ZooKeeper(ZK_SERVER, 3000, event -> {
                // no-op for demo
            });
            clients.add(client);

            LeaderElection election = new LeaderElection(client, new LeaderElection.Listener() {
                @Override
                public void onElectedLeader(String leaderNode) {
                    boolean isFirst = firstLeaderSelected.compareAndSet(false, true);
                    isLeader.set(idx, true);
                    System.out.println(names[idx] + " became leader: " + leaderNode);
                    if (isFirst) {
                        electedLatch.countDown();
                    } else {
                        newLeaderLatch.countDown();
                    }
                }

                @Override
                public void onLeaderLost(String previousLeaderNode) {
                    System.out.println(names[idx] + " lost leadership: " + previousLeaderNode);
                    if (isLeader.get(idx)) {
                        isLeader.set(idx, false);
                        failoverLatch.countDown();
                    }
                }
            });
            electors.add(election);
            election.start();
        }

        if (!electedLatch.await(15, TimeUnit.SECONDS)) {
            throw new IllegalStateException("No leader was elected in time");
        }

        int currentLeader = -1;
        for (int i = 0; i < isLeader.size(); i++) {
            if (isLeader.get(i)) {
                currentLeader = i;
                break;
            }
        }

        if (currentLeader < 0) {
            throw new IllegalStateException("Leader not found after election");
        }

        System.out.println("Current leader is " + names[currentLeader] + ", resigning to force failover");
        electors.get(currentLeader).resign();

        if (!newLeaderLatch.await(15, TimeUnit.SECONDS)) {
            throw new IllegalStateException("New leader election did not occur in time");
        }

        System.out.println("Failover occurred. Verifying at least one leader remains...");
        int leaders = 0;
        for (Boolean leader : isLeader) {
            if (leader)
                leaders++;
        }

        System.out.println("Number of leaders after failover: " + leaders);
        if (leaders != 1) {
            throw new IllegalStateException("Expected exactly one leader after failover, found: " + leaders);
        }

        for (LeaderElection election : electors) {
            election.resign();
        }
        for (ZooKeeper client : clients) {
            client.close();
        }

        System.out.println("Leader election demo completed successfully.");
    }

    @Override
    public void process(WatchedEvent event) {
        System.out.println("Event received: " + event);
    }
}
