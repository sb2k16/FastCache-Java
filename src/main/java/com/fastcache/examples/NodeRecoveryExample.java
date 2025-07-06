package com.fastcache.examples;

import com.fastcache.core.CacheEntry;
import com.fastcache.core.EvictionPolicy;
import com.fastcache.core.PersistentCacheEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * Example demonstrating node recovery with persistence.
 * Shows how a cache node can recover its data after a crash.
 */
public class NodeRecoveryExample {
    
    public static void main(String[] args) {
        String dataDir = "data/node1";
        String nodeId = "node1";
        
        System.out.println("=== FastCache Node Recovery Example ===\n");
        
        try {
            // Clean up any existing data
            cleanupData(dataDir);
            
            // Step 1: Start the cache node and populate it with data
            System.out.println("Step 1: Starting cache node and populating with data...");
            PersistentCacheEngine cache = new PersistentCacheEngine(dataDir, nodeId, 1000, new EvictionPolicy.LRU());
            
            // Add some test data
            cache.set("user:1", "John Doe", 3600, CacheEntry.EntryType.STRING);
            cache.set("user:2", "Jane Smith", 3600, CacheEntry.EntryType.STRING);
            cache.set("config:app", "production", -1, CacheEntry.EntryType.STRING);
            
            // Add sorted set data
            cache.zadd("leaderboard", "player1", 100.0);
            cache.zadd("leaderboard", "player2", 85.5);
            cache.zadd("leaderboard", "player3", 120.0);
            
            // Create a snapshot
            cache.createSnapshot();
            
            System.out.println("Cache populated with data:");
            System.out.println("- user:1 = " + cache.get("user:1"));
            System.out.println("- user:2 = " + cache.get("user:2"));
            System.out.println("- config:app = " + cache.get("config:app"));
            System.out.println("- leaderboard size = " + cache.zcard("leaderboard"));
            System.out.println("- leaderboard top player = " + cache.zrevrange("leaderboard", 0, 0));
            
            // Shutdown the cache (simulating a crash)
            System.out.println("\nStep 2: Simulating node crash...");
            cache.shutdown();
            
            // Step 3: Simulate node restart and recovery
            System.out.println("\nStep 3: Restarting node and performing recovery...");
            PersistentCacheEngine recoveredCache = new PersistentCacheEngine(dataDir, nodeId, 1000, new EvictionPolicy.LRU());
            
            // Verify that data was recovered
            System.out.println("\nStep 4: Verifying recovered data...");
            System.out.println("- user:1 = " + recoveredCache.get("user:1"));
            System.out.println("- user:2 = " + recoveredCache.get("user:2"));
            System.out.println("- config:app = " + recoveredCache.get("config:app"));
            System.out.println("- leaderboard size = " + recoveredCache.zcard("leaderboard"));
            System.out.println("- leaderboard top player = " + recoveredCache.zrevrange("leaderboard", 0, 0));
            
            // Add more data after recovery
            System.out.println("\nStep 5: Adding more data after recovery...");
            recoveredCache.set("user:3", "Bob Johnson", 1800, CacheEntry.EntryType.STRING);
            recoveredCache.zadd("leaderboard", "player4", 95.0);
            
            // Show final state
            System.out.println("\nFinal cache state:");
            System.out.println("- user:3 = " + recoveredCache.get("user:3"));
            System.out.println("- leaderboard size = " + recoveredCache.zcard("leaderboard"));
            System.out.println("- leaderboard all players = " + recoveredCache.zrevrange("leaderboard", 0, -1));
            
            // Show persistence statistics
            System.out.println("\nPersistence Statistics:");
            System.out.println(recoveredCache.getPersistentStats());
            
            // Cleanup
            recoveredCache.shutdown();
            
            System.out.println("\n=== Recovery Example Completed Successfully ===");
            
        } catch (Exception e) {
            System.err.println("Recovery example failed: " + e.getMessage());
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
                Files.walk(dataPath)
                    .sorted((a, b) -> b.compareTo(a)) // Delete files before directories
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            System.err.println("Failed to delete: " + path + " - " + e.getMessage());
                        }
                    });
                System.out.println("Cleaned up existing data directory: " + dataDir);
            }
        } catch (IOException e) {
            System.err.println("Failed to cleanup data directory: " + e.getMessage());
        }
    }
} 