package com.fastcache.examples;

import com.fastcache.core.CacheEntry;
import com.fastcache.core.EvictionPolicy;
import com.fastcache.core.PersistentCacheEngine;
import com.fastcache.core.PersistenceConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * Comprehensive example demonstrating FastCache persistence functionality.
 * Shows crash recovery, snapshots, WAL operations, and configuration options.
 */
public class PersistenceExample {
    
    public static void main(String[] args) {
        System.out.println("=== FastCache Persistence Example ===\n");
        
        String dataDir = "data/persistence-demo";
        String nodeId = "persistence-demo";
        
        try {
            // Clean up any existing data
            cleanupData(dataDir);
            
            // Step 1: Configure persistence
            System.out.println("Step 1: Configuring persistence...");
            PersistenceConfig config = PersistenceConfig.builder()
                .enablePersistence(true)
                .enableWriteAheadLog(true)
                .enableSnapshots(true)
                .snapshotInterval(java.time.Duration.ofMinutes(2))
                .persistenceDir(dataDir)
                .enableCompression(false)
                .build();
            
            System.out.println("Persistence configuration: " + config);
            
            // Step 2: Create persistent cache engine
            System.out.println("\nStep 2: Creating persistent cache engine...");
            PersistentCacheEngine cache = new PersistentCacheEngine(dataDir, nodeId, 1000, new EvictionPolicy.LRU());
            
            // Step 3: Add various types of data
            System.out.println("\nStep 3: Adding data to cache...");
            
            // String data
            cache.set("user:1", "John Doe", 3600, CacheEntry.EntryType.STRING);
            cache.set("user:2", "Jane Smith", 3600, CacheEntry.EntryType.STRING);
            cache.set("config:app", "production", -1, CacheEntry.EntryType.STRING);
            cache.set("session:123", "active", 1800, CacheEntry.EntryType.STRING);
            
            // Sorted set data
            cache.zadd("leaderboard", "player1", 100.0);
            cache.zadd("leaderboard", "player2", 85.5);
            cache.zadd("leaderboard", "player3", 120.0);
            cache.zadd("leaderboard", "player4", 95.0);
            
            // More data to trigger eviction
            for (int i = 1; i <= 50; i++) {
                cache.set("temp:key:" + i, "value:" + i, 300, CacheEntry.EntryType.STRING);
            }
            
            System.out.println("Cache populated with data:");
            System.out.println("- user:1 = " + cache.get("user:1"));
            System.out.println("- user:2 = " + cache.get("user:2"));
            System.out.println("- config:app = " + cache.get("config:app"));
            System.out.println("- session:123 = " + cache.get("session:123"));
            
            // Display sorted set data
            System.out.println("- leaderboard top 3:");
            var topPlayers = cache.zrevrange("leaderboard", 0, 2);
            for (int i = 0; i < topPlayers.size(); i++) {
                String player = topPlayers.get(i);
                Double score = cache.zscore("leaderboard", player);
                System.out.println("  " + (i + 1) + ". " + player + " = " + score);
            }
            
            // Step 4: Create a snapshot
            System.out.println("\nStep 4: Creating snapshot...");
            cache.createSnapshot();
            
            // Step 5: Add more data after snapshot
            System.out.println("\nStep 5: Adding more data after snapshot...");
            cache.set("user:3", "Bob Johnson", 3600, CacheEntry.EntryType.STRING);
            cache.set("user:4", "Alice Brown", 3600, CacheEntry.EntryType.STRING);
            cache.zadd("leaderboard", "player5", 110.0);
            
            System.out.println("Additional data added:");
            System.out.println("- user:3 = " + cache.get("user:3"));
            System.out.println("- user:4 = " + cache.get("user:4"));
            
            // Step 6: Display cache statistics
            System.out.println("\nStep 6: Cache statistics before crash:");
            var stats = cache.getPersistentStats();
            System.out.println("Cache stats: " + stats.getCacheStats());
            System.out.println("WAL sequence number: " + stats.getWalSequenceNumber());
            System.out.println("Data directory: " + stats.getDataDir());
            System.out.println("Node ID: " + stats.getNodeId());
            
            // Step 7: Simulate crash by closing the cache
            System.out.println("\nStep 7: Simulating node crash...");
            cache.shutdown();
            
            // Step 8: Restart and recover
            System.out.println("\nStep 8: Restarting and recovering...");
            System.out.println("Waiting 2 seconds before recovery...");
            Thread.sleep(2000);
            
            PersistentCacheEngine recoveredCache = new PersistentCacheEngine(dataDir, nodeId, 1000, new EvictionPolicy.LRU());
            
            // Step 9: Verify recovered data
            System.out.println("\nStep 9: Verifying recovered data...");
            System.out.println("Recovered cache contents:");
            System.out.println("- user:1 = " + recoveredCache.get("user:1"));
            System.out.println("- user:2 = " + recoveredCache.get("user:2"));
            System.out.println("- user:3 = " + recoveredCache.get("user:3"));
            System.out.println("- user:4 = " + recoveredCache.get("user:4"));
            System.out.println("- config:app = " + recoveredCache.get("config:app"));
            System.out.println("- session:123 = " + recoveredCache.get("session:123"));
            
            // Verify sorted set data
            System.out.println("- leaderboard after recovery:");
            var recoveredTopPlayers = recoveredCache.zrevrange("leaderboard", 0, 4);
            for (int i = 0; i < recoveredTopPlayers.size(); i++) {
                String player = recoveredTopPlayers.get(i);
                Double score = recoveredCache.zscore("leaderboard", player);
                System.out.println("  " + (i + 1) + ". " + player + " = " + score);
            }
            
            // Step 10: Add more data after recovery
            System.out.println("\nStep 10: Adding more data after recovery...");
            recoveredCache.set("user:5", "Charlie Wilson", 3600, CacheEntry.EntryType.STRING);
            recoveredCache.set("user:6", "Diana Davis", 3600, CacheEntry.EntryType.STRING);
            recoveredCache.zadd("leaderboard", "player6", 105.0);
            
            // Step 11: Create another snapshot
            System.out.println("\nStep 11: Creating another snapshot...");
            recoveredCache.createSnapshot();
            
            // Step 12: Test expiration
            System.out.println("\nStep 12: Testing expiration...");
            recoveredCache.set("expiring:key", "will expire soon", 5, CacheEntry.EntryType.STRING);
            System.out.println("Added expiring key: expiring:key = " + recoveredCache.get("expiring:key"));
            System.out.println("TTL: " + recoveredCache.ttl("expiring:key") + " seconds");
            
            // Step 13: Final statistics
            System.out.println("\nStep 13: Final cache statistics:");
            var finalStats = recoveredCache.getPersistentStats();
            System.out.println("Cache stats: " + finalStats.getCacheStats());
            System.out.println("WAL sequence number: " + finalStats.getWalSequenceNumber());
            
            // Step 14: Clean shutdown
            System.out.println("\nStep 14: Performing clean shutdown...");
            recoveredCache.shutdown();
            
            System.out.println("\n=== Persistence Example Completed Successfully ===");
            System.out.println("Data has been persisted to: " + dataDir);
            System.out.println("You can restart the cache to see the data recovered.");
            
        } catch (Exception e) {
            System.err.println("Persistence example failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Cleans up existing data directory.
     */
    private static void cleanupData(String dataDir) {
        try {
            Path dataPath = Paths.get(dataDir);
            if (Files.exists(dataPath)) {
                System.out.println("Cleaning up existing data directory: " + dataDir);
                Files.walk(dataPath)
                    .sorted((a, b) -> b.compareTo(a)) // Delete files first, then directories
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            System.err.println("Failed to delete: " + path + " - " + e.getMessage());
                        }
                    });
            }
        } catch (IOException e) {
            System.err.println("Failed to cleanup data directory: " + e.getMessage());
        }
    }
} 