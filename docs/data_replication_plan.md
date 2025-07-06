# FastCache Data Replication Plan

## Overview

This document outlines the data replication strategy for the FastCache distributed caching system, including current implementation analysis, replication patterns, and improvement strategies.

## Current Replication Implementation

### 1. **DistributedCacheManager Replication**

The current implementation uses a **synchronous replication model** with the following characteristics:

```java
// Current replication configuration
public DistributedCacheManager(int virtualNodes, int replicationFactor, boolean enableReplication) {
    this.replicationFactor = replicationFactor;  // Default: 2
    this.enableReplication = enableReplication;  // Default: true
}

// Replication logic for SET operations
public CompletableFuture<Boolean> set(String key, Object value, long ttlSeconds, CacheEntry.EntryType type) {
    Collection<CacheNode> nodes = getReplicationNodes(key);  // Gets 2 nodes for replication
    
    List<CompletableFuture<Boolean>> futures = nodes.stream()
            .map(node -> CompletableFuture.supplyAsync(() -> {
                CacheEngine engine = localEngines.get(node.getId());
                if (engine != null) {
                    return engine.set(key, value, ttlSeconds, type);
                }
                return false;
            }, executor))
            .toList();
    
    // Wait for ALL replicas to complete
    return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream().anyMatch(CompletableFuture::join));
}
```

### 2. **Consistent Hashing for Replication**

```java
// Gets multiple nodes for replication
public Collection<CacheNode> getReplicationNodes(String key) {
    if (!enableReplication) {
        CacheNode node = getResponsibleNode(key);
        return node != null ? List.of(node) : List.of();
    }
    return consistentHash.getNodes(key, replicationFactor);  // Gets 2 nodes
}
```

### 3. **Current Replication Characteristics**

| Aspect | Current Implementation |
|--------|----------------------|
| **Replication Factor** | 2 (configurable) |
| **Replication Model** | Synchronous |
| **Consistency Level** | Strong consistency (all replicas) |
| **Failure Handling** | Returns false if any replica fails |
| **Performance Impact** | High latency (waits for all replicas) |
| **Data Types** | All types (String, SortedSet, etc.) |

## Replication Architecture

### **Current Replication Flow**

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              CLIENT REQUEST                                 │
│                              SET key1 value1                                │
└─────────────────────┬───────────────────────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                              PROXY LAYER                                    │
│                                                                             │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐        │
│  │   Proxy 1   │  │   Proxy 2   │  │   Proxy 3   │  │   Proxy 4   │        │
│  │             │  │             │  │             │  │             │        │
│  │ • Route     │  │ • Route     │  │ • Route     │  │ • Route     │        │
│  │ • Replicate │  │ • Replicate │  │ • Replicate │  │ • Replicate │        │
│  │ • Wait All  │  │ • Wait All  │  │ • Wait All  │  │ • Wait All  │        │
│  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘        │
└─────────────────────┬───────────────────────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                              REPLICATION LAYER                             │
│                                                                             │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐    ┌─────────────┐   │
│  │ Primary     │    │ Replica 1   │    │ Replica 2   │    │ Replica N   │   │
│  │ Node        │    │ Node        │    │ Node        │    │ Node        │   │
│  │             │    │             │    │             │    │             │   │
│  │ • Store     │    │ • Store     │    │ • Store     │    │ • Store     │   │
│  │ • Validate  │    │ • Validate  │    │ • Validate  │    │ • Validate  │   │
│  │ • Confirm   │    │ • Confirm   │    │ • Confirm   │    │ • Confirm   │   │
│  └─────────────┘    └─────────────┘    └─────────────┘    └─────────────┘   │
│         │                   │                   │                   │      │
│         └───────────────────┼───────────────────┼───────────────────┘      │
│                             │                   │                          │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                    Replication Coordinator                          │   │
│  │                    • Wait for all replicas                         │   │
│  │                    • Handle failures                               │   │
│  │                    • Return success/failure                        │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Replication Strategies

### **1. Synchronous Replication (Current)**

**Characteristics:**
- **Consistency**: Strong consistency
- **Performance**: High latency
- **Reliability**: High (waits for all replicas)
- **Use Case**: Critical data requiring strong consistency

**Implementation:**
```java
// Current synchronous replication
public CompletableFuture<Boolean> setSynchronous(String key, Object value) {
    Collection<CacheNode> nodes = getReplicationNodes(key);
    
    List<CompletableFuture<Boolean>> futures = nodes.stream()
            .map(node -> replicateToNode(node, key, value))
            .toList();
    
    // Wait for ALL replicas to complete
    return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream().allMatch(CompletableFuture::join));
}
```

**Pros:**
- ✅ Strong consistency guarantee
- ✅ No data loss on node failures
- ✅ Simple failure detection

**Cons:**
- ❌ High latency (waits for all replicas)
- ❌ Reduced availability during node failures
- ❌ Performance bottleneck

### **2. Asynchronous Replication (Proposed)**

**Characteristics:**
- **Consistency**: Eventual consistency
- **Performance**: Low latency
- **Reliability**: Medium (background replication)
- **Use Case**: High-performance scenarios with eventual consistency

**Implementation:**
```java
// Proposed asynchronous replication
public CompletableFuture<Boolean> setAsynchronous(String key, Object value) {
    // Primary write (immediate response)
    CacheNode primaryNode = getResponsibleNode(key);
    CompletableFuture<Boolean> primaryFuture = replicateToNode(primaryNode, key, value);
    
    // Background replication
    Collection<CacheNode> replicaNodes = getReplicationNodes(key);
    replicaNodes.remove(primaryNode);
    
    CompletableFuture.runAsync(() -> {
        replicaNodes.forEach(node -> replicateToNode(node, key, value));
    }, replicationExecutor);
    
    return primaryFuture;
}
```

**Pros:**
- ✅ Low latency (immediate response)
- ✅ High availability
- ✅ Better performance

**Cons:**
- ❌ Eventual consistency
- ❌ Potential data loss on primary failure
- ❌ More complex failure handling

### **3. Quorum-Based Replication (Proposed)**

**Characteristics:**
- **Consistency**: Strong consistency with quorum
- **Performance**: Medium latency
- **Reliability**: High (quorum-based)
- **Use Case**: Balanced consistency and performance

**Implementation:**
```java
// Proposed quorum-based replication
public CompletableFuture<Boolean> setQuorum(String key, Object value, int quorumSize) {
    Collection<CacheNode> nodes = getReplicationNodes(key);
    
    List<CompletableFuture<Boolean>> futures = nodes.stream()
            .map(node -> replicateToNode(node, key, value))
            .toList();
    
    // Wait for quorum (majority) to complete
    return CompletableFuture.supplyAsync(() -> {
        int successCount = 0;
        for (CompletableFuture<Boolean> future : futures) {
            if (future.join()) {
                successCount++;
                if (successCount >= quorumSize) {
                    return true; // Quorum achieved
                }
            }
        }
        return false; // Quorum not achieved
    });
}
```

**Pros:**
- ✅ Strong consistency with quorum
- ✅ Better performance than full replication
- ✅ Tolerates minority node failures

**Cons:**
- ❌ More complex implementation
- ❌ Requires quorum size configuration
- ❌ Still has latency overhead

## Data Type-Specific Replication

### **1. String/Simple Values**

**Current Implementation:**
```java
// Simple key-value replication
public CompletableFuture<Boolean> set(String key, Object value, CacheEntry.EntryType type) {
    Collection<CacheNode> nodes = getReplicationNodes(key);
    
    List<CompletableFuture<Boolean>> futures = nodes.stream()
            .map(node -> CompletableFuture.supplyAsync(() -> {
                CacheEngine engine = localEngines.get(node.getId());
                return engine.set(key, value, type);
            }, executor))
            .toList();
    
    return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream().anyMatch(CompletableFuture::join));
}
```

### **2. Sorted Sets**

**Current Implementation:**
```java
// Sorted set operations are replicated as atomic operations
public CompletableFuture<Boolean> zadd(String key, String member, double score) {
    Collection<CacheNode> nodes = getReplicationNodes(key);
    
    List<CompletableFuture<Boolean>> futures = nodes.stream()
            .map(node -> CompletableFuture.supplyAsync(() -> {
                CacheEngine engine = localEngines.get(node.getId());
                return engine.zadd(key, member, score);
            }, executor))
            .toList();
    
    return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream().anyMatch(CompletableFuture::join));
}
```

**Challenges:**
- **Atomicity**: Sorted set operations need to be atomic across replicas
- **Consistency**: Score updates must be consistent
- **Performance**: Skip list operations are more expensive

## Replication Topology

### **1. Ring-Based Replication**

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              REPLICATION RING                               │
│                                                                             │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐    ┌─────────────┐   │
│  │   Node 1    │    │   Node 2    │    │   Node 3    │    │   Node 4    │   │
│  │             │    │             │    │             │    │             │   │
│  │ Primary:    │    │ Primary:    │    │ Primary:    │    │ Primary:    │   │
│  │ Keys A-D    │    │ Keys E-H    │    │ Keys I-L    │    │ Keys M-P    │   │
│  │ Replica:    │    │ Replica:    │    │ Replica:    │    │ Replica:    │   │
│  │ Keys M-P    │    │ Keys A-D    │    │ Keys E-H    │    │ Keys I-L    │   │
│  └─────────────┘    └─────────────┘    └─────────────┘    └─────────────┘   │
│         │                   │                   │                   │      │
│         └───────────────────┼───────────────────┼───────────────────┘      │
│                             │                   │                          │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐    ┌─────────────┐   │
│  │   Node 5    │    │   Node 6    │    │   Node 7    │    │   Node 8    │   │
│  │             │    │             │    │             │    │             │   │
│  │ Primary:    │    │ Primary:    │    │ Primary:    │    │ Primary:    │   │
│  │ Keys Q-T    │    │ Keys U-X    │    │ Keys Y-Z    │    │ Keys AA-AD  │   │
│  │ Replica:    │    │ Replica:    │    │ Replica:    │    │ Replica:    │   │
│  │ Keys Y-Z    │    │ Keys AA-AD  │    │ Keys Q-T    │    │ Keys U-X    │   │
│  └─────────────┘    └─────────────┘    └─────────────┘    └─────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
```

### **2. Consistent Hashing Replication**

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        CONSISTENT HASHING REPLICATION                       │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                            Hash Ring                                 │   │
│  │                                                                       │   │
│  │  Node1-V1 ── Node2-V1 ── Node3-V1 ── Node4-V1 ── Node5-V1 ── Node1-V2 │   │
│  │     │           │           │           │           │           │     │   │
│  │  Node2-V2 ── Node3-V2 ── Node4-V2 ── Node5-V2 ── Node1-V3 ── Node2-V3 │   │
│  │     │           │           │           │           │           │     │   │
│  │  Node3-V3 ── Node4-V3 ── Node5-V3 ── Node1-V4 ── Node2-V4 ── Node3-V4 │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  Key Distribution:                                                          │
│  • Key "user:123" → Node2 (Primary) + Node3 (Replica)                      │
│  • Key "session:456" → Node4 (Primary) + Node5 (Replica)                   │
│  • Key "cache:789" → Node1 (Primary) + Node2 (Replica)                     │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Replication Configuration

### **1. Replication Factor Configuration**

```java
// Current configuration
public class ReplicationConfig {
    private int replicationFactor = 2;           // Number of replicas
    private boolean enableReplication = true;    // Enable/disable replication
    private ReplicationStrategy strategy = ReplicationStrategy.SYNCHRONOUS;
    private int quorumSize = 2;                  // For quorum-based replication
    private Duration replicationTimeout = Duration.ofSeconds(5);
    private boolean enableBackgroundReplication = false;
}

public enum ReplicationStrategy {
    SYNCHRONOUS,      // Wait for all replicas
    ASYNCHRONOUS,     // Primary + background replication
    QUORUM,          // Wait for quorum
    EVENTUAL         // Eventual consistency
}
```

### **2. Environment-Based Configuration**

```bash
# Docker environment variables
REPLICATION_FACTOR=2
REPLICATION_STRATEGY=SYNCHRONOUS
QUORUM_SIZE=2
REPLICATION_TIMEOUT=5s
ENABLE_BACKGROUND_REPLICATION=false
```

## Replication Monitoring

### **1. Replication Metrics**

```java
public class ReplicationMetrics {
    private final AtomicLong replicationSuccessCount = new AtomicLong();
    private final AtomicLong replicationFailureCount = new AtomicLong();
    private final AtomicLong replicationLatency = new AtomicLong();
    private final AtomicLong replicaSyncLag = new AtomicLong();
    
    // Metrics tracking
    public void recordReplicationSuccess(long latency) {
        replicationSuccessCount.incrementAndGet();
        replicationLatency.addAndGet(latency);
    }
    
    public void recordReplicationFailure() {
        replicationFailureCount.incrementAndGet();
    }
}
```

### **2. Replication Health Checks**

```java
public class ReplicationHealthChecker {
    public ReplicationHealth checkReplicationHealth(String key) {
        Collection<CacheNode> nodes = getReplicationNodes(key);
        Map<String, Boolean> nodeHealth = new HashMap<>();
        
        for (CacheNode node : nodes) {
            boolean healthy = checkNodeReplicationHealth(node, key);
            nodeHealth.put(node.getId(), healthy);
        }
        
        return new ReplicationHealth(nodeHealth);
    }
}
```

## Failure Handling and Recovery

### **1. Node Failure Scenarios**

#### **Primary Node Failure**
```
Scenario: Primary node fails during write operation
Response: 
1. Detect failure via health checker
2. Promote replica to primary
3. Update consistent hash ring
4. Re-replicate data to new replica
5. Continue operations
```

#### **Replica Node Failure**
```
Scenario: Replica node fails during write operation
Response:
1. Detect failure via health checker
2. Mark replica as unhealthy
3. Continue with remaining replicas
4. Re-replicate to new replica when available
5. Update replication topology
```

### **2. Data Recovery Strategies**

#### **Automatic Recovery**
```java
public class ReplicationRecovery {
    public CompletableFuture<Void> recoverNode(String nodeId) {
        // 1. Identify missing data
        Set<String> missingKeys = identifyMissingKeys(nodeId);
        
        // 2. Re-replicate from healthy replicas
        return CompletableFuture.runAsync(() -> {
            missingKeys.forEach(key -> {
                Object value = getValueFromReplica(key);
                replicateToNode(nodeId, key, value);
            });
        });
    }
}
```

#### **Manual Recovery**
```bash
# Manual recovery commands
./scripts/replication-recovery.sh --node node1 --mode full
./scripts/replication-recovery.sh --node node1 --mode incremental
./scripts/replication-recovery.sh --node node1 --mode verify
```

## Performance Optimization

### **1. Replication Batching**

```java
public class ReplicationBatcher {
    private final Queue<ReplicationTask> batchQueue = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService batchExecutor;
    
    public void batchReplicate(String key, Object value) {
        batchQueue.offer(new ReplicationTask(key, value));
        
        if (batchQueue.size() >= batchSize) {
            flushBatch();
        }
    }
    
    private void flushBatch() {
        List<ReplicationTask> batch = new ArrayList<>();
        ReplicationTask task;
        
        while ((task = batchQueue.poll()) != null && batch.size() < batchSize) {
            batch.add(task);
        }
        
        replicateBatch(batch);
    }
}
```

### **2. Parallel Replication**

```java
public class ParallelReplicator {
    public CompletableFuture<Boolean> replicateParallel(String key, Object value) {
        Collection<CacheNode> nodes = getReplicationNodes(key);
        
        // Parallel replication with timeout
        List<CompletableFuture<Boolean>> futures = nodes.stream()
                .map(node -> replicateToNodeWithTimeout(node, key, value))
                .toList();
        
        return CompletableFuture.anyOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(result -> (Boolean) result);
    }
}
```

## Implementation Roadmap

### **Phase 1: Enhanced Synchronous Replication**
- [ ] Add replication timeout handling
- [ ] Implement retry mechanisms
- [ ] Add replication metrics
- [ ] Improve error handling

### **Phase 2: Asynchronous Replication**
- [ ] Implement background replication
- [ ] Add eventual consistency support
- [ ] Implement replication lag monitoring
- [ ] Add conflict resolution

### **Phase 3: Quorum-Based Replication**
- [ ] Implement quorum-based writes
- [ ] Add quorum size configuration
- [ ] Implement quorum failure handling
- [ ] Add quorum health monitoring

### **Phase 4: Advanced Features**
- [ ] Implement replication batching
- [ ] Add parallel replication
- [ ] Implement automatic recovery
- [ ] Add replication topology management

## Conclusion

The current FastCache replication implementation provides strong consistency through synchronous replication with a replication factor of 2. While this ensures data durability, it comes with performance trade-offs.

The proposed improvements include:

1. **Multiple Replication Strategies**: Synchronous, asynchronous, and quorum-based
2. **Enhanced Monitoring**: Replication metrics and health checks
3. **Better Failure Handling**: Automatic recovery and topology management
4. **Performance Optimization**: Batching and parallel replication

These improvements will provide flexibility to choose the appropriate replication strategy based on application requirements for consistency, performance, and availability. 