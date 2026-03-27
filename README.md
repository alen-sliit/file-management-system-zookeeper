# File Management System (ZooKeeper)

Simple implementation plan for the Distributed Systems group assignment.

## 1) MVP Goal (what must work first)

1. File upload and download
2. Replica failover
3. Leader election after leader failure

## 2) Fixed Project Choices

1. Language and build: Java + Maven
2. Coordination: ZooKeeper
3. Cluster size: 5 servers
4. Replication factor: RF=3
5. Consistency: Eventual consistency
6. Interface: REST API + minimal web UI
7. Primary metrics: read/write latency, failover time
8. Network partition: report analysis only (not MVP implementation)

## 3) Team Split (4 members)

1. Member 1 - Fault Tolerance
	1. Failure detection
	2. Failover flow
	3. Node rejoin recovery

2. Member 2 - Replication and Consistency (+ minimal UI)
	1. Chunking and RF=3 replication
	2. Versioning and conflict handling
	3. Simple upload/download/status UI

3. Member 3 - Time Synchronization
	1. NTP setup
	2. Drift/skew monitoring
	3. Skew failure handling strategy

4. Member 4 - Consensus and Leader Election
	1. ZooKeeper leader election
	2. Leader handover logic
	3. Consensus/failover test scenarios

## 4) Simple Architecture

1. ZooKeeper manages leader election, membership, and health status.
2. Leader receives writes and assigns versions.
3. Leader replicates updates asynchronously to followers.
4. Reads come from available replicas.
5. Rejoined nodes catch up missing data from healthy nodes.

## 5) One-Week Execution Plan (to 31st)

1. Day 1
	1. Finalize APIs and metadata schema
	2. Set up ZooKeeper and 5-node local environment

2. Day 2
	1. Implement upload/download baseline
	2. Store and fetch metadata

3. Day 3
	1. Add RF=3 replication
	2. Add version checks for eventual consistency

4. Day 4
	1. Add leader election and discovery
	2. Test leader failure and automatic switch

5. Day 5
	1. Add node rejoin catch-up
	2. Finish minimal UI

6. Day 6
	1. Run tests and collect metrics (latency, failover time)
	2. Fix integration issues

7. Day 7
	1. Finalize report, slides, video, and submission package

## 6) Minimum Test Checklist

1. Upload/download under normal operation
2. Follower failure during read/write
3. Leader failure during active writes
4. Rejoin and recovery of a failed node
5. Concurrent write conflict handling
6. Clock skew detection behavior

## 7) Suggested API Endpoints

1. `POST /files/upload`
2. `GET /files/{fileId}`
3. `GET /files/{fileId}/metadata`
4. `GET /cluster/leader`
5. `GET /cluster/nodes`
6. `POST /admin/simulate/node-fail`
7. `POST /admin/simulate/node-rejoin`

## 8) Submission Checklist

1. Report (10-12 pages)
2. Prototype source code
3. 15-minute presentation slides
4. Text file with GitHub repository link
5. Text file with YouTube presentation link
6. README with member names, registration numbers, emails, and run instructions
7. Descriptive Git commit history from project start

## 9) Quick Start Commands (example)

Update these commands to your final module names.

```bash
# build
mvn clean install

# run backend service
mvn spring-boot:run
```

## 10) Repository Structure

```text
file-management-system-zookeeper/
  backend/
  frontend/
  README.md
```

## 11) Run Commands (5 Storage Servers + 1 Web Server)

Run these from the `backend` folder.

Quick start (starts all 5 storage servers + web server):

```bash
bash start.sh
```

Start each storage server in a separate terminal:

```bash
mvn exec:java -Dexec.args="server-1 localhost:2181 8081"
mvn exec:java -Dexec.args="server-2 localhost:2181 8082"
mvn exec:java -Dexec.args="server-3 localhost:2181 8083"
mvn exec:java -Dexec.args="server-4 localhost:2181 8084"
mvn exec:java -Dexec.args="server-5 localhost:2181 8085"
```

Start web server (separate terminal):

```bash
mvn -f pom.xml exec:java -Dexec.mainClass=com.example.zookeeper.client.WebClientServer -Dexec.args="localhost:2181 8080"
```