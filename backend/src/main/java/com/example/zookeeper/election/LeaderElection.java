package com.example.zookeeper.election;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Leader election using ZooKeeper ephemeral sequential nodes.
 * Automatically ensures parent path exists and watches it if deleted.
 */
public class LeaderElection implements Watcher {

    public interface Listener {
        void onElectedLeader(String leaderNode);

        void onLeaderLost(String previousLeaderNode);
    }

    private static final String LEADER_PATH = "/fms/leader";
    private static final String CANDIDATE_PREFIX = "candidate_";

    private final ZooKeeper zk;
    private final Listener listener;
    private String currentNodePath;
    private final AtomicBoolean isLeader = new AtomicBoolean(false);

    public LeaderElection(ZooKeeper zk, Listener listener) {
        this.zk = zk;
        this.listener = listener;
    }

    public void start() throws KeeperException, InterruptedException {
        waitForConnected();
        ensureElectionPath();
        joinElection();
        attemptLeadership();
    }

    /**
     * Waits until ZooKeeper client is connected.
     */
    private void waitForConnected() throws InterruptedException {
        while (zk.getState() != ZooKeeper.States.CONNECTED) {
            Thread.sleep(100);
        }
    }

    /**
     * Ensures the parent path exists, and watches it for deletion.
     */
    private void ensureElectionPath() throws KeeperException, InterruptedException {
        createPathRecursively(LEADER_PATH);

        // Watch the parent path in case it gets deleted
        zk.exists(LEADER_PATH, event -> {
            if (event.getType() == Watcher.Event.EventType.NodeDeleted) {
                try {
                    createPathRecursively(LEADER_PATH);
                    attemptLeadership();
                } catch (Exception e) {
                    System.err.println("Failed to recreate election path: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Recursively creates a path if it doesn’t exist.
     */
    private void createPathRecursively(String path) throws KeeperException, InterruptedException {
        if (path == null || path.isEmpty() || "/".equals(path))
            return;
        if (zk.exists(path, false) != null)
            return;

        int lastSlash = path.lastIndexOf('/');
        if (lastSlash > 0)
            createPathRecursively(path.substring(0, lastSlash));

        try {
            zk.create(path, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        } catch (KeeperException.NodeExistsException ignored) {
            // race condition is okay
        }
    }

    /**
     * Join leader election by creating an ephemeral sequential node.
     */
    private void joinElection() throws KeeperException, InterruptedException {
        String created = zk.create(
                LEADER_PATH + "/" + CANDIDATE_PREFIX,
                new byte[0],
                ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.EPHEMERAL_SEQUENTIAL);
        currentNodePath = created;
        System.out.println("Joined leader election with node: " + currentNodePath);
    }

    /**
     * Attempts to become leader by checking if this node is the smallest.
     */
    private void attemptLeadership() throws KeeperException, InterruptedException {
        List<String> children = zk.getChildren(LEADER_PATH, false);
        if (children.isEmpty()) {
            // Node removed unexpectedly
            if (isLeader.compareAndSet(true, false))
                listener.onLeaderLost(currentNodePath);
            return;
        }

        Collections.sort(children);
        String currentNodeName = currentNodePath.substring(LEADER_PATH.length() + 1);
        String smallestNode = children.get(0);

        if (currentNodeName.equals(smallestNode)) {
            if (isLeader.compareAndSet(false, true))
                listener.onElectedLeader(currentNodePath);
            return;
        }

        int myIndex = children.indexOf(currentNodeName);
        if (myIndex < 0) {
            if (isLeader.compareAndSet(true, false))
                listener.onLeaderLost(currentNodePath);
            return;
        }

        String predecessor = children.get(myIndex - 1);
        String predecessorPath = LEADER_PATH + "/" + predecessor;

        if (zk.exists(predecessorPath, this) == null) {
            // Predecessor gone; try again
            attemptLeadership();
            return;
        }

        if (isLeader.get()) {
            isLeader.set(false);
            listener.onLeaderLost(currentNodePath);
        }
    }

    public boolean isLeader() {
        return isLeader.get();
    }

    @Override
    public void process(WatchedEvent event) {
        if (event.getType() == Event.EventType.NodeDeleted) {
            try {
                attemptLeadership();
            } catch (KeeperException | InterruptedException e) {
                System.err.println("Failed to re-check leadership after predecessor loss: " + e.getMessage());
            }
        }
    }

    /**
     * Resign leadership and delete ephemeral node.
     */
    public void resign() {
        try {
            if (currentNodePath != null && zk.exists(currentNodePath, false) != null) {
                zk.delete(currentNodePath, -1);
            }
            if (isLeader.get()) {
                isLeader.set(false);
                listener.onLeaderLost(currentNodePath);
            }
        } catch (KeeperException | InterruptedException ex) {
            System.err.println("Failed to resign cleanly: " + ex.getMessage());
        }
    }
}
