package com.example.zookeeper;

import org.apache.zookeeper.*;

import java.io.IOException;

public class App implements Watcher {

    private static final String ZK_SERVER = "localhost:2181";
    private ZooKeeper zk;

    public static void main(String[] args) throws Exception {
        App app = new App();
        app.connect();
        app.createNode("/testNode", "Hello ZooKeeper");
        app.close();
    }

    public void connect() throws IOException {
        zk = new ZooKeeper(ZK_SERVER, 3000, this);
    }

    public void createNode(String path, String data) throws Exception {
        zk.create(
                path,
                data.getBytes(),
                ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT
        );
        System.out.println("Node created: " + path);
    }

    public void close() throws InterruptedException {
        zk.close();
    }

    @Override
    public void process(WatchedEvent event) {
        System.out.println("Event received: " + event);
    }
}