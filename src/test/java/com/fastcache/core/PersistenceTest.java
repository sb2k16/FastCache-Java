package com.fastcache.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for persistence functionality.
 */
@DisplayName("Persistence Tests")
public class PersistenceTest {

    @TempDir
    Path tempDir;
    
    private String dataDir;
    private String nodeId;
    
    @BeforeEach
    void setUp() {
        dataDir = tempDir.toString();
        nodeId = "test-node";
    }
    
    @Test
    @DisplayName("Should create persistent cache engine")
    void shouldCreatePersistentCacheEngine() throws IOException {
        PersistentCacheEngine cache = new PersistentCacheEngine(dataDir, nodeId, 1000, new EvictionPolicy.LRU());
        
        // Add some data
        cache.set("key1", "value1", CacheEntry.EntryType.STRING);
        cache.set("key2", "value2", CacheEntry.EntryType.STRING);
        
        // Verify data is in memory
        assertEquals("value1", cache.get("key1"));
        assertEquals("value2", cache.get("key2"));
        
        cache.shutdown();
    }
    
    @Test
    @DisplayName("Should persist data across restarts")
    void shouldPersistDataAcrossRestarts() throws IOException {
        // Create cache and add data
        PersistentCacheEngine cache1 = new PersistentCacheEngine(dataDir, nodeId, 1000, new EvictionPolicy.LRU());
        cache1.set("key1", "value1", CacheEntry.EntryType.STRING);
        cache1.set("key2", "value2", CacheEntry.EntryType.STRING);
        cache1.shutdown();
        
        // Create new cache instance (simulating restart)
        PersistentCacheEngine cache2 = new PersistentCacheEngine(dataDir, nodeId, 1000, new EvictionPolicy.LRU());
        
        // Verify data was recovered
        assertEquals("value1", cache2.get("key1"));
        assertEquals("value2", cache2.get("key2"));
        
        cache2.shutdown();
    }
    
    @Test
    @DisplayName("Should handle mixed operations with persistence")
    void shouldHandleMixedOperations() throws IOException {
        PersistentCacheEngine cache = new PersistentCacheEngine(dataDir, nodeId, 1000, new EvictionPolicy.LRU());
        
        // Add data
        cache.set("key1", "value1", CacheEntry.EntryType.STRING);
        cache.set("key2", "value2", CacheEntry.EntryType.STRING);
        
        // Update data
        cache.set("key1", "value1_updated", CacheEntry.EntryType.STRING);
        
        // Delete data
        cache.delete("key2");
        
        // Add more data
        cache.set("key3", "value3", CacheEntry.EntryType.STRING);
        
        // Verify final state
        assertEquals("value1_updated", cache.get("key1"));
        assertNull(cache.get("key2"));
        assertEquals("value3", cache.get("key3"));
        
        cache.shutdown();
    }
    
    @Test
    @DisplayName("Should handle TTL with persistence")
    void shouldHandleTTLWithPersistence() throws IOException, InterruptedException {
        PersistentCacheEngine cache = new PersistentCacheEngine(dataDir, nodeId, 1000, new EvictionPolicy.LRU());
        
        // Add data with TTL
        cache.set("key1", "value1", 1, CacheEntry.EntryType.STRING); // 1 second TTL
        cache.set("key2", "value2", CacheEntry.EntryType.STRING); // No TTL
        
        // Verify data exists
        assertNotNull(cache.get("key1"));
        assertNotNull(cache.get("key2"));
        
        // Wait for expiration
        Thread.sleep(1100);
        
        // Verify TTL behavior
        assertNull(cache.get("key1")); // Should be expired
        assertNotNull(cache.get("key2")); // Should still exist
        
        cache.shutdown();
    }
    
    @Test
    @DisplayName("Should handle concurrent access with persistence")
    void shouldHandleConcurrentAccess() throws IOException, InterruptedException {
        PersistentCacheEngine cache = new PersistentCacheEngine(dataDir, nodeId, 1000, new EvictionPolicy.LRU());
        
        // Create multiple threads
        Thread[] threads = new Thread[5];
        for (int i = 0; i < threads.length; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 100; j++) {
                    String key = "key_" + threadId + "_" + j;
                    cache.set(key, "value_" + j, CacheEntry.EntryType.STRING);
                    cache.get(key);
                }
            });
        }
        
        // Start all threads
        for (Thread thread : threads) {
            thread.start();
        }
        
        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }
        
        // Verify data integrity
        for (int i = 0; i < threads.length; i++) {
            for (int j = 0; j < 100; j++) {
                String key = "key_" + i + "_" + j;
                assertEquals("value_" + j, cache.get(key));
            }
        }
        
        cache.shutdown();
    }
    
    @Test
    @DisplayName("Should handle large datasets")
    void shouldHandleLargeDatasets() throws IOException {
        PersistentCacheEngine cache = new PersistentCacheEngine(dataDir, nodeId, 1000, new EvictionPolicy.LRU());
        
        // Add large dataset
        for (int i = 0; i < 1000; i++) {
            cache.set("key" + i, "value" + i, CacheEntry.EntryType.STRING);
        }
        
        // Verify all data
        for (int i = 0; i < 1000; i++) {
            assertEquals("value" + i, cache.get("key" + i));
        }
        
        cache.shutdown();
    }
    
    @Test
    @DisplayName("Should handle different eviction policies")
    void shouldHandleDifferentEvictionPolicies() throws IOException {
        // Test with LRU
        PersistentCacheEngine lruCache = new PersistentCacheEngine(dataDir + "/lru", "lru-node", 3, new EvictionPolicy.LRU());
        lruCache.set("key1", "value1", CacheEntry.EntryType.STRING);
        lruCache.set("key2", "value2", CacheEntry.EntryType.STRING);
        lruCache.set("key3", "value3", CacheEntry.EntryType.STRING);
        lruCache.get("key1"); // Make key1 most recently used
        lruCache.set("key4", "value4", CacheEntry.EntryType.STRING); // Should evict key2
        assertNotNull(lruCache.get("key1"));
        assertNull(lruCache.get("key2"));
        lruCache.shutdown();
        
        // Test with LFU
        PersistentCacheEngine lfuCache = new PersistentCacheEngine(dataDir + "/lfu", "lfu-node", 3, new EvictionPolicy.LFU());
        lfuCache.set("key1", "value1", CacheEntry.EntryType.STRING);
        lfuCache.set("key2", "value2", CacheEntry.EntryType.STRING);
        lfuCache.set("key3", "value3", CacheEntry.EntryType.STRING);
        lfuCache.get("key1");
        lfuCache.get("key1"); // Make key1 most frequently used
        lfuCache.set("key4", "value4", CacheEntry.EntryType.STRING); // Should evict key2 or key3
        assertNotNull(lfuCache.get("key1"));
        lfuCache.shutdown();
    }
    
    @Test
    @DisplayName("Should handle data directory creation")
    void shouldHandleDataDirectoryCreation() throws IOException {
        // Test with non-existent directory
        Path newDataDir = tempDir.resolve("new-data-dir");
        PersistentCacheEngine cache = new PersistentCacheEngine(newDataDir.toString(), nodeId, 1000, new EvictionPolicy.LRU());
        
        // Verify directory was created
        assertTrue(Files.exists(newDataDir));
        
        // Add some data
        cache.set("key1", "value1", CacheEntry.EntryType.STRING);
        assertEquals("value1", cache.get("key1"));
        
        cache.shutdown();
    }
    
    @Test
    @DisplayName("Should handle statistics with persistence")
    void shouldHandleStatisticsWithPersistence() throws IOException {
        PersistentCacheEngine cache = new PersistentCacheEngine(dataDir, nodeId, 1000, new EvictionPolicy.LRU());
        
        // Add data and access it
        cache.set("key1", "value1", CacheEntry.EntryType.STRING);
        cache.get("key1"); // Hit
        cache.get("nonexistent"); // Miss
        
        // Get statistics
        CacheEngine.CacheStats stats = cache.getStats();
        
        assertEquals(1, stats.getSize());
        assertEquals(1, stats.getHits());
        assertEquals(1, stats.getMisses());
        assertEquals(0.5, stats.getHitRate(), 0.01);
        
        cache.shutdown();
    }
} 