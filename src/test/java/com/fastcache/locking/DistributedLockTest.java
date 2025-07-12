package com.fastcache.locking;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.List;

/**
 * Unit tests for distributed locking functionality.
 */
public class DistributedLockTest {
    
    private DistributedLockManager node1;
    private DistributedLockManager node2;
    private DistributedLockManager node3;
    private LockConfig config;
    
    @BeforeAll
    static void setUpClass() {
        // Initialize shared registry once for all tests
        LockConfig config = LockConfig.builder()
            .defaultTtl(5000) // 5 seconds
            .renewalInterval(1000) // 1 second
            .maxRenewalCount(3)
            .lockTimeout(2000) // 2 seconds
            .build();
        SharedLockRegistry.getInstance(config);
    }
    
    @AfterAll
    static void tearDownClass() {
        SharedLockRegistry.getInstance().shutdown();
    }
    
    @BeforeEach
    void setUp() {
        SharedLockRegistry.resetInstance();
        config = LockConfig.builder()
            .defaultTtl(5000) // 5 seconds
            .renewalInterval(1000) // 1 second
            .maxRenewalCount(3)
            .lockTimeout(2000) // 2 seconds
            .build();
        
        // Initialize the shared registry with our config and clear it
        SharedLockRegistry.getInstance(config).clear();
        
        node1 = new DistributedLockManager("node-1", config);
        node2 = new DistributedLockManager("node-2", config);
        node3 = new DistributedLockManager("node-3", config);
    }
    
    @AfterEach
    void tearDown() {
        if (node1 != null) node1.shutdown();
        if (node2 != null) node2.shutdown();
        if (node3 != null) node3.shutdown();
    }
    
    @Test
    void testExclusiveLockAcquisition() throws InterruptedException {
        String resource = "test-resource";
        
        // Node 1 should be able to acquire exclusive lock
        LockEntry lock1 = node1.acquireExclusiveLock(resource);
        assertNotNull(lock1, "Node 1 should acquire exclusive lock");
        assertEquals("node-1", lock1.getOwner());
        assertEquals(LockEntry.LockType.EXCLUSIVE, lock1.getType());
        assertEquals(resource, lock1.getResource());
        assertEquals(LockEntry.LockState.ACQUIRED, lock1.getState());
        
        // Node 2 should not be able to acquire the same resource
        LockEntry lock2 = node2.acquireExclusiveLock(resource);
        assertNull(lock2, "Node 2 should not acquire lock when resource is locked");
        
        // Release lock
        boolean released = node1.releaseLock(lock1);
        assertTrue(released, "Lock should be released successfully");
        
        // Now Node 2 should be able to acquire the lock
        // Wait for the exclusive lock to be granted (poll for up to 500ms)
        long start = System.currentTimeMillis();
        do {
            lock2 = node2.acquireExclusiveLock(resource);
            if (lock2 != null) break;
            Thread.sleep(50);
        } while (System.currentTimeMillis() - start < 500);
        assertNotNull(lock2, "Node 2 should acquire lock after release");
        
        node2.releaseLock(lock2);
    }
    
    @Test
    void testSharedLockAcquisition() throws InterruptedException {
        String resource = "shared-resource";
        
        // Multiple nodes should be able to acquire shared locks
        LockEntry lock1 = node1.acquireSharedLock(resource);
        LockEntry lock2 = node2.acquireSharedLock(resource);
        LockEntry lock3 = node3.acquireSharedLock(resource);
        
        assertNotNull(lock1, "Node 1 should acquire shared lock");
        assertNotNull(lock2, "Node 2 should acquire shared lock");
        assertNotNull(lock3, "Node 3 should acquire shared lock");
        
        assertEquals(LockEntry.LockType.SHARED, lock1.getType());
        assertEquals(LockEntry.LockType.SHARED, lock2.getType());
        assertEquals(LockEntry.LockType.SHARED, lock3.getType());
        
        // Try to acquire exclusive lock (should fail)
        LockEntry exclusiveLock = node1.acquireExclusiveLock(resource);
        assertNull(exclusiveLock, "Exclusive lock should not be acquired when shared locks exist");
        
        // Release shared locks
        node1.releaseLock(lock1);
        node2.releaseLock(lock2);
        node3.releaseLock(lock3);
        
        // Now exclusive lock should work
        // Wait for the exclusive lock to be granted (poll for up to 500ms)
        long start = System.currentTimeMillis();
        do {
            exclusiveLock = node1.acquireExclusiveLock(resource);
            if (exclusiveLock != null) break;
            Thread.sleep(50);
        } while (System.currentTimeMillis() - start < 500);
        assertNotNull(exclusiveLock, "Exclusive lock should be acquired after shared locks released");
        
        node1.releaseLock(exclusiveLock);
    }
    
    @Test
    void testTryLock() {
        String resource = "try-lock-resource";
        
        // Node 1 acquires lock
        LockEntry lock1 = node1.acquireExclusiveLock(resource);
        assertNotNull(lock1);
        
        // Node 2 tries non-blocking lock (should fail immediately)
        LockEntry lock2 = node2.tryAcquireExclusiveLock(resource);
        assertNull(lock2, "Try lock should fail immediately when resource is locked");
        
        // Release lock
        node1.releaseLock(lock1);
        
        // Now try lock should succeed
        lock2 = node2.tryAcquireExclusiveLock(resource);
        assertNotNull(lock2, "Try lock should succeed when resource is available");
        
        node2.releaseLock(lock2);
    }
    
    @Test
    void testLockWithTimeout() throws Exception {
        String resource = "timeout-resource";
        
        // Node 1 acquires lock
        LockEntry lock1 = node1.acquireExclusiveLock(resource);
        assertNotNull(lock1);
        
        // Node 2 tries with timeout
        CompletableFuture<LockEntry> future = node2.acquireLockWithTimeout(
            resource, LockEntry.LockType.EXCLUSIVE, 3000, 1000);
        
        LockEntry lock2 = future.get(2, TimeUnit.SECONDS);
        assertNull(lock2, "Lock with timeout should fail when resource is locked");
        
        // Release Node 1 lock
        node1.releaseLock(lock1);
        
        // Now timeout lock should succeed
        future = node2.acquireLockWithTimeout(resource, LockEntry.LockType.EXCLUSIVE, 3000, 1000);
        lock2 = future.get(2, TimeUnit.SECONDS);
        assertNotNull(lock2, "Lock with timeout should succeed when resource is available");
        
        node2.releaseLock(lock2);
    }
    
    @Test
    void testLockRenewal() {
        String resource = "renewal-resource";
        
        LockEntry lock = node1.acquireExclusiveLock(resource, 2000); // 2 seconds TTL
        assertNotNull(lock);
        
        // Renew the lock
        boolean renewed = node1.renewLock(lock);
        assertTrue(renewed, "Lock should be renewed successfully");
        
        // Check renewal count
        assertTrue(lock.getRenewalCount() > 0, "Renewal count should be incremented");
        
        // Release lock for isolation
        node1.releaseLock(lock);
        assertFalse(node1.isLocked(resource));
    }
    
    @Test
    void testAutoRenewal() throws InterruptedException {
        String resource = "auto-renewal-resource";
        // Use a config with a higher maxRenewalCount for this test
        LockConfig localConfig = LockConfig.builder()
            .defaultTtl(3000)
            .renewalInterval(1000)
            .maxRenewalCount(10)
            .lockTimeout(2000)
            .build();
        DistributedLockManager localNode = new DistributedLockManager("node-1", localConfig);
        LockEntry lock = localNode.acquireExclusiveLock(resource, 3000); // 3 seconds TTL
        assertNotNull(lock);
        boolean renewalStarted = localNode.startAutoRenewal(lock);
        assertTrue(renewalStarted, "Auto-renewal should start successfully");
        for (int i = 0; i < 5; i++) {
            Thread.sleep(1000);
            System.out.println("[AutoRenewalTest] Lock expired? " + lock.isExpired() + ", renewalCount=" + lock.getRenewalCount());
        }
        assertFalse(lock.isExpired(), "Lock should not be expired due to auto-renewal");
        localNode.releaseLock(lock);
        assertFalse(localNode.isLocked(resource));
        localNode.shutdown();
    }
    
    @Test
    void testExecuteWithLock() {
        String resource = "execute-resource";
        
        // Execute task with exclusive lock
        String result = node1.executeWithExclusiveLock(resource, () -> {
            // Simulate some work
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "Task completed";
        });
        
        assertEquals("Task completed", result, "Task should execute successfully");
        
        // Verify lock is released
        assertFalse(node1.isLocked(resource), "Lock should be released after task execution");
        
        // Clean up for isolation
        node1.releaseLock(resource);
    }
    
    @Test
    void testLockExpiration() throws InterruptedException {
        String resource = "expiration-resource";
        
        LockEntry lock = node1.acquireExclusiveLock(resource, 1000); // 1 second TTL
        assertNotNull(lock);
        
        // Wait for expiration
        Thread.sleep(1500);
        
        assertTrue(lock.isExpired(), "Lock should be expired");
        assertEquals(0, lock.getRemainingTtl(), "Remaining TTL should be 0");
        
        // Try to renew expired lock
        boolean renewed = node1.renewLock(lock);
        assertFalse(renewed, "Expired lock should not be renewed");
        
        // Release lock for isolation (should be a no-op)
        node1.releaseLock(lock);
        assertFalse(node1.isLocked(resource));
    }
    
    @Test
    void testLockStatistics() {
        String resource = "stats-resource";
        
        // Get initial stats
        SharedLockRegistry.LockStats initialStats = node1.getStats();
        assertEquals(0, initialStats.getTotalLocks());
        assertEquals(0, initialStats.getActiveLocks());
        
        // Acquire some locks
        LockEntry lock1 = node1.acquireExclusiveLock(resource);
        LockEntry lock2 = node1.acquireSharedLock("another-resource");
        
        assertNotNull(lock1);
        assertNotNull(lock2);
        
        // Check updated stats
        SharedLockRegistry.LockStats updatedStats = node1.getStats();
        assertEquals(2, updatedStats.getTotalLocks());
        assertEquals(2, updatedStats.getActiveLocks());
        
        // Release locks
        node1.releaseLock(lock1);
        node1.releaseLock(lock2);
        
        // Check final stats
        SharedLockRegistry.LockStats finalStats = node1.getStats();
        assertEquals(0, finalStats.getTotalLocks());
        assertEquals(0, finalStats.getActiveLocks());
    }
    
    @Test
    void testGetLocks() {
        String resource = "get-locks-resource";
        
        // Acquire multiple locks on the same resource
        LockEntry lock1 = node1.acquireSharedLock(resource);
        LockEntry lock2 = node2.acquireSharedLock(resource);
        
        assertNotNull(lock1);
        assertNotNull(lock2);
        
        // Get locks for the resource
        List<LockInfo> locks = node1.getLocks(resource);
        assertEquals(2, locks.size(), "Should return 2 locks for the resource");
        
        // Verify lock information
        boolean foundLock1 = locks.stream().anyMatch(info -> info.getLockId().equals(lock1.getLockId()));
        boolean foundLock2 = locks.stream().anyMatch(info -> info.getLockId().equals(lock2.getLockId()));
        
        assertTrue(foundLock1, "Should find lock1 in the list");
        assertTrue(foundLock2, "Should find lock2 in the list");
        
        // Release locks
        node1.releaseLock(lock1);
        node2.releaseLock(lock2);
    }
    
    @Test
    void testLockCompatibility() {
        String resource = "compatibility-resource";
        
        // Create locks for compatibility testing
        LockEntry exclusiveLock = new LockEntry("exclusive", resource, "owner", LockEntry.LockType.EXCLUSIVE, 5000);
        LockEntry sharedLock1 = new LockEntry("shared1", resource, "owner", LockEntry.LockType.SHARED, 5000);
        LockEntry sharedLock2 = new LockEntry("shared2", resource, "owner", LockEntry.LockType.SHARED, 5000);
        
        // Test compatibility
        assertTrue(sharedLock1.isCompatibleWith(sharedLock2), "Shared locks should be compatible");
        assertFalse(exclusiveLock.isCompatibleWith(sharedLock1), "Exclusive lock should not be compatible with shared lock");
        assertFalse(sharedLock1.isCompatibleWith(exclusiveLock), "Shared lock should not be compatible with exclusive lock");
        assertFalse(exclusiveLock.isCompatibleWith(exclusiveLock), "Exclusive locks should not be compatible");
        
        // Test conflicts
        assertTrue(exclusiveLock.conflictsWith(sharedLock1), "Exclusive lock should conflict with shared lock");
        assertFalse(sharedLock1.conflictsWith(sharedLock2), "Shared locks should not conflict");
    }
    
    @Test
    void testLockOwnerVerification() {
        String resource = "owner-resource";
        
        LockEntry lock = node1.acquireExclusiveLock(resource);
        assertNotNull(lock);
        
        // Try to release with wrong owner
        boolean released = node2.releaseLock(lock.getLockId());
        assertFalse(released, "Should not release lock with wrong owner");
        
        // Release with correct owner
        released = node1.releaseLock(lock);
        assertTrue(released, "Should release lock with correct owner");
        
        // Ensure lock is released for isolation
        assertFalse(node1.isLocked(resource));
    }
    
    @Test
    void testLockIdGeneration() {
        String resource = "id-resource";
        LockEntry lock1 = node1.acquireExclusiveLock(resource);
        assertNotNull(lock1);
        assertTrue(lock1.getLockId().contains("node-1"), "Lock ID should contain owner");
        LockEntry lock2 = node2.acquireExclusiveLock(resource + "-2");
        assertNotNull(lock2);
        assertTrue(lock2.getLockId().contains("node-2"), "Lock ID should contain owner");
    }

    @Test
    void testDebugLockAcquisition() {
        System.out.println("=== Starting debug test ===");
        String resource = "debug-resource";
        
        // Clear any existing locks
        SharedLockRegistry.getInstance().clear();
        System.out.println("Debug: Cleared registry");
        
        // Check if registry is working
        assertNotNull(SharedLockRegistry.getInstance(), "Registry should not be null");
        System.out.println("Debug: Registry is not null");
        
        // Try to acquire a lock
        System.out.println("Debug: About to acquire lock for resource: " + resource);
        LockEntry lock = node1.acquireExclusiveLock(resource);
        System.out.println("Debug: Lock acquired: " + lock);
        
        if (lock == null) {
            // Check what's in the registry
            List<LockInfo> allLocks = SharedLockRegistry.getInstance().getAllLocks();
            System.out.println("Debug: All locks in registry: " + allLocks);
            
            // Check if resource is locked
            boolean isLocked = SharedLockRegistry.getInstance().isLocked(resource);
            System.out.println("Debug: Is resource locked: " + isLocked);
        }
        
        assertNotNull(lock, "Lock should be acquired");
        System.out.println("=== Debug test completed ===");
    }
} 