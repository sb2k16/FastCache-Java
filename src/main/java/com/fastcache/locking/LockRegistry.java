package com.fastcache.locking;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Registry for managing distributed locks across the cluster.
 */
public class LockRegistry {
    private final Map<String, LockEntry> locks = new ConcurrentHashMap<>();
    private final Map<String, Queue<LockRequest>> waitingQueue = new ConcurrentHashMap<>();
    private final ReadWriteLock registryLock = new ReentrantReadWriteLock();
    private final ScheduledExecutorService cleanupExecutor;
    private final LockConfig config;
    
    public LockRegistry(LockConfig config) {
        this.config = config;
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lock-cleanup");
            t.setDaemon(true);
            return t;
        });
        
        // Schedule periodic cleanup of expired locks
        this.cleanupExecutor.scheduleAtFixedRate(
            this::cleanupExpiredLocks, 
            config.getCleanupInterval(), 
            config.getCleanupInterval(), 
            TimeUnit.MILLISECONDS
        );
        
        System.out.println("Lock registry initialized with config: " + config);
    }
    
    /**
     * Acquires a lock for the specified resource.
     * @param resource The resource to lock
     * @param owner The lock owner
     * @param type The lock type
     * @param ttl Time to live in milliseconds
     * @return LockEntry if acquired, null if failed
     */
    public LockEntry acquireLock(String resource, String owner, LockEntry.LockType type, long ttl) {
        String lockId = generateLockId(resource, owner);
        
        registryLock.writeLock().lock();
        try {
            // Check if lock can be acquired
            if (canAcquireLock(resource, type)) {
                LockEntry lockEntry = new LockEntry(lockId, resource, owner, type, ttl);
                locks.put(lockId, lockEntry);
                System.out.println("Lock acquired: " + lockEntry);
                return lockEntry;
            }
            
            // Add to waiting queue
            LockRequest request = new LockRequest(lockId, resource, owner, type, ttl);
            waitingQueue.computeIfAbsent(resource, k -> new LinkedList<>()).add(request);
            System.out.println("Lock request queued: " + request);
            return null;
        } finally {
            registryLock.writeLock().unlock();
        }
    }
    
    /**
     * Tries to acquire a lock without blocking.
     * @param resource The resource to lock
     * @param owner The lock owner
     * @param type The lock type
     * @param ttl Time to live in milliseconds
     * @return LockEntry if acquired, null if failed
     */
    public LockEntry tryAcquireLock(String resource, String owner, LockEntry.LockType type, long ttl) {
        String lockId = generateLockId(resource, owner);
        
        registryLock.readLock().lock();
        try {
            if (canAcquireLock(resource, type)) {
                LockEntry lockEntry = new LockEntry(lockId, resource, owner, type, ttl);
                locks.put(lockId, lockEntry);
                System.out.println("Lock acquired (try): " + lockEntry);
                return lockEntry;
            }
            return null;
        } finally {
            registryLock.readLock().unlock();
        }
    }
    
    /**
     * Releases a lock.
     * @param lockId The lock ID to release
     * @param owner The lock owner (for verification)
     * @return true if released, false if not found or not owner
     */
    public boolean releaseLock(String lockId, String owner) {
        registryLock.writeLock().lock();
        try {
            LockEntry lockEntry = locks.get(lockId);
            if (lockEntry == null) {
                System.out.println("Lock not found for release: " + lockId);
                return false;
            }
            
            if (!lockEntry.getOwner().equals(owner)) {
                System.out.println("Lock owner mismatch for release: " + lockId + ", expected: " + owner + ", actual: " + lockEntry.getOwner());
                return false;
            }
            
            lockEntry.setState(LockEntry.LockState.RELEASED);
            locks.remove(lockId);
            System.out.println("Lock released: " + lockEntry);
            
            // Process waiting queue
            processWaitingQueue(lockEntry.getResource());
            
            return true;
        } finally {
            registryLock.writeLock().unlock();
        }
    }
    
    /**
     * Renews a lock.
     * @param lockId The lock ID to renew
     * @param owner The lock owner (for verification)
     * @return true if renewed, false if not found or not owner
     */
    public boolean renewLock(String lockId, String owner) {
        registryLock.readLock().lock();
        try {
            LockEntry lockEntry = locks.get(lockId);
            if (lockEntry == null) {
                return false;
            }
            
            if (!lockEntry.getOwner().equals(owner)) {
                return false;
            }
            
            if (lockEntry.isExpired()) {
                lockEntry.setState(LockEntry.LockState.EXPIRED);
                locks.remove(lockId);
                return false;
            }
            
            lockEntry.renew();
            System.out.println("Lock renewed: " + lockEntry);
            return true;
        } finally {
            registryLock.readLock().unlock();
        }
    }
    
    /**
     * Checks if a resource is locked.
     * @param resource The resource to check
     * @return true if locked, false otherwise
     */
    public boolean isLocked(String resource) {
        registryLock.readLock().lock();
        try {
            return locks.values().stream()
                .anyMatch(lock -> lock.getResource().equals(resource) && 
                                lock.getState() == LockEntry.LockState.ACQUIRED && 
                                !lock.isExpired());
        } finally {
            registryLock.readLock().unlock();
        }
    }
    
    /**
     * Gets all locks for a resource.
     * @param resource The resource
     * @return List of lock information
     */
    public List<LockInfo> getLocks(String resource) {
        registryLock.readLock().lock();
        try {
            return locks.values().stream()
                .filter(lock -> lock.getResource().equals(resource))
                .map(LockInfo::new)
                .collect(Collectors.toList());
        } finally {
            registryLock.readLock().unlock();
        }
    }
    
    /**
     * Gets all active locks.
     * @return List of all active lock information
     */
    public List<LockInfo> getAllLocks() {
        registryLock.readLock().lock();
        try {
            return locks.values().stream()
                .map(LockInfo::new)
                .collect(Collectors.toList());
        } finally {
            registryLock.readLock().unlock();
        }
    }
    
    /**
     * Gets lock statistics.
     * @return Lock statistics
     */
    public LockStats getStats() {
        registryLock.readLock().lock();
        try {
            long totalLocks = locks.size();
            long activeLocks = locks.values().stream()
                .filter(lock -> lock.getState() == LockEntry.LockState.ACQUIRED && !lock.isExpired())
                .count();
            long expiredLocks = locks.values().stream()
                .filter(LockEntry::isExpired)
                .count();
            long waitingRequests = waitingQueue.values().stream()
                .mapToInt(Queue::size)
                .sum();
            
            return new LockStats(totalLocks, activeLocks, expiredLocks, waitingRequests);
        } finally {
            registryLock.readLock().unlock();
        }
    }
    
    /**
     * Shuts down the lock registry.
     */
    public void shutdown() {
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        System.out.println("Lock registry shutdown complete");
    }
    
    // Private helper methods
    
    private boolean canAcquireLock(String resource, LockEntry.LockType type) {
        // Check if there are any conflicting locks
        for (LockEntry existingLock : locks.values()) {
            if (existingLock.getResource().equals(resource) && 
                existingLock.getState() == LockEntry.LockState.ACQUIRED && 
                !existingLock.isExpired()) {
                
                // Check compatibility
                LockEntry newLock = new LockEntry("temp", resource, "temp", type, 0);
                if (newLock.conflictsWith(existingLock)) {
                    return false;
                }
            }
        }
        return true;
    }
    
    private void processWaitingQueue(String resource) {
        Queue<LockRequest> queue = waitingQueue.get(resource);
        if (queue == null || queue.isEmpty()) {
            return;
        }
        
        Iterator<LockRequest> iterator = queue.iterator();
        while (iterator.hasNext()) {
            LockRequest request = iterator.next();
            if (canAcquireLock(request.resource, request.type)) {
                LockEntry lockEntry = new LockEntry(request.lockId, request.resource, request.owner, request.type, request.ttl);
                locks.put(request.lockId, lockEntry);
                iterator.remove();
                System.out.println("Queued lock acquired: " + lockEntry);
                break; // Only process one request at a time
            }
        }
        
        // Remove empty queue
        if (queue.isEmpty()) {
            waitingQueue.remove(resource);
        }
    }
    
    private void cleanupExpiredLocks() {
        registryLock.writeLock().lock();
        try {
            List<String> expiredLockIds = locks.values().stream()
                .filter(LockEntry::isExpired)
                .map(LockEntry::getLockId)
                .collect(Collectors.toList());
            
            for (String lockId : expiredLockIds) {
                LockEntry lockEntry = locks.remove(lockId);
                if (lockEntry != null) {
                    lockEntry.setState(LockEntry.LockState.EXPIRED);
                    System.out.println("Expired lock cleaned up: " + lockEntry);
                    
                    // Process waiting queue for this resource
                    processWaitingQueue(lockEntry.getResource());
                }
            }
            
            if (!expiredLockIds.isEmpty()) {
                System.out.println("Cleaned up " + expiredLockIds.size() + " expired locks");
            }
        } finally {
            registryLock.writeLock().unlock();
        }
    }
    
    private String generateLockId(String resource, String owner) {
        return resource + ":" + owner + ":" + System.currentTimeMillis() + ":" + UUID.randomUUID().toString().substring(0, 8);
    }
    
    // Inner classes
    
    private static class LockRequest {
        final String lockId;
        final String resource;
        final String owner;
        final LockEntry.LockType type;
        final long ttl;
        
        LockRequest(String lockId, String resource, String owner, LockEntry.LockType type, long ttl) {
            this.lockId = lockId;
            this.resource = resource;
            this.owner = owner;
            this.type = type;
            this.ttl = ttl;
        }
        
        @Override
        public String toString() {
            return String.format("LockRequest{lockId='%s', resource='%s', owner='%s', type=%s, ttl=%dms}",
                    lockId, resource, owner, type, ttl);
        }
    }
    
    public static class LockStats {
        private final long totalLocks;
        private final long activeLocks;
        private final long expiredLocks;
        private final long waitingRequests;
        
        public LockStats(long totalLocks, long activeLocks, long expiredLocks, long waitingRequests) {
            this.totalLocks = totalLocks;
            this.activeLocks = activeLocks;
            this.expiredLocks = expiredLocks;
            this.waitingRequests = waitingRequests;
        }
        
        public long getTotalLocks() { return totalLocks; }
        public long getActiveLocks() { return activeLocks; }
        public long getExpiredLocks() { return expiredLocks; }
        public long getWaitingRequests() { return waitingRequests; }
        
        @Override
        public String toString() {
            return String.format("LockStats{total=%d, active=%d, expired=%d, waiting=%d}",
                    totalLocks, activeLocks, expiredLocks, waitingRequests);
        }
    }
} 