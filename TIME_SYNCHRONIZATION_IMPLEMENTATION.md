# Time Synchronization Implementation (Member 3)

## Overview

Full implementation of time synchronization across the distributed file storage system. This ensures all servers have synchronized clocks for consistent file versioning, metadata comparison, and event ordering.

## Components Implemented

### 1. **ClockSynchronizer.java**
NTP (Network Time Protocol) client for synchronizing system clocks.

**Key Features:**
- Queries public NTP servers (pool.ntp.org, time.nist.gov, time.cloudflare.com)
- Measures clock offset from authoritative time source
- Automated periodic synchronization (configurable, default 5 minutes)
- Graceful fallback to system clock if NTP sync fails
- Offset capping (only applies offset if < 30 seconds deviation)

**Key Methods:**
- `getCurrentTimeMillis()` - Get synchronized time in milliseconds
- `getCurrentTimeInstant()` - Get synchronized time as Java Instant
- `getClockOffsetMillis()` - Debug: see current offset from NTP
- `isSynchronized()` - Check if sync is active
- `forceSync()` - Immediate synchronization attempt
- `getSyncStatus()` - Get detailed sync status

**Technical Details:**
- Uses standard UDP (port 123) to query NTP servers
- Implements standard NTP protocol (version 3)
- Non-blocking, doesn't interfere with main operations if NTP slow
- Automatically retries multiple NTP servers on failure

---

### 2. **ClockSkewMonitor.java**
Detects and monitors clock drift across all nodes in the cluster.

**Key Features:**
- Collects clock information from all peers via ZooKeeper
- Calculates clock skew between every pair of servers
- Tracks maximum skew detected in the cluster
- Severity classification: NORMAL → WARNING → ERROR → CRITICAL
- Peer clock information stored at `/clock-info/<serverId>` (ephemeral nodes)

**Severity Levels:**
| Level | Skew Threshold | Behavior |
|-------|---|----------|
| NORMAL | < 1 second | All operations allowed |
| WARNING | 1-5 seconds | Log warnings, allow operations |
| ERROR | 5-30 seconds | Log errors, allow operations but caution |
| CRITICAL | > 30 seconds | Reject write operations, alerts triggered |

**Key Methods:**
- `isClockSkewAcceptable()` - Check if skew within safe bounds
- `getMaxSkewMs()` - Get maximum skew detected
- `getSeverity()` - Get current severity level
- `getPeerClockInfo()` - Get detailed peer clock data

---

### 3. **TimeSyncService.java**
High-level coordination service integrating both components.

**Key Features:**
- Manages ClockSynchronizer and ClockSkewMonitor lifecycle
- Provides unified interface for time queries
- Policy enforcement: allows/denies read/write based on clock health
- Comprehensive health reporting

**Key Methods:**
- `getCurrentTimeInstant()` - Get synchronized time
- `isTimeSyncHealthy()` - Overall system health
- `canPerformWriteOperation()` - Check write eligibility
- `canPerformReadOperation()` - Check read eligibility
- `handleSyncFailure()` - Fallback strategy on failure
- `getTimeSyncStatus()` - Complete status report
- `getHealthReport()` - Human-readable status

**Write Operation Policy:**
- REJECTED if: Synchronization failed, clock skew >= CRITICAL threshold
- WARNED if: Clock skew in ERROR range
- ALLOWED if: Clock health acceptable

**Read Operation Policy:**
- ALWAYS ALLOWED (reads are more tolerant)
- WARNING logged if skew in ERROR or CRITICAL range

---

## Integration Points

### 1. **FileStorageManager.java Integration**

**Constructor Changes:**
```java
// Old: FileStorageManager(ZooKeeper, serverId, storagePath)
// New: FileStorageManager(ZooKeeper, serverId, storagePath, TimeSyncService)
```

**Write Operation Checks:**
- `uploadFile()` - Rejects upload if clock skew prevents writes
- `deleteFile()` - Rejects delete if clock skew prevents writes
- Added setter/getter for TimeSyncService

**Timestamp Usage:**
- Automatically uses synchronized time via `timeSyncService.getCurrentTimeInstant()`
- Falls back to `Instant.now()` if TimeSyncService not configured

**Statistics:**
- Added `timeSyncHealthy` and `clockSkewMs` to storage statistics

---

### 2. **StorageNode.java Integration**

**Initialization in start() method:**
```java
timeSyncService = new TimeSyncService(serverId, zkConnection, 300, 30);
timeSyncService.start();
storageManager.setTimeSyncService(timeSyncService);
```

**Configuration:**
- NTP sync interval: 5 minutes (300 seconds)
- Skew monitor interval: 30 seconds

**Graceful Degradation:**
- If TimeSyncService fails to start, logs warning and continues
- System operates without time sync (uses local clocks)

**Shutdown Handling:**
- Properly stops TimeSyncService on node shutdown
- Logs completion of time sync service shutdown

**New Interactive Commands:**
- `timesync` or `clock` - Display time synchronization status

---

## Failure Handling Strategy

### Scenario 1: NTP Synchronization Fails
**Action:**
1. Logs CRITICAL error
2. Attempts forced immediate sync with all NTP servers
3. Falls back to system clock
4. Checks if skew exceeded CRITICAL threshold
5. Triggers alerts if critical

**Result:** System continues with potentially degraded time accuracy

---

### Scenario 2: Clock Skew Exceeds CRITICAL Threshold (>30s)
**Action:**
1. Blocks all write operations
2. Allows read operations (with warnings)
3. Logs CRITICAL severity alert
4. Suggests immediate administrator intervention

**Result:** System becomes read-only until clocks are fixed

---

### Scenario 3: Clock Skew in ERROR Range (5-30s)
**Action:**
1. Allows write operations
2. Logs ERROR severity warnings
3. Monitors for further drift

**Result:** Operations continue but with warnings

---

### Scenario 4: Clock Skew in WARNING Range (1-5s)
**Action:**
1. Allows all operations
2. Logs warnings periodically

**Result:** Operations continue normally

---

## Impact on System Operations

### File Versioning
- Files with synchronized timestamps maintain correct ordering
- Latest file detection uses `isNewer()` comparison of timestamps
- Clock skew could cause wrong "latest" file selection if uncorrected

### Metadata Consistency
- File metadata stored with synchronized timestamps
- Replication preserves timing information
- Replay of operations requires consistent ordering

### Write Ordering
- Leader assigns timestamps to files
- Followers see consistent ordering across replicas
- Skew monitoring prevents "backward time" anomalies

### Statistics and Monitoring
- TimeSyncService reports included in health metrics
- Storage statistics updated with clock skew data
- Alerts triggered on critical conditions

---

## Testing Clock Skew

### Manual Testing
1. Start all three storage nodes:
   ```bash
   mvn exec:java -Dexec.args="server-1 localhost:2181 8081"
   mvn exec:java -Dexec.args="server-2 localhost:2181 8082"
   mvn exec:java -Dexec.args="server-3 localhost:2181 8083"
   ```

2. Check time sync status:
   ```
   > timesync
   > clock
   ```

3. Observe clock skew monitoring:
   - Check severity level transitions
   - Monitor peer clock information
   - Observe skew changes over time

### Simulated Clock Skew
To simulate clock skew on Linux:
```bash
# Shift system clock back by 10 seconds
sudo date -s "-10 seconds"

# Server will detect skew at next monitor cycle
# Check "timesync" command for alerts
```

### Expected Behavior
- Initial sync completes within first 10 seconds
- Monitor cycle updates every 30 seconds
- Skew detected and logged within 30 seconds of clock change
- If skew > 30s, write operations rejected
- If skew fixed, write operations resume

---

## Configuration Options

### In TimeSyncService Constructor
```java
new TimeSyncService(
    serverId,         // Server identifier
    zooKeeper,        // ZooKeeper connection
    300,              // NTP sync interval (seconds)
    30                // Skew monitor interval (seconds)
)
```

### Threshold Tuning (in ClockSkewMonitor)
```java
WARN_SKEW_MS = 1000;      // 1 second
ERROR_SKEW_MS = 5000;     // 5 seconds
CRITICAL_SKEW_MS = 30000; // 30 seconds
```

Modify these constants to adjust sensitivity to clock drift.

---

## Health Reporting

Use `timesync` command in interactive mode to see:
- Synchronization status (Running/Stopped)
- System health (Healthy/Unhealthy)
- NTP synchronization state
- Current clock offset from NTP
- Maximum skew detected across cluster
- Severity classification
- Current time as Instant
- Write/Read operation eligibility
- Peer clock information

Example output:
```
=== TIME SYNCHRONIZATION REPORT for server-1 ===
Status: RUNNING
Healthy: true
NTP Synchronized: true
Clock Offset: -123 ms
Max Skew Detected: 145 ms
Skew Severity: WARNING
Skew Acceptable: true
Current Time: 2026-03-27T14:45:32.123Z
Can Write: true
Can Read: true

Peer details:
  server-2: offset=-128ms, skew=5ms
  server-3: offset=-118ms, skew=25ms
```

---

## ZooKeeper Integration

### Ephemeral Nodes
- `/clock-info/<serverId>` - Each server's current clock information
- Published on: initialization, and every monitor cycle
- Contains: timestamp, offset, sync status
- Auto-cleaned on server disconnect

### Example Node Content
```json
{
  "serverId": "server-1",
  "localTime": 1743157532123,
  "clockOffset": -123,
  "synchronized": true,
  "timestamp": 1743157532000
}
```

---

## Compilation & Dependencies

**No new Maven dependencies required:**
- Uses Java standard library for NTP protocol
- Uses existing ZooKeeper dependency
- Uses existing SLF4J for logging

**Compilation:**
```bash
mvn clean compile
```

---

## Future Enhancements

1. **PTP Support** - Add IEEE 1588 Precision Time Protocol for better accuracy
2. **Local NTP Server** - Run NTP server on one node, clients sync to it
3. **Byzantine Fault Tolerance** - Detect and exclude obviously wrong clock values
4. **Clocksource Metrics** - Report hardware clock accuracy/quality
5. **Automatic Remediation** - Auto-restart services on critical skew
6. **Integration with Prometheus** - Export metrics for monitoring
7. **Leap Second Handling** - Properly handle TAI/UTC conversions

---

## Conclusion

This implementation provides robust clock synchronization ensuring:
✓ Consistent timestamps across distributed nodes
✓ Proper file versioning and ordering
✓ Early detection of timing anomalies
✓ Graceful degradation when sync fails
✓ Clear alerts for operational issues
✓ No mandatory external service (NTP is optional)
✓ Configurable tolerance thresholds
✓ Comprehensive health monitoring

The system can operate without perfect time sync but maintains safeguards against severe clock skew causing data consistency issues.
