package com.example.zookeeper;

import com.example.zookeeper.election.LeaderElection;
import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;
import org.apache.curator.test.TestingServer;
import org.apache.zookeeper.ZooKeeper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Unit test for leader election.
 */
public class AppTest
        extends TestCase {
    public AppTest(String testName) {
        super(testName);
    }

    public static Test suite() {
        return new TestSuite(AppTest.class);
    }

    public void testLeaderElectionAndFailover() throws Exception {
        try (TestingServer server = new TestingServer(true)) {
            ZooKeeper zk1 = new ZooKeeper(server.getConnectString(), 3000, event -> {
            });
            ZooKeeper zk2 = new ZooKeeper(server.getConnectString(), 3000, event -> {
            });
            ZooKeeper zk3 = new ZooKeeper(server.getConnectString(), 3000, event -> {
            });

            CountDownLatch electedLatch = new CountDownLatch(1);
            CountDownLatch failoverLatch = new CountDownLatch(1);

            LeaderElection[] elections = new LeaderElection[3];
            ZooKeeper[] zkClients = new ZooKeeper[] { zk1, zk2, zk3 };
            boolean[] elected = new boolean[3];

            for (int i = 0; i < 3; i++) {
                final int index = i;
                elections[i] = new LeaderElection(zkClients[i], new LeaderElection.Listener() {
                    @Override
                    public void onElectedLeader(String leaderNode) {
                        elected[index] = true;
                        electedLatch.countDown();
                    }

                    @Override
                    public void onLeaderLost(String previousLeaderNode) {
                        if (elected[index]) {
                            elected[index] = false;
                            failoverLatch.countDown();
                        }
                    }
                });
                elections[i].start();
            }

            assertTrue("Leader should be elected", electedLatch.await(10, TimeUnit.SECONDS));

            int leaderIndex = -1;
            for (int i = 0; i < elected.length; i++) {
                if (elected[i]) {
                    leaderIndex = i;
                    break;
                }
            }
            assertTrue("Exactly one leader must be selected", leaderIndex != -1);

            elections[leaderIndex].resign();

            assertTrue("New leader should be elected after failover", failoverLatch.await(10, TimeUnit.SECONDS));

            int newLeaderCount = 0;
            for (int i = 0; i < elected.length; i++) {
                if (elected[i]) {
                    newLeaderCount++;
                }
            }
            assertTrue("One new leader should exist after leader resignation", newLeaderCount == 1);

            for (ZooKeeper zk : zkClients) {
                if (zk != null) {
                    zk.close();
                }
            }
        }
    }
}
