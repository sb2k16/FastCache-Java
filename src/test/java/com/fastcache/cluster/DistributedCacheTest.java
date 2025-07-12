package com.fastcache.cluster;

import com.fastcache.core.CacheEngine;
import com.fastcache.core.EvictionPolicy;
import com.fastcache.core.CacheEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for distributed cache functionality.
 */
@DisplayName("Distributed Cache Tests")
public class DistributedCacheTest {

    @TempDir
    Path tempDir;
    
    private String dataDir;
    private DistributedCacheManager cacheManager;
    
    @BeforeEach
    void setUp() {
        dataDir = tempDir.toString();
        cacheManager = DistributedCacheManager.builder()
            .dataDir(dataDir)
            .enableReplication(true)
            .replicationFactor(2)
            .virtualNodes(150)
            .maxSize(1000)
            .evictionPolicy(new EvictionPolicy.LRU())
            .build();
    }
    
    @AfterEach
    void tearDown() {
        if (cacheManager != null) {
            cacheManager.shutdown();
        }
    }
    
    @Test
    @DisplayName("Should create distributed cache manager")
    void shouldCreateDistributedCacheManager() {
        assertNotNull(cacheManager);
        assertEquals(0, cacheManager.getNodeCount());
        assertTrue(cacheManager.isEmpty());
    }
    
    @Test
    @DisplayName("Should add and remove nodes")
    void shouldAddAndRemoveNodes() throws IOException {
        // Create cache nodes
        CacheNode node1 = new CacheNode("node1", "localhost", 6379);
        CacheNode node2 = new CacheNode("node2", "localhost", 6380);
        
        // Create local engines
        CacheEngine engine1 = new CacheEngine(1000, new EvictionPolicy.LRU());
        CacheEngine engine2 = new CacheEngine(1000, new EvictionPolicy.LRU());
        
        // Add nodes
        cacheManager.addNode(node1, engine1);
        cacheManager.addNode(node2, engine2);
        
        // Verify nodes were added
        assertEquals(2, cacheManager.getNodeCount());
        assertFalse(cacheManager.isEmpty());
        
        // Remove a node
        cacheManager.removeNode("node1");
        
        // Verify node was removed
        assertEquals(1, cacheManager.getNodeCount());
        
        // Clean up
        engine1.shutdown();
        engine2.shutdown();
    }
    
    @Test
    @DisplayName("Should handle basic operations with nodes")
    void shouldHandleBasicOperationsWithNodes() throws IOException, InterruptedException, ExecutionException, TimeoutException {
        // Create cache node and engine
        CacheNode node = new CacheNode("node1", "localhost", 6379);
        CacheEngine engine = new CacheEngine(1000, new EvictionPolicy.LRU());
        
        // Add node
        cacheManager.addNode(node, engine);
        
        // Test set operation
        CompletableFuture<Boolean> setFuture = cacheManager.set("key1", "value1", CacheEntry.EntryType.STRING);
        assertTrue(setFuture.get(5, TimeUnit.SECONDS));
        
        // Test get operation
        CompletableFuture<Object> getFuture = cacheManager.get("key1");
        Object result = getFuture.get(5, TimeUnit.SECONDS);
        assertEquals("value1", result);
        
        // Test delete operation
        CompletableFuture<Boolean> deleteFuture = cacheManager.delete("key1");
        assertTrue(deleteFuture.get(5, TimeUnit.SECONDS));
        
        // Verify deletion
        CompletableFuture<Object> getAfterDeleteFuture = cacheManager.get("key1");
        Object resultAfterDelete = getAfterDeleteFuture.get(5, TimeUnit.SECONDS);
        assertNull(resultAfterDelete);
        
        // Clean up
        engine.shutdown();
    }
    
    @Test
    @DisplayName("Should handle TTL operations")
    void shouldHandleTTLOperations() throws IOException, InterruptedException, ExecutionException, TimeoutException {
        // Create cache node and engine
        CacheNode node = new CacheNode("node1", "localhost", 6379);
        CacheEngine engine = new CacheEngine(1000, new EvictionPolicy.LRU());
        
        // Add node
        cacheManager.addNode(node, engine);
        
        // Test set with TTL
        CompletableFuture<Boolean> setFuture = cacheManager.set("key1", "value1", 1, CacheEntry.EntryType.STRING);
        assertTrue(setFuture.get(5, TimeUnit.SECONDS));
        
        // Verify value exists
        CompletableFuture<Object> getFuture = cacheManager.get("key1");
        Object result = getFuture.get(5, TimeUnit.SECONDS);
        assertEquals("value1", result);
        
        // Wait for expiration
        Thread.sleep(1100);
        
        // Verify value is expired
        CompletableFuture<Object> getAfterExpiryFuture = cacheManager.get("key1");
        Object resultAfterExpiry = getAfterExpiryFuture.get(5, TimeUnit.SECONDS);
        assertNull(resultAfterExpiry);
        
        // Clean up
        engine.shutdown();
    }
    
    @Test
    @DisplayName("Should handle cluster statistics")
    void shouldHandleClusterStatistics() throws IOException {
        // Create cache node and engine
        CacheNode node = new CacheNode("node1", "localhost", 6379);
        CacheEngine engine = new CacheEngine(1000, new EvictionPolicy.LRU());
        
        // Add node
        cacheManager.addNode(node, engine);
        
        // Get cluster statistics
        DistributedCacheManager.ClusterStats stats = cacheManager.getClusterStats();
        
        assertNotNull(stats);
        assertEquals(1, stats.getNodeCount());
        assertTrue(stats.getTotalSize() >= 0);
        
        // Clean up
        engine.shutdown();
    }
    
    @Test
    @DisplayName("Should handle shutdown gracefully")
    void shouldHandleShutdownGracefully() throws IOException {
        // Create cache node and engine
        CacheNode node = new CacheNode("node1", "localhost", 6379);
        CacheEngine engine = new CacheEngine(1000, new EvictionPolicy.LRU());
        
        // Add node
        cacheManager.addNode(node, engine);
        
        // Verify cluster is running
        assertEquals(1, cacheManager.getNodeCount());
        
        // Shutdown cluster
        cacheManager.shutdown();
        
        // Verify cluster is shutdown
        assertTrue(cacheManager.isEmpty());
        
        // Clean up
        engine.shutdown();
    }
} 