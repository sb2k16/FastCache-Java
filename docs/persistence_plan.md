# FastCache Persistence and Recovery Plan

## Overview

This document outlines a comprehensive plan to add persistence capabilities to FastCache, enabling data recovery when nodes fail and providing data durability.

## Current State Analysis

### **Current Limitations:**
- **Memory-only storage**: All data lost on node restart
- **No persistence**: No disk-based backup/restore
- **No crash recovery**: Cannot recover from node failures
- **No data durability**: Data vulnerable to hardware failures

### **Current Recovery Mechanisms:**
- **Replication**: 2x replication provides limited protection
- **Health checks**: Failover to healthy nodes
- **No persistence**: Cannot recover data after node restart

## Proposed Persistence Architecture

### **1. Multi-Level Persistence Strategy**

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           PERSISTENCE LAYERS                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐             │
│  │   Memory Cache  │  │   Write Buffer  │  │   Disk Storage  │             │
│  │   (Fast)        │  │   (Async)       │  │   (Persistent)  │             │
│  └─────────────────┘  └─────────────────┘  └─────────────────┘             │
│         │                     │                     │                      │
│         │              ┌──────┴──────┐              │                      │
│         │              │   Snapshot  │              │                      │
│         │              │   (Periodic)│              │                      │
│         │              └─────────────┘              │                      │
│         │                     │                     │                      │
│  ┌──────┴─────────────────────┴─────────────────────┴──────┐              │
│  │                    Recovery Manager                      │              │
│  │              • Crash Recovery                            │              │
│  │              • Data Validation                           │              │
│  │              • Consistency Checks                        │              │
│  └─────────────────────────────────────────────────────────┘              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### **2. Persistence Implementation**

#### **A. Write-Ahead Log (WAL)**
```java
public class WriteAheadLog {
    private final String logFile;
    private final BufferedWriter writer;
    private final AtomicLong sequenceNumber = new AtomicLong(0);
    
    public void logOperation(String operation, String key, Object value) {
        String logEntry = String.format("%d|%s|%s|%s\n", 
            sequenceNumber.incrementAndGet(), operation, key, serialize(value));
        writer.write(logEntry);
        writer.flush(); // Ensure durability
    }
    
    public void replayLog(CacheEngine cache) {
        // Replay operations from log file
        Files.lines(Paths.get(logFile))
            .map(this::parseLogEntry)
            .forEach(entry -> replayOperation(cache, entry));
    }
}
```

#### **B. Snapshot Persistence**
```java
public class SnapshotManager {
    private final String snapshotDir;
    private final ScheduledExecutorService snapshotExecutor;
    
    public void createSnapshot(CacheEngine cache) {
        String snapshotFile = snapshotDir + "/snapshot-" + System.currentTimeMillis() + ".dat";
        
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(snapshotFile))) {
            // Serialize cache state
            oos.writeObject(cache.getCacheState());
            oos.writeObject(cache.getSortedSetsState());
            
            // Update latest snapshot reference
            updateLatestSnapshot(snapshotFile);
        }
    }
    
    public void restoreFromSnapshot(CacheEngine cache, String snapshotFile) {
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(snapshotFile))) {
            // Deserialize cache state
            Map<String, CacheEntry> cacheState = (Map<String, CacheEntry>) ois.readObject();
            Map<String, SortedSet> sortedSetsState = (Map<String, SortedSet>) ois.readObject();
            
            // Restore cache
            cache.restoreFromState(cacheState, sortedSetsState);
        }
    }
}
```

#### **C. Incremental Backup**
```java
public class IncrementalBackup {
    private final String backupDir;
    private final Map<String, Long> lastBackupTimes = new ConcurrentHashMap<>();
    
    public void backupIncremental(String key, CacheEntry entry) {
        long lastBackup = lastBackupTimes.getOrDefault(key, 0L);
        long entryModified = entry.getLastModified();
        
        if (entryModified > lastBackup) {
            // Backup only changed entries
            String backupFile = backupDir + "/" + key.hashCode() + ".entry";
            serializeEntry(entry, backupFile);
            lastBackupTimes.put(key, entryModified);
        }
    }
    
    public void restoreIncremental(CacheEngine cache) {
        // Restore from incremental backups
        Files.list(Paths.get(backupDir))
            .filter(path -> path.toString().endsWith(".entry"))
            .forEach(path -> {
                CacheEntry entry = deserializeEntry(path.toString());
                cache.set(entry.getKey(), entry.getValue(), entry.getTtlSeconds(), entry.getType());
            });
    }
}
```

### **3. Recovery Strategies**

#### **A. Crash Recovery**
```java
public class CrashRecovery {
    private final WriteAheadLog wal;
    private final SnapshotManager snapshotManager;
    private final IncrementalBackup incrementalBackup;
    
    public void recoverFromCrash(CacheEngine cache) {
        logger.info("Starting crash recovery...");
        
        // 1. Find latest snapshot
        String latestSnapshot = snapshotManager.getLatestSnapshot();
        if (latestSnapshot != null) {
            logger.info("Restoring from snapshot: {}", latestSnapshot);
            snapshotManager.restoreFromSnapshot(cache, latestSnapshot);
        }
        
        // 2. Replay write-ahead log since snapshot
        long snapshotTime = getSnapshotTime(latestSnapshot);
        logger.info("Replaying WAL since snapshot time: {}", snapshotTime);
        wal.replayLogSince(cache, snapshotTime);
        
        // 3. Apply incremental backups
        logger.info("Applying incremental backups...");
        incrementalBackup.restoreIncremental(cache);
        
        logger.info("Crash recovery completed. Cache size: {}", cache.size());
    }
}
```

#### **B. Node Failure Recovery**
```java
public class NodeFailureRecovery {
    private final DistributedCacheManager clusterManager;
    private final ReplicationManager replicationManager;
    
    public void recoverFailedNode(String nodeId) {
        logger.info("Recovering failed node: {}", nodeId);
        
        // 1. Identify missing data
        Set<String> missingKeys = identifyMissingKeys(nodeId);
        
        // 2. Restore from replicas
        for (String key : missingKeys) {
            Object value = getValueFromReplica(key);
            if (value != null) {
                replicateToNode(nodeId, key, value);
            }
        }
        
        // 3. Restore from persistence if available
        if (hasPersistenceData(nodeId)) {
            restoreFromPersistence(nodeId);
        }
        
        logger.info("Node recovery completed for: {}", nodeId);
    }
}
```

### **4. Configuration Options**

#### **Persistence Configuration**
```java
public class PersistenceConfig {
    private boolean enablePersistence = true;
    private boolean enableWriteAheadLog = true;
    private boolean enableSnapshots = true;
    private boolean enableIncrementalBackup = false;
    
    private Duration snapshotInterval = Duration.ofMinutes(5);
    private Duration walFlushInterval = Duration.ofSeconds(1);
    private String persistenceDir = "/app/data";
    private long maxSnapshotSize = 1024 * 1024 * 1024; // 1GB
    
    // Recovery settings
    private boolean enableCrashRecovery = true;
    private boolean enableNodeRecovery = true;
    private Duration recoveryTimeout = Duration.ofMinutes(10);
}
```

#### **Environment Variables**
```bash
# Persistence configuration
ENABLE_PERSISTENCE=true
ENABLE_WRITE_AHEAD_LOG=true
ENABLE_SNAPSHOTS=true
ENABLE_INCREMENTAL_BACKUP=false

# Recovery settings
ENABLE_CRASH_RECOVERY=true
ENABLE_NODE_RECOVERY=true
RECOVERY_TIMEOUT=10m

# Storage settings
PERSISTENCE_DIR=/app/data
SNAPSHOT_INTERVAL=5m
WAL_FLUSH_INTERVAL=1s
MAX_SNAPSHOT_SIZE=1GB
```

### **5. Performance Considerations**

#### **A. Asynchronous Persistence**
```java
public class AsyncPersistenceManager {
    private final ExecutorService persistenceExecutor;
    private final Queue<PersistenceTask> taskQueue;
    
    public void persistAsync(String operation, String key, Object value) {
        PersistenceTask task = new PersistenceTask(operation, key, value);
        taskQueue.offer(task);
        
        // Process in background
        persistenceExecutor.submit(() -> {
            processPersistenceTask(task);
        });
    }
}
```

#### **B. Compression and Optimization**
```java
public class OptimizedPersistence {
    private final CompressionStrategy compression;
    private final SerializationStrategy serialization;
    
    public void saveOptimized(CacheEngine cache, String file) {
        // Use efficient serialization
        byte[] data = serialization.serialize(cache.getCacheState());
        
        // Compress data
        byte[] compressed = compression.compress(data);
        
        // Write to disk
        Files.write(Paths.get(file), compressed);
    }
}
```

### **6. Monitoring and Observability**

#### **A. Persistence Metrics**
```java
public class PersistenceMetrics {
    private final AtomicLong snapshotCount = new AtomicLong();
    private final AtomicLong walEntries = new AtomicLong();
    private final AtomicLong recoveryTime = new AtomicLong();
    private final AtomicLong dataSize = new AtomicLong();
    
    public void recordSnapshot(long size) {
        snapshotCount.incrementAndGet();
        dataSize.addAndGet(size);
    }
    
    public void recordRecoveryTime(long duration) {
        recoveryTime.set(duration);
    }
}
```

#### **B. Health Checks**
```java
public class PersistenceHealthChecker {
    public PersistenceHealth checkPersistenceHealth() {
        return new PersistenceHealth(
            checkDiskSpace(),
            checkWritePermissions(),
            checkSnapshotIntegrity(),
            checkWalIntegrity()
        );
    }
}
```

### **7. Implementation Phases**

#### **Phase 1: Basic Persistence (Week 1-2)**
- [ ] Implement Write-Ahead Log
- [ ] Add basic snapshot functionality
- [ ] Create crash recovery mechanism
- [ ] Add persistence configuration

#### **Phase 2: Advanced Features (Week 3-4)**
- [ ] Implement incremental backups
- [ ] Add compression and optimization
- [ ] Create node failure recovery
- [ ] Add persistence metrics

#### **Phase 3: Integration (Week 5-6)**
- [ ] Integrate with existing cache engine
- [ ] Add Docker volume support
- [ ] Create recovery scripts
- [ ] Performance testing

#### **Phase 4: Production Ready (Week 7-8)**
- [ ] Add comprehensive monitoring
- [ ] Create backup/restore tools
- [ ] Add data validation
- [ ] Documentation and testing

### **8. Expected Benefits**

#### **Data Durability**
- **Crash recovery**: Survive node restarts
- **Hardware failure protection**: Data survives disk failures
- **Disaster recovery**: Backup and restore capabilities

#### **Operational Benefits**
- **Zero data loss**: No data lost on node failures
- **Fast recovery**: Quick restart with data intact
- **Operational confidence**: Reliable data storage

#### **Performance Impact**
- **Minimal overhead**: Asynchronous persistence
- **Configurable**: Enable/disable based on needs
- **Optimized**: Compression and efficient serialization

## Conclusion

Adding persistence to FastCache will transform it from a memory-only cache to a durable, reliable caching system. The multi-level persistence strategy provides:

1. **Data durability** through write-ahead logs and snapshots
2. **Fast recovery** from crashes and node failures
3. **Operational reliability** with backup and restore capabilities
4. **Performance optimization** through asynchronous operations

This will make FastCache suitable for production environments where data durability is critical. 