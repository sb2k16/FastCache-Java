package com.fastcache.locking;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Distributed lock manager providing high-level API for distributed locking.
 */
public class DistributedLockManager {
    private final SharedLockRegistry lockRegistry;
    private final LockConfig config;
    private final ScheduledExecutorService renewalExecutor;
    private final String nodeId;
    
    public DistributedLockManager(String nodeId, LockConfig config) {
        this.nodeId = nodeId;
        this.config = config;
        this.lockRegistry = SharedLockRegistry.getInstance(config);
        this.renewalExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lock-renewal");
            t.setDaemon(true);
            return t;
        });
        
        System.out.println("Distributed lock manager initialized for node: " + nodeId);
    }
    
    /**
     * Acquires an exclusive lock with default TTL.
     * @param resource The resource to lock
     * @return LockEntry if acquired, null if failed
     */
    public LockEntry acquireExclusiveLock(String resource) {
        // Check for already granted lock for this node/resource
        for (LockInfo info : lockRegistry.getLocks(resource)) {
            if (info.getOwner().equals(nodeId) && info.getType() == LockEntry.LockType.EXCLUSIVE && info.getState() == LockEntry.LockState.ACQUIRED && !info.isExpired()) {
                return new LockEntry(info.getLockId(), info.getResource(), info.getOwner(), info.getType(), info.getTtl());
            }
        }
        return acquireExclusiveLock(resource, config.getDefaultTtl());
    }
    
    /**
     * Acquires an exclusive lock with specified TTL.
     * @param resource The resource to lock
     * @param ttl Time to live in milliseconds
     * @return LockEntry if acquired, null if failed
     */
    public LockEntry acquireExclusiveLock(String resource, long ttl) {
        // Check for already granted lock for this node/resource
        for (LockInfo info : lockRegistry.getLocks(resource)) {
            if (info.getOwner().equals(nodeId) && info.getType() == LockEntry.LockType.EXCLUSIVE && info.getState() == LockEntry.LockState.ACQUIRED && !info.isExpired()) {
                return new LockEntry(info.getLockId(), info.getResource(), info.getOwner(), info.getType(), info.getTtl());
            }
        }
        return lockRegistry.acquireLock(resource, nodeId, LockEntry.LockType.EXCLUSIVE, ttl);
    }
    
    /**
     * Acquires a shared lock with default TTL.
     * @param resource The resource to lock
     * @return LockEntry if acquired, null if failed
     */
    public LockEntry acquireSharedLock(String resource) {
        // Check for already granted lock for this node/resource
        for (LockInfo info : lockRegistry.getLocks(resource)) {
            if (info.getOwner().equals(nodeId) && info.getType() == LockEntry.LockType.SHARED && info.getState() == LockEntry.LockState.ACQUIRED && !info.isExpired()) {
                return new LockEntry(info.getLockId(), info.getResource(), info.getOwner(), info.getType(), info.getTtl());
            }
        }
        return acquireSharedLock(resource, config.getDefaultTtl());
    }
    
    /**
     * Acquires a shared lock with specified TTL.
     * @param resource The resource to lock
     * @param ttl Time to live in milliseconds
     * @return LockEntry if acquired, null if failed
     */
    public LockEntry acquireSharedLock(String resource, long ttl) {
        // Check for already granted lock for this node/resource
        for (LockInfo info : lockRegistry.getLocks(resource)) {
            if (info.getOwner().equals(nodeId) && info.getType() == LockEntry.LockType.SHARED && info.getState() == LockEntry.LockState.ACQUIRED && !info.isExpired()) {
                return new LockEntry(info.getLockId(), info.getResource(), info.getOwner(), info.getType(), info.getTtl());
            }
        }
        return lockRegistry.acquireLock(resource, nodeId, LockEntry.LockType.SHARED, ttl);
    }
    
    /**
     * Tries to acquire an exclusive lock without blocking.
     * @param resource The resource to lock
     * @return LockEntry if acquired, null if failed
     */
    public LockEntry tryAcquireExclusiveLock(String resource) {
        return tryAcquireExclusiveLock(resource, config.getDefaultTtl());
    }
    
    /**
     * Tries to acquire an exclusive lock without blocking.
     * @param resource The resource to lock
     * @param ttl Time to live in milliseconds
     * @return LockEntry if acquired, null if failed
     */
    public LockEntry tryAcquireExclusiveLock(String resource, long ttl) {
        return lockRegistry.tryAcquireLock(resource, nodeId, LockEntry.LockType.EXCLUSIVE, ttl);
    }
    
    /**
     * Tries to acquire a shared lock without blocking.
     * @param resource The resource to lock
     * @return LockEntry if acquired, null if failed
     */
    public LockEntry tryAcquireSharedLock(String resource) {
        return tryAcquireSharedLock(resource, config.getDefaultTtl());
    }
    
    /**
     * Tries to acquire a shared lock without blocking.
     * @param resource The resource to lock
     * @param ttl Time to live in milliseconds
     * @return LockEntry if acquired, null if failed
     */
    public LockEntry tryAcquireSharedLock(String resource, long ttl) {
        return lockRegistry.tryAcquireLock(resource, nodeId, LockEntry.LockType.SHARED, ttl);
    }
    
    /**
     * Acquires a lock with timeout.
     * @param resource The resource to lock
     * @param type The lock type
     * @param ttl Time to live in milliseconds
     * @param timeout Timeout for acquisition in milliseconds
     * @return CompletableFuture that completes with LockEntry or null
     */
    public CompletableFuture<LockEntry> acquireLockWithTimeout(String resource, LockEntry.LockType type, long ttl, long timeout) {
        CompletableFuture<LockEntry> future = new CompletableFuture<>();
        
        // Try immediate acquisition first
        LockEntry lock = lockRegistry.tryAcquireLock(resource, nodeId, type, ttl);
        if (lock != null) {
            future.complete(lock);
            return future;
        }
        
        // Schedule periodic attempts
        ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor();
        long startTime = System.currentTimeMillis();
        
        timeoutExecutor.scheduleAtFixedRate(() -> {
            try {
                // Check timeout
                if (System.currentTimeMillis() - startTime > timeout) {
                    future.complete(null);
                    timeoutExecutor.shutdown();
                    return;
                }
                
                // Try to acquire lock
                LockEntry acquiredLock = lockRegistry.tryAcquireLock(resource, nodeId, type, ttl);
                if (acquiredLock != null) {
                    future.complete(acquiredLock);
                    timeoutExecutor.shutdown();
                }
            } catch (Exception e) {
                future.completeExceptionally(e);
                timeoutExecutor.shutdown();
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
        
        // Set timeout
        timeoutExecutor.schedule(() -> {
            if (!future.isDone()) {
                future.complete(null);
            }
            timeoutExecutor.shutdown();
        }, timeout, TimeUnit.MILLISECONDS);
        
        return future;
    }
    
    /**
     * Releases a lock.
     * @param lockEntry The lock entry to release
     * @return true if released, false if failed
     */
    public boolean releaseLock(LockEntry lockEntry) {
        if (lockEntry == null) {
            return false;
        }
        return lockRegistry.releaseLock(lockEntry.getLockId(), nodeId);
    }
    
    /**
     * Releases a lock by ID.
     * @param lockId The lock ID to release
     * @return true if released, false if failed
     */
    public boolean releaseLock(String lockId) {
        return lockRegistry.releaseLock(lockId, nodeId);
    }
    
    /**
     * Renews a lock.
     * @param lockEntry The lock entry to renew
     * @return true if renewed, false if failed
     */
    public boolean renewLock(LockEntry lockEntry) {
        if (lockEntry == null) {
            return false;
        }
        return lockRegistry.renewLock(lockEntry.getLockId(), nodeId);
    }
    
    /**
     * Starts automatic lock renewal for a lock.
     * @param lockEntry The lock entry to renew automatically
     * @return true if renewal started, false if failed
     */
    public boolean startAutoRenewal(LockEntry lockEntry) {
        if (lockEntry == null || lockEntry.getRenewalCount() >= config.getMaxRenewalCount()) {
            return false;
        }
        
        renewalExecutor.scheduleAtFixedRate(() -> {
            try {
                if (!renewLock(lockEntry)) {
                    System.out.println("Failed to renew lock: " + lockEntry.getLockId());
                }
            } catch (Exception e) {
                System.err.println("Error during lock renewal: " + e.getMessage());
            }
        }, config.getRenewalInterval(), config.getRenewalInterval(), TimeUnit.MILLISECONDS);
        
        System.out.println("Started auto-renewal for lock: " + lockEntry.getLockId());
        return true;
    }
    
    /**
     * Checks if a resource is locked.
     * @param resource The resource to check
     * @return true if locked, false otherwise
     */
    public boolean isLocked(String resource) {
        return lockRegistry.isLocked(resource);
    }
    
    /**
     * Gets all locks for a resource.
     * @param resource The resource
     * @return List of lock information
     */
    public List<LockInfo> getLocks(String resource) {
        return lockRegistry.getLocks(resource);
    }
    
    /**
     * Gets all active locks.
     * @return List of all active lock information
     */
    public List<LockInfo> getAllLocks() {
        return lockRegistry.getAllLocks();
    }
    
    /**
     * Gets lock statistics.
     * @return Lock statistics
     */
    public SharedLockRegistry.LockStats getStats() {
        return lockRegistry.getStats();
    }
    
    /**
     * Executes a task with an exclusive lock.
     * @param resource The resource to lock
     * @param task The task to execute
     * @param <T> The return type
     * @return The result of the task, or null if lock acquisition failed
     */
    public <T> T executeWithExclusiveLock(String resource, LockTask<T> task) {
        return executeWithLock(resource, LockEntry.LockType.EXCLUSIVE, task);
    }
    
    /**
     * Executes a task with a shared lock.
     * @param resource The resource to lock
     * @param task The task to execute
     * @param <T> The return type
     * @return The result of the task, or null if lock acquisition failed
     */
    public <T> T executeWithSharedLock(String resource, LockTask<T> task) {
        return executeWithLock(resource, LockEntry.LockType.SHARED, task);
    }
    
    /**
     * Executes a task with a lock.
     * @param resource The resource to lock
     * @param type The lock type
     * @param task The task to execute
     * @param <T> The return type
     * @return The result of the task, or null if lock acquisition failed
     */
    public <T> T executeWithLock(String resource, LockEntry.LockType type, LockTask<T> task) {
        LockEntry lock = lockRegistry.acquireLock(resource, nodeId, type, config.getDefaultTtl());
        if (lock == null) {
            return null;
        }
        
        try {
            return task.execute();
        } finally {
            releaseLock(lock);
        }
    }
    
    /**
     * Shuts down the lock manager.
     */
    public void shutdown() {
        renewalExecutor.shutdown();
        try {
            if (!renewalExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                renewalExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            renewalExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        lockRegistry.shutdown();
        System.out.println("Distributed lock manager shutdown complete");
    }
    
    /**
     * Functional interface for tasks that require a lock.
     * @param <T> The return type
     */
    @FunctionalInterface
    public interface LockTask<T> {
        T execute();
    }
} 