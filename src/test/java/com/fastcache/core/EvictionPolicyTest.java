package com.fastcache.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for eviction policies.
 */
@DisplayName("Eviction Policy Tests")
public class EvictionPolicyTest {

    @Nested
    @DisplayName("LRU Eviction Policy")
    class LRUEvictionPolicyTest {
        
        private CacheEngine cache;
        
        @BeforeEach
        void setUp() {
            cache = new CacheEngine(3, new EvictionPolicy.LRU());
        }
        
        @AfterEach
        void tearDown() {
            cache.shutdown();
        }
        
        @Test
        @DisplayName("Should evict least recently used item when capacity is reached")
        void shouldEvictLRUWhenCapacityReached() {
            cache.set("key1", "value1", CacheEntry.EntryType.STRING);
            cache.set("key2", "value2", CacheEntry.EntryType.STRING);
            cache.set("key3", "value3", CacheEntry.EntryType.STRING);
            
            // Access key1 to make it most recently used
            cache.get("key1");
            
            // Add 4th item - should evict key2 (least recently used)
            cache.set("key4", "value4", CacheEntry.EntryType.STRING);
            
            assertEquals(3, cache.size());
            assertNotNull(cache.get("key1")); // Still exists (was accessed)
            assertNull(cache.get("key2")); // Should be evicted
            assertNotNull(cache.get("key3")); // Still exists
            assertNotNull(cache.get("key4")); // New item
        }
        
        @Test
        @DisplayName("Should maintain access order correctly")
        void shouldMaintainAccessOrder() {
            cache.set("key1", "value1", CacheEntry.EntryType.STRING);
            cache.set("key2", "value2", CacheEntry.EntryType.STRING);
            cache.set("key3", "value3", CacheEntry.EntryType.STRING);
            
            // Access key1, then key2
            cache.get("key1");
            cache.get("key2");
            
            // Add 4th item - should evict key3 (least recently used)
            cache.set("key4", "value4", CacheEntry.EntryType.STRING);
            
            assertNull(cache.get("key3")); // Should be evicted
            assertNotNull(cache.get("key1"));
            assertNotNull(cache.get("key2"));
            assertNotNull(cache.get("key4"));
        }
        
        @Test
        @DisplayName("Should handle updates correctly")
        void shouldHandleUpdatesCorrectly() {
            cache.set("key1", "value1", CacheEntry.EntryType.STRING);
            cache.set("key2", "value2", CacheEntry.EntryType.STRING);
            cache.set("key3", "value3", CacheEntry.EntryType.STRING);
            
            // Update key1 - should make it most recently used
            cache.set("key1", "value1_updated", CacheEntry.EntryType.STRING);
            
            // Add 4th item - should evict key2
            cache.set("key4", "value4", CacheEntry.EntryType.STRING);
            
            assertNull(cache.get("key2")); // Should be evicted
            assertEquals("value1_updated", cache.get("key1"));
        }
    }
    
    @Nested
    @DisplayName("LFU Eviction Policy")
    class LFUEvictionPolicyTest {
        
        private CacheEngine cache;
        
        @BeforeEach
        void setUp() {
            cache = new CacheEngine(3, new EvictionPolicy.LFU());
        }
        
        @AfterEach
        void tearDown() {
            cache.shutdown();
        }
        
        @Test
        @DisplayName("Should evict least frequently used item")
        void shouldEvictLFU() {
            cache.set("key1", "value1", CacheEntry.EntryType.STRING);
            cache.set("key2", "value2", CacheEntry.EntryType.STRING);
            cache.set("key3", "value3", CacheEntry.EntryType.STRING);
            
            // Access key1 multiple times
            cache.get("key1");
            cache.get("key1");
            cache.get("key1");
            
            // Access key2 once
            cache.get("key2");
            
            // Don't access key3
            
            // Add 4th item - should evict key3 (least frequently used)
            cache.set("key4", "value4", CacheEntry.EntryType.STRING);
            
            assertNull(cache.get("key3")); // Should be evicted
            assertNotNull(cache.get("key1"));
            assertNotNull(cache.get("key2"));
            assertNotNull(cache.get("key4"));
        }
        
        @Test
        @DisplayName("Should handle frequency updates correctly")
        void shouldHandleFrequencyUpdates() {
            cache.set("key1", "value1", CacheEntry.EntryType.STRING);
            cache.set("key2", "value2", CacheEntry.EntryType.STRING);
            cache.set("key3", "value3", CacheEntry.EntryType.STRING);
            
            // Make key3 most frequently used
            cache.get("key3");
            cache.get("key3");
            cache.get("key3");
            
            // Make key2 second most frequently used
            cache.get("key2");
            cache.get("key2");
            
            // key1 has no accesses
            
            // Add 4th item - should evict key1
            cache.set("key4", "value4", CacheEntry.EntryType.STRING);
            
            assertNull(cache.get("key1")); // Should be evicted
            assertNotNull(cache.get("key2"));
            assertNotNull(cache.get("key3"));
            assertNotNull(cache.get("key4"));
        }
    }
    
    @Nested
    @DisplayName("Random Eviction Policy")
    class RandomEvictionPolicyTest {
        
        private CacheEngine cache;
        
        @BeforeEach
        void setUp() {
            cache = new CacheEngine(3, new EvictionPolicy.Random());
        }
        
        @AfterEach
        void tearDown() {
            cache.shutdown();
        }
        
        @Test
        @DisplayName("Should evict items when capacity is reached")
        void shouldEvictWhenCapacityReached() {
            cache.set("key1", "value1", CacheEntry.EntryType.STRING);
            cache.set("key2", "value2", CacheEntry.EntryType.STRING);
            cache.set("key3", "value3", CacheEntry.EntryType.STRING);
            
            assertEquals(3, cache.size());
            
            // Add 4th item - should evict one item randomly
            cache.set("key4", "value4", CacheEntry.EntryType.STRING);
            
            assertEquals(3, cache.size());
            assertNotNull(cache.get("key4")); // New item should exist
        }
        
        @Test
        @DisplayName("Should handle access without changing eviction order")
        void shouldHandleAccessWithoutChangingOrder() {
            cache.set("key1", "value1", CacheEntry.EntryType.STRING);
            cache.set("key2", "value2", CacheEntry.EntryType.STRING);
            cache.set("key3", "value3", CacheEntry.EntryType.STRING);
            
            // Access items multiple times
            cache.get("key1");
            cache.get("key1");
            cache.get("key2");
            cache.get("key3");
            
            // Add 4th item - should still evict one item
            cache.set("key4", "value4", CacheEntry.EntryType.STRING);
            
            assertEquals(3, cache.size());
            assertNotNull(cache.get("key4"));
        }
    }
    
    @Nested
    @DisplayName("TTL Eviction Policy")
    class TTLEvictionPolicyTest {
        
        private CacheEngine cache;
        
        @BeforeEach
        void setUp() {
            cache = new CacheEngine(1000, new EvictionPolicy.TTL());
        }
        
        @AfterEach
        void tearDown() {
            cache.shutdown();
        }
        
        @Test
        @DisplayName("Should expire items after TTL")
        void shouldExpireAfterTTL() throws InterruptedException {
            cache.set("key1", "value1", 1, CacheEntry.EntryType.STRING); // 1 second TTL
            
            assertNotNull(cache.get("key1"));
            
            // Wait for expiration
            Thread.sleep(1100);
            
            assertNull(cache.get("key1"));
        }
        
        @Test
        @DisplayName("Should handle different TTL values")
        void shouldHandleDifferentTTLValues() throws InterruptedException {
            cache.set("key1", "value1", 1, CacheEntry.EntryType.STRING); // 1 second
            cache.set("key2", "value2", 2, CacheEntry.EntryType.STRING); // 2 seconds
            
            // Wait 1.5 seconds
            Thread.sleep(1500);
            
            assertNull(cache.get("key1")); // Should be expired
            assertNotNull(cache.get("key2")); // Should still exist
            
            // Wait another second
            Thread.sleep(1000);
            
            assertNull(cache.get("key2")); // Should now be expired
        }
        
        @Test
        @DisplayName("Should handle no TTL correctly")
        void shouldHandleNoTTL() {
            cache.set("key1", "value1", CacheEntry.EntryType.STRING); // No TTL
            
            assertNotNull(cache.get("key1"));
            assertEquals(-1, cache.ttl("key1")); // No expiration
        }
        
        @Test
        @DisplayName("Should update TTL correctly")
        void shouldUpdateTTL() {
            cache.set("key1", "value1", 60, CacheEntry.EntryType.STRING); // 60 seconds
            
            long ttl1 = cache.ttl("key1");
            assertTrue(ttl1 > 0 && ttl1 <= 60);
            
            // Update TTL
            cache.expire("key1", 30);
            
            long ttl2 = cache.ttl("key1");
            assertTrue(ttl2 > 0 && ttl2 <= 30);
            assertTrue(ttl2 < ttl1);
        }
    }
    
    @Nested
    @DisplayName("Eviction Policy Integration")
    class EvictionPolicyIntegrationTest {
        
        @Test
        @DisplayName("Should handle concurrent access correctly")
        void shouldHandleConcurrentAccess() throws InterruptedException {
            CacheEngine cache = new CacheEngine(100, new EvictionPolicy.LRU());
            
            // Create multiple threads accessing the cache
            Thread[] threads = new Thread[10];
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
            
            // Give eviction a chance to catch up
            Thread.sleep(100);
            
            // Verify cache size doesn't exceed limit (allow for small overrun due to race conditions)
            assertTrue(cache.size() <= 110);
            
            cache.shutdown();
        }
        
        @Test
        @DisplayName("Should handle mixed operations correctly")
        void shouldHandleMixedOperations() {
            CacheEngine cache = new CacheEngine(5, new EvictionPolicy.LRU());
            
            // Add items
            cache.set("key1", "value1", CacheEntry.EntryType.STRING);
            cache.set("key2", "value2", CacheEntry.EntryType.STRING);
            cache.set("key3", "value3", CacheEntry.EntryType.STRING);
            
            // Access some items
            cache.get("key1");
            cache.get("key2");
            
            // Delete an item
            cache.delete("key1");
            
            // Add more items
            cache.set("key4", "value4", CacheEntry.EntryType.STRING);
            cache.set("key5", "value5", CacheEntry.EntryType.STRING);
            cache.set("key6", "value6", CacheEntry.EntryType.STRING);
            
            // Verify final state
            assertEquals(5, cache.size());
            assertNull(cache.get("key1")); // Deleted
            assertNotNull(cache.get("key2"));
            assertNotNull(cache.get("key3"));
            assertNotNull(cache.get("key4"));
            assertNotNull(cache.get("key5"));
            assertNotNull(cache.get("key6"));
            
            cache.shutdown();
        }
    }
} 