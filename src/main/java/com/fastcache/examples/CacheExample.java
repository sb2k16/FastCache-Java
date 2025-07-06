package com.fastcache.examples;

import com.fastcache.client.FastCacheClient;
import com.fastcache.protocol.CacheResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Example demonstrating FastCache usage with various operations.
 */
public class CacheExample {
    private static final Logger logger = LoggerFactory.getLogger(CacheExample.class);
    
    public static void main(String[] args) {
        logger.info("Starting FastCache Example");
        
        FastCacheClient client = new FastCacheClient("localhost", 6379);
        
        // Connect to server
        client.connect().thenRun(() -> {
            logger.info("Connected to FastCache server");
            
            // Run examples
            runBasicOperations(client);
            runExpirationExample(client);
            runConcurrentOperations(client);
            runClusterInfoExample(client);
            
        }).exceptionally(throwable -> {
            logger.error("Failed to connect to FastCache server", throwable);
            return null;
        });
        
        // Keep the application running for a while
        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        client.disconnect();
        logger.info("Example completed");
    }
    
    private static void runBasicOperations(FastCacheClient client) {
        logger.info("=== Basic Operations ===");
        
        // Set a value
        client.set("user:1", "John Doe").thenAccept(response -> {
            if (response.isSuccess()) {
                logger.info("✓ User stored successfully");
            } else {
                logger.error("✗ Failed to store user: {}", response.getErrorMessage());
            }
        });
        
        // Get a value
        client.get("user:1").thenAccept(response -> {
            if (response.isSuccess()) {
                logger.info("✓ Retrieved user: {}", response.getDataAsString());
            } else {
                logger.error("✗ Failed to retrieve user: {}", response.getErrorMessage());
            }
        });
        
        // Check if key exists
        client.exists("user:1").thenAccept(response -> {
            if (response.isSuccess()) {
                logger.info("✓ Key exists: {}", response.getDataAsBoolean());
            }
        });
        
        // Delete a key
        client.delete("user:1").thenAccept(response -> {
            if (response.isSuccess()) {
                logger.info("✓ Key deleted: {}", response.getDataAsBoolean());
            }
        });
    }
    
    private static void runExpirationExample(FastCacheClient client) {
        logger.info("=== Expiration Example ===");
        
        // Set a value with TTL
        client.set("session:123", "active", 5).thenAccept(response -> {
            if (response.isSuccess()) {
                logger.info("✓ Session stored with 5-second TTL");
            }
        });
        
        // Check TTL immediately
        client.ttl("session:123").thenAccept(response -> {
            if (response.isSuccess()) {
                logger.info("✓ TTL: {} seconds", response.getDataAsLong());
            }
        });
        
        // Wait and check TTL again
        CompletableFuture.delayedExecutor(3, TimeUnit.SECONDS).execute(() -> {
            client.ttl("session:123").thenAccept(response -> {
                if (response.isSuccess()) {
                    logger.info("✓ TTL after 3 seconds: {} seconds", response.getDataAsLong());
                }
            });
        });
        
        // Wait for expiration and try to get
        CompletableFuture.delayedExecutor(6, TimeUnit.SECONDS).execute(() -> {
            client.get("session:123").thenAccept(response -> {
                if (response.isNotFound()) {
                    logger.info("✓ Session expired as expected");
                } else {
                    logger.info("Session still exists: {}", response.getDataAsString());
                }
            });
        });
    }
    
    private static void runConcurrentOperations(FastCacheClient client) {
        logger.info("=== Concurrent Operations ===");
        
        int numOperations = 100;
        CountDownLatch latch = new CountDownLatch(numOperations);
        
        // Perform concurrent set operations
        for (int i = 0; i < numOperations; i++) {
            final int index = i;
            client.set("concurrent:key:" + index, "value:" + index).thenAccept(response -> {
                if (response.isSuccess()) {
                    logger.debug("✓ Set concurrent:key:{}", index);
                } else {
                    logger.error("✗ Failed to set concurrent:key:{}", index);
                }
                latch.countDown();
            });
        }
        
        // Wait for all operations to complete
        try {
            latch.await(10, TimeUnit.SECONDS);
            logger.info("✓ Completed {} concurrent operations", numOperations);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Concurrent operations interrupted");
        }
        
        // Verify some of the values
        for (int i = 0; i < 5; i++) {
            final int index = i;
            client.get("concurrent:key:" + index).thenAccept(response -> {
                if (response.isSuccess()) {
                    logger.info("✓ Verified concurrent:key:{} = {}", index, response.getDataAsString());
                } else {
                    logger.error("✗ Failed to verify concurrent:key:{}", index);
                }
            });
        }
    }
    
    private static void runClusterInfoExample(FastCacheClient client) {
        logger.info("=== Cluster Information ===");
        
        // Get server info
        client.info().thenAccept(response -> {
            if (response.isSuccess()) {
                logger.info("Server Info:\n{}", response.getDataAsString());
            }
        });
        
        // Get cache statistics
        client.stats().thenAccept(response -> {
            if (response.isSuccess()) {
                logger.info("Cache Stats: {}", response.getDataAsString());
            }
        });
        
        // Get cluster information
        client.clusterInfo().thenAccept(response -> {
            if (response.isSuccess()) {
                logger.info("Cluster Info:\n{}", response.getDataAsString());
            }
        });
        
        // Get cluster nodes
        client.clusterNodes().thenAccept(response -> {
            if (response.isSuccess()) {
                logger.info("Cluster Nodes:\n{}", response.getDataAsString());
            }
        });
        
        // Get cluster statistics
        client.clusterStats().thenAccept(response -> {
            if (response.isSuccess()) {
                logger.info("Cluster Stats: {}", response.getDataAsString());
            }
        });
    }
} 