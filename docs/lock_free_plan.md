# Lock-Free FastCache Architecture Plan

## Overview

This plan proposes eliminating explicit ReadWriteLocks from FastCache by implementing thread partitioning, lock-free data structures, and modern concurrency techniques inspired by Memcached and modern cache systems.

## Current Locking Issues

### Problems with Current Approach
```java
// Current FastCache - ReadWriteLock overhead
private final ReadWriteLock lock = new ReentrantReadWriteLock();

public Object get(String key) {
    lock.readLock().lock();  // Contention point
    try {
        return cache.get(key);
    } finally {
        lock.readLock().unlock();
    }
}
```

**Issues:**
- **Lock contention**: Multiple threads waiting for locks
- **Cache line bouncing**: False sharing between threads
- **Unpredictable latency**: Lock acquisition time varies
- **Scalability limits**: Performance degrades with more threads

## Proposed Lock-Free Architecture

### 1. Thread Partitioning Strategy

#### Hash-Based Partitioning
```java
public class PartitionedCacheEngine {
    private final CachePartition[] partitions;
    private final int numPartitions;
    
    public PartitionedCacheEngine(int numPartitions) {
        this.numPartitions = numPartitions;
        this.partitions = new CachePartition[numPartitions];
        
        for (int i = 0; i < numPartitions; i++) {
            partitions[i] = new CachePartition();
        }
    }
    
    private int getPartition(String key) {
        return Math.abs(key.hashCode()) % numPartitions;
    }
    
    public Object get(String key) {
        int partition = getPartition(key);
        return partitions[partition].get(key);
    }
}
```

#### Thread Affinity
```java
public class ThreadAffinityPartition {
    private final ThreadLocal<Integer> threadId = new ThreadLocal<>();
    private final AtomicInteger nextThreadId = new AtomicInteger(0);
    
    public void assignThreadToPartition() {
        if (threadId.get() == null) {
            threadId.set(nextThreadId.getAndIncrement());
        }
    }
    
    public int getThreadPartition() {
        return threadId.get() % numPartitions;
    }
}
```

### 2. Lock-Free Data Structures

#### Lock-Free Hash Table
```java
public class LockFreeHashMap<K, V> {
    private final AtomicReferenceArray<Node<K, V>> table;
    private final int capacity;
    
    static class Node<K, V> {
        final K key;
        final V value;
        final AtomicReference<Node<K, V>> next;
        
        Node(K key, V value) {
            this.key = key;
            this.value = value;
            this.next = new AtomicReference<>();
        }
    }
    
    public V get(K key) {
        int index = hash(key) % capacity;
        Node<K, V> current = table.get(index);
        
        while (current != null) {
            if (key.equals(current.key)) {
                return current.value;
            }
            current = current.next.get();
        }
        return null;
    }
    
    public boolean put(K key, V value) {
        int index = hash(key) % capacity;
        Node<K, V> newNode = new Node<>(key, value);
        
        while (true) {
            Node<K, V> current = table.get(index);
            if (current == null) {
                if (table.compareAndSet(index, null, newNode)) {
                    return true;
                }
            } else {
                // Handle collision with lock-free linked list
                if (updateLinkedList(current, newNode)) {
                    return true;
                }
            }
        }
    }
}
```

#### Lock-Free Skip List for Sorted Sets
```java
public class LockFreeSkipList<T> {
    private final AtomicReference<Node<T>> head;
    private final int maxLevel;
    
    static class Node<T> {
        final T value;
        final double score;
        final AtomicReferenceArray<Node<T>> next;
        final int level;
        
        Node(T value, double score, int level) {
            this.value = value;
            this.score = score;
            this.level = level;
            this.next = new AtomicReferenceArray<>(level + 1);
        }
    }
    
    public boolean add(T value, double score) {
        Node<T>[] update = new Node[maxLevel];
        Node<T> current = head.get();
        
        // Find insertion point
        for (int i = maxLevel - 1; i >= 0; i--) {
            while (current.next.get(i) != null && 
                   current.next.get(i).score < score) {
                current = current.next.get(i);
            }
            update[i] = current;
        }
        
        // Insert new node
        int level = randomLevel();
        Node<T> newNode = new Node<>(value, score, level);
        
        for (int i = 0; i < level; i++) {
            newNode.next.set(i, update[i].next.get(i));
            if (!update[i].next.compareAndSet(i, update[i].next.get(i), newNode)) {
                return false; // Retry
            }
        }
        return true;
    }
}
```

### 3. Memory Management

#### Slab Allocator (Inspired by Memcached)
```java
public class SlabAllocator {
    private final SlabClass[] slabClasses;
    private final int numSlabClasses;
    
    static class SlabClass {
        private final int itemSize;
        private final AtomicReferenceArray<Slab> slabs;
        private final AtomicInteger currentSlab;
        
        SlabClass(int itemSize) {
            this.itemSize = itemSize;
            this.slabs = new AtomicReferenceArray<>(MAX_SLABS);
            this.currentSlab = new AtomicInteger(0);
        }
        
        public SlabItem allocate() {
            while (true) {
                int slabIndex = currentSlab.get();
                Slab slab = slabs.get(slabIndex);
                
                if (slab == null) {
                    slab = createNewSlab();
                    if (slabs.compareAndSet(slabIndex, null, slab)) {
                        return slab.allocate();
                    }
                } else {
                    SlabItem item = slab.allocate();
                    if (item != null) {
                        return item;
                    }
                    // Slab full, try next
                    currentSlab.incrementAndGet();
                }
            }
        }
    }
}
```

#### Off-Heap Memory
```java
public class OffHeapCache {
    private final Unsafe unsafe;
    private final long baseAddress;
    private final long size;
    
    public OffHeapCache(long sizeInBytes) {
        this.unsafe = getUnsafe();
        this.size = sizeInBytes;
        this.baseAddress = unsafe.allocateMemory(sizeInBytes);
    }
    
    public void put(long offset, byte[] data) {
        unsafe.copyMemory(data, Unsafe.ARRAY_BYTE_BASE_OFFSET, 
                         null, baseAddress + offset, data.length);
    }
    
    public byte[] get(long offset, int length) {
        byte[] data = new byte[length];
        unsafe.copyMemory(null, baseAddress + offset, 
                         data, Unsafe.ARRAY_BYTE_BASE_OFFSET, length);
        return data;
    }
}
```

### 4. Eviction Strategies

#### Lock-Free LRU with Ring Buffer
```java
public class LockFreeLRU {
    private final AtomicReferenceArray<LRUEntry> ringBuffer;
    private final AtomicLong head;
    private final AtomicLong tail;
    private final int capacity;
    
    static class LRUEntry {
        final String key;
        final long timestamp;
        volatile boolean valid = true;
        
        LRUEntry(String key) {
            this.key = key;
            this.timestamp = System.nanoTime();
        }
    }
    
    public void access(String key) {
        long currentTail = tail.getAndIncrement();
        int index = (int) (currentTail % capacity);
        
        LRUEntry entry = new LRUEntry(key);
        ringBuffer.set(index, entry);
        
        // Evict old entries if needed
        evictOldEntries();
    }
    
    private void evictOldEntries() {
        long currentHead = head.get();
        long currentTail = tail.get();
        
        if (currentTail - currentHead > capacity) {
            // Evict oldest entry
            int index = (int) (currentHead % capacity);
            LRUEntry entry = ringBuffer.get(index);
            if (entry != null) {
                entry.valid = false;
                // Remove from cache
                removeFromCache(entry.key);
            }
            head.incrementAndGet();
        }
    }
}
```

### 5. Network Layer Optimization

#### Thread-Per-Connection Model
```java
public class ThreadPerConnectionHandler {
    private final ExecutorService executor;
    private final PartitionedCacheEngine cache;
    
    public void handleConnection(SocketChannel channel) {
        executor.submit(() -> {
            // Assign thread to specific partition
            int partition = assignThreadToPartition();
            
            while (channel.isConnected()) {
                CacheCommand command = readCommand(channel);
                CacheResponse response = processCommand(command, partition);
                writeResponse(channel, response);
            }
        });
    }
    
    private CacheResponse processCommand(CacheCommand command, int partition) {
        // Direct access to thread's partition - no locks needed
        return cache.getPartition(partition).process(command);
    }
}
```

#### Event-Driven with Thread Affinity
```java
public class AffinityEventLoop {
    private final EventLoop[] eventLoops;
    private final int numEventLoops;
    
    public void processEvent(Channel channel, Object event) {
        int eventLoopIndex = channel.hashCode() % numEventLoops;
        EventLoop eventLoop = eventLoops[eventLoopIndex];
        
        // Ensure thread affinity
        if (eventLoop.inEventLoop()) {
            processEventDirectly(event);
        } else {
            eventLoop.execute(() -> processEventDirectly(event));
        }
    }
}
```

### 6. Implementation Phases

#### Phase 1: Thread Partitioning (Week 1-2)
- [ ] Implement hash-based partitioning
- [ ] Create `PartitionedCacheEngine`
- [ ] Add thread affinity support
- [ ] Benchmark against current implementation

#### Phase 2: Lock-Free Data Structures (Week 3-4)
- [ ] Implement lock-free hash table
- [ ] Create lock-free skip list for sorted sets
- [ ] Add atomic operations support
- [ ] Performance testing and optimization

#### Phase 3: Memory Management (Week 5-6)
- [ ] Implement slab allocator
- [ ] Add off-heap memory support
- [ ] Optimize memory layout
- [ ] Memory usage monitoring

#### Phase 4: Eviction and Cleanup (Week 7-8)
- [ ] Implement lock-free LRU
- [ ] Add background cleanup threads
- [ ] Optimize eviction algorithms
- [ ] Performance tuning

#### Phase 5: Network Layer (Week 9-10)
- [ ] Implement thread-per-connection model
- [ ] Add event-driven processing
- [ ] Optimize connection handling
- [ ] Load testing

### 7. Performance Expectations

#### Expected Improvements
```
Current FastCache (with locks):
- Throughput: ~100K ops/sec
- Latency: 1-5ms (variable due to locks)
- CPU Usage: 60-80%

Lock-Free FastCache:
- Throughput: ~500K ops/sec (5x improvement)
- Latency: 0.1-1ms (consistent)
- CPU Usage: 80-95% (better utilization)
```

#### Scalability Benefits
- **Linear scaling**: Performance scales with CPU cores
- **Predictable latency**: No lock contention
- **Higher throughput**: Reduced synchronization overhead
- **Better resource utilization**: More efficient CPU usage

### 8. Configuration Options

#### New Configuration Parameters
```properties
# Thread partitioning
cache.partitions=16
cache.thread.affinity=true
cache.partition.strategy=hash

# Memory management
cache.memory.allocator=slab
cache.memory.offheap=true
cache.memory.size=4GB

# Lock-free settings
cache.lockfree.enabled=true
cache.lockfree.retry.limit=1000
cache.lockfree.backoff.strategy=exponential
```

### 9. Migration Strategy

#### Gradual Migration
1. **Feature flag**: Enable/disable lock-free mode
2. **A/B testing**: Compare performance in production
3. **Rollback capability**: Quick fallback to locking version
4. **Monitoring**: Comprehensive metrics during transition

#### Backward Compatibility
- **Same API**: No changes to client code
- **Same protocol**: Existing clients continue to work
- **Configuration**: Optional migration path

### 10. Monitoring and Observability

#### New Metrics
```java
public class LockFreeMetrics {
    private final AtomicLong partitionHits;
    private final AtomicLong partitionMisses;
    private final AtomicLong retryCount;
    private final AtomicLong evictionCount;
    
    // Monitor lock-free performance
    public void recordPartitionAccess(int partition, boolean hit) {
        if (hit) {
            partitionHits.incrementAndGet();
        } else {
            partitionMisses.incrementAndGet();
        }
    }
}
```

#### Health Checks
- **Partition balance**: Ensure even distribution
- **Memory usage**: Monitor slab allocation efficiency
- **Retry rates**: Track lock-free operation success
- **Latency distribution**: Measure consistency improvements

## Conclusion

This lock-free architecture will transform FastCache from a lock-contended system to a highly scalable, predictable performance cache. The combination of thread partitioning, lock-free data structures, and optimized memory management will provide:

1. **5x throughput improvement**
2. **Consistent sub-millisecond latency**
3. **Linear scalability with CPU cores**
4. **Better resource utilization**
5. **Predictable performance characteristics**

The implementation follows proven patterns from Memcached and modern concurrent systems while maintaining FastCache's rich feature set. 