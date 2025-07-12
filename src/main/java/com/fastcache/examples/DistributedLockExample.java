package com.fastcache.examples;

import com.fastcache.locking.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Example demonstrating distributed locking functionality.
 */
public class DistributedLockExample {
    
    public static void main(String[] args) {
        System.out.println("=== Distributed Lock Example ===\n");
        
        // Create lock configuration
        LockConfig config = LockConfig.builder()
            .defaultTtl(10000) // 10 seconds
            .renewalInterval(3000) // 3 seconds
            .maxRenewalCount(5)
            .lockTimeout(5000) // 5 seconds
            .build();
        
        // Create lock managers for different nodes
        DistributedLockManager node1 = new DistributedLockManager("node-1", config);
        DistributedLockManager node2 = new DistributedLockManager("node-2", config);
        DistributedLockManager node3 = new DistributedLockManager("node-3", config);
        
        try {
            // Example 1: Basic exclusive lock
            System.out.println("1. Basic Exclusive Lock Example:");
            basicExclusiveLockExample(node1, node2);
            
            // Example 2: Shared locks
            System.out.println("\n2. Shared Lock Example:");
            sharedLockExample(node1, node2, node3);
            
            // Example 3: Try lock (non-blocking)
            System.out.println("\n3. Try Lock Example:");
            tryLockExample(node1, node2);
            
            // Example 4: Lock with timeout
            System.out.println("\n4. Lock with Timeout Example:");
            lockWithTimeoutExample(node1, node2);
            
            // Example 5: Auto-renewal
            System.out.println("\n5. Auto-Renewal Example:");
            autoRenewalExample(node1);
            
            // Example 6: Execute with lock
            System.out.println("\n6. Execute with Lock Example:");
            executeWithLockExample(node1, node2);
            
            // Example 7: Lock statistics
            System.out.println("\n7. Lock Statistics:");
            printLockStatistics(node1);
            
        } finally {
            // Cleanup
            node1.shutdown();
            node2.shutdown();
            node3.shutdown();
        }
    }
    
    private static void basicExclusiveLockExample(DistributedLockManager node1, DistributedLockManager node2) {
        String resource = "database-connection";
        
        // Node 1 acquires exclusive lock
        LockEntry lock1 = node1.acquireExclusiveLock(resource);
        System.out.println("Node 1 acquired lock: " + (lock1 != null ? "SUCCESS" : "FAILED"));
        
        // Node 2 tries to acquire the same resource (should fail)
        LockEntry lock2 = node2.acquireExclusiveLock(resource);
        System.out.println("Node 2 acquired lock: " + (lock2 != null ? "SUCCESS" : "FAILED"));
        
        // Node 1 releases the lock
        if (lock1 != null) {
            boolean released = node1.releaseLock(lock1);
            System.out.println("Node 1 released lock: " + (released ? "SUCCESS" : "FAILED"));
        }
        
        // Now Node 2 should be able to acquire the lock
        lock2 = node2.acquireExclusiveLock(resource);
        System.out.println("Node 2 acquired lock after release: " + (lock2 != null ? "SUCCESS" : "FAILED"));
        
        if (lock2 != null) {
            node2.releaseLock(lock2);
        }
    }
    
    private static void sharedLockExample(DistributedLockManager node1, DistributedLockManager node2, DistributedLockManager node3) {
        String resource = "read-only-data";
        
        // Multiple nodes can acquire shared locks
        LockEntry lock1 = node1.acquireSharedLock(resource);
        LockEntry lock2 = node2.acquireSharedLock(resource);
        LockEntry lock3 = node3.acquireSharedLock(resource);
        
        System.out.println("Node 1 shared lock: " + (lock1 != null ? "SUCCESS" : "FAILED"));
        System.out.println("Node 2 shared lock: " + (lock2 != null ? "SUCCESS" : "FAILED"));
        System.out.println("Node 3 shared lock: " + (lock3 != null ? "SUCCESS" : "FAILED"));
        
        // Try to acquire exclusive lock (should fail)
        LockEntry exclusiveLock = node1.acquireExclusiveLock(resource);
        System.out.println("Exclusive lock with shared locks: " + (exclusiveLock != null ? "SUCCESS" : "FAILED"));
        
        // Release shared locks
        if (lock1 != null) node1.releaseLock(lock1);
        if (lock2 != null) node2.releaseLock(lock2);
        if (lock3 != null) node3.releaseLock(lock3);
        
        // Now exclusive lock should work
        exclusiveLock = node1.acquireExclusiveLock(resource);
        System.out.println("Exclusive lock after shared locks released: " + (exclusiveLock != null ? "SUCCESS" : "FAILED"));
        
        if (exclusiveLock != null) {
            node1.releaseLock(exclusiveLock);
        }
    }
    
    private static void tryLockExample(DistributedLockManager node1, DistributedLockManager node2) {
        String resource = "try-lock-resource";
        
        // Node 1 acquires lock
        LockEntry lock1 = node1.acquireExclusiveLock(resource);
        System.out.println("Node 1 acquired lock: " + (lock1 != null ? "SUCCESS" : "FAILED"));
        
        // Node 2 tries non-blocking lock (should fail immediately)
        LockEntry lock2 = node2.tryAcquireExclusiveLock(resource);
        System.out.println("Node 2 try lock: " + (lock2 != null ? "SUCCESS" : "FAILED"));
        
        // Release lock
        if (lock1 != null) {
            node1.releaseLock(lock1);
        }
        
        // Now try lock should succeed
        lock2 = node2.tryAcquireExclusiveLock(resource);
        System.out.println("Node 2 try lock after release: " + (lock2 != null ? "SUCCESS" : "FAILED"));
        
        if (lock2 != null) {
            node2.releaseLock(lock2);
        }
    }
    
    private static void lockWithTimeoutExample(DistributedLockManager node1, DistributedLockManager node2) {
        String resource = "timeout-resource";
        
        // Node 1 acquires lock
        LockEntry lock1 = node1.acquireExclusiveLock(resource);
        System.out.println("Node 1 acquired lock: " + (lock1 != null ? "SUCCESS" : "FAILED"));
        
        // Node 2 tries with timeout
        CompletableFuture<LockEntry> future = node2.acquireLockWithTimeout(
            resource, LockEntry.LockType.EXCLUSIVE, 5000, 3000);
        
        try {
            LockEntry lock2 = future.get(4, TimeUnit.SECONDS);
            System.out.println("Node 2 timeout lock: " + (lock2 != null ? "SUCCESS" : "FAILED"));
            
            if (lock2 != null) {
                node2.releaseLock(lock2);
            }
        } catch (Exception e) {
            System.out.println("Node 2 timeout lock: TIMEOUT");
        }
        
        // Release Node 1 lock
        if (lock1 != null) {
            node1.releaseLock(lock1);
        }
    }
    
    private static void autoRenewalExample(DistributedLockManager node1) {
        String resource = "auto-renewal-resource";
        
        // Acquire lock with short TTL
        LockEntry lock = node1.acquireExclusiveLock(resource, 5000); // 5 seconds
        System.out.println("Acquired lock for auto-renewal: " + (lock != null ? "SUCCESS" : "FAILED"));
        
        if (lock != null) {
            // Start auto-renewal
            boolean renewalStarted = node1.startAutoRenewal(lock);
            System.out.println("Auto-renewal started: " + (renewalStarted ? "SUCCESS" : "FAILED"));
            
            // Keep the lock for a while
            try {
                Thread.sleep(15000); // 15 seconds
                System.out.println("Lock held for 15 seconds with auto-renewal");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            // Release lock
            node1.releaseLock(lock);
            System.out.println("Lock released after auto-renewal");
        }
    }
    
    private static void executeWithLockExample(DistributedLockManager node1, DistributedLockManager node2) {
        String resource = "execute-resource";
        
        // Execute task with exclusive lock
        String result = node1.executeWithExclusiveLock(resource, () -> {
            System.out.println("Executing critical section with exclusive lock");
            try {
                Thread.sleep(2000); // Simulate work
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "Task completed successfully";
        });
        
        System.out.println("Execute with lock result: " + result);
        
        // Try to execute on another node while first is running
        String result2 = node2.executeWithExclusiveLock(resource, () -> {
            System.out.println("This should not execute if lock is working properly");
            return "This should be null";
        });
        
        System.out.println("Second execute result: " + result2);
    }
    
    private static void printLockStatistics(DistributedLockManager node) {
        SharedLockRegistry.LockStats stats = node.getStats();
        System.out.println("Lock Statistics: " + stats);
        
        System.out.println("All locks:");
        node.getAllLocks().forEach(lockInfo -> 
            System.out.println("  " + lockInfo));
    }
} 