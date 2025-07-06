package com.fastcache.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Basic tests for CacheEngine functionality.
 */
public class CacheEngineTest {
    
    private CacheEngine cache;
    
    @BeforeEach
    void setUp() {
        cache = new CacheEngine(1000, new EvictionPolicy.LRU());
    }
    
    @AfterEach
    void tearDown() {
        cache.shutdown();
    }
    
    @Test
    void testBasicSetAndGet() {
        // Test basic set and get operations
        assertTrue(cache.set("key1", "value1", CacheEntry.EntryType.STRING));
        
        Object result = cache.get("key1");
        assertEquals("value1", result);
    }
    
    @Test
    void testGetNonExistentKey() {
        // Test getting a key that doesn't exist
        Object result = cache.get("nonexistent");
        assertNull(result);
    }
    
    @Test
    void testDelete() {
        // Test delete operation
        cache.set("key1", "value1", CacheEntry.EntryType.STRING);
        assertTrue(cache.delete("key1"));
        assertNull(cache.get("key1"));
    }
    
    @Test
    void testDeleteNonExistentKey() {
        // Test deleting a key that doesn't exist
        assertFalse(cache.delete("nonexistent"));
    }
    
    @Test
    void testExists() {
        // Test exists operation
        cache.set("key1", "value1", CacheEntry.EntryType.STRING);
        assertTrue(cache.exists("key1"));
        assertFalse(cache.exists("nonexistent"));
    }
    
    @Test
    void testExpiration() throws InterruptedException {
        // Test TTL functionality
        cache.set("key1", "value1", 1, CacheEntry.EntryType.STRING); // 1 second TTL
        
        // Should exist immediately
        assertTrue(cache.exists("key1"));
        
        // Wait for expiration
        Thread.sleep(1100);
        
        // Should not exist after expiration
        assertFalse(cache.exists("key1"));
        assertNull(cache.get("key1"));
    }
    
    @Test
    void testTtl() {
        // Test TTL retrieval
        cache.set("key1", "value1", 60, CacheEntry.EntryType.STRING); // 60 seconds TTL
        
        long ttl = cache.ttl("key1");
        assertTrue(ttl > 0 && ttl <= 60);
        
        // Test TTL for non-existent key
        assertEquals(-2, cache.ttl("nonexistent"));
        
        // Test TTL for key without expiration
        cache.set("key2", "value2", CacheEntry.EntryType.STRING);
        assertEquals(-1, cache.ttl("key2"));
    }
    
    @Test
    void testExpire() {
        // Test setting expiration on existing key
        cache.set("key1", "value1", CacheEntry.EntryType.STRING);
        assertTrue(cache.expire("key1", 60));
        
        long ttl = cache.ttl("key1");
        assertTrue(ttl > 0 && ttl <= 60);
    }
    
    @Test
    void testFlush() {
        // Test flush operation
        cache.set("key1", "value1", CacheEntry.EntryType.STRING);
        cache.set("key2", "value2", CacheEntry.EntryType.STRING);
        
        assertEquals(2, cache.size());
        
        cache.flush();
        
        assertEquals(0, cache.size());
        assertFalse(cache.exists("key1"));
        assertFalse(cache.exists("key2"));
    }
    
    @Test
    void testStats() {
        // Test statistics
        cache.set("key1", "value1", CacheEntry.EntryType.STRING);
        cache.get("key1"); // Hit
        cache.get("nonexistent"); // Miss
        
        CacheEngine.CacheStats stats = cache.getStats();
        
        assertEquals(1, stats.getSize());
        assertEquals(1, stats.getHits());
        assertEquals(1, stats.getMisses());
        assertEquals(0.5, stats.getHitRate(), 0.01);
    }
    
    @Test
    void testKeys() {
        // Test keys operation
        cache.set("key1", "value1", CacheEntry.EntryType.STRING);
        cache.set("key2", "value2", CacheEntry.EntryType.STRING);
        
        var keys = cache.keys();
        assertEquals(2, keys.size());
        assertTrue(keys.contains("key1"));
        assertTrue(keys.contains("key2"));
    }
    
    @Test
    void testSizeAndEmpty() {
        // Test size and empty operations
        assertTrue(cache.isEmpty());
        assertEquals(0, cache.size());
        
        cache.set("key1", "value1", CacheEntry.EntryType.STRING);
        
        assertFalse(cache.isEmpty());
        assertEquals(1, cache.size());
    }
} 