package com.fastcache.examples;

import com.fastcache.core.CacheEngine;
import com.fastcache.core.WriteAheadLog;
import com.fastcache.discovery.ServiceDiscovery;
import com.fastcache.replication.ReplicationConfig;
import com.fastcache.replication.ReplicationManager;
import com.fastcache.replication.ReplicationInfo;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Example demonstrating Redis-inspired replication in FastCache.
 * Shows how to set up primary-replica replication with automatic failover.
 */
public class ReplicationExample {
    
    public static void main(String[] args) {
        System.out.println("=== FastCache Redis-Inspired Replication Example ===\n");
        
        try {
            // Example 1: Primary Node Setup
            setupPrimaryNode();
            
            // Wait a bit
            Thread.sleep(2000);
            
            // Example 2: Replica Node Setup
            setupReplicaNode();
            
            // Wait a bit
            Thread.sleep(2000);
            
            // Example 3: Demonstrate Replication
            demonstrateReplication();
            
        } catch (Exception e) {
            System.err.println("Replication example failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Sets up a primary node with replication enabled.
     */
    private static void setupPrimaryNode() throws IOException {
        System.out.println("--- Setting up Primary Node ---");
        
        // Create cache engine and WAL
        CacheEngine cacheEngine = new CacheEngine(1000, new com.fastcache.core.EvictionPolicy.LRU());
        WriteAheadLog wal = new WriteAheadLog("/tmp/fastcache/primary", "primary-node");
        ServiceDiscovery serviceDiscovery = new ServiceDiscovery();
        
        // Configure replication
        ReplicationConfig config = ReplicationConfig.builder()
                .replicationFactor(2)
                .heartbeatInterval(5000)
                .backlogSize(1024 * 1024) // 1MB
                .enableAutomaticFailover(true)
                .build();
        
        // Create replication manager
        ReplicationManager replicationManager = new ReplicationManager(
                "primary-node", "localhost", 6379, cacheEngine, wal, serviceDiscovery, config);
        
        // Start replication manager
        replicationManager.start();
        
        // Promote to primary
        replicationManager.promoteToPrimary();
        
        // Register with service discovery
        serviceDiscovery.registerNode("primary-node", "localhost", 6379, "cache");
        
        System.out.println("Primary node setup complete");
        System.out.println("Replication info: " + replicationManager.getReplicationInfo());
    }
    
    /**
     * Sets up a replica node that connects to the primary.
     */
    private static void setupReplicaNode() throws IOException {
        System.out.println("\n--- Setting up Replica Node ---");
        
        // Create cache engine and WAL
        CacheEngine cacheEngine = new CacheEngine(1000, new com.fastcache.core.EvictionPolicy.LRU());
        WriteAheadLog wal = new WriteAheadLog("/tmp/fastcache/replica", "replica-node");
        ServiceDiscovery serviceDiscovery = new ServiceDiscovery();
        
        // Configure replication
        ReplicationConfig config = ReplicationConfig.builder()
                .heartbeatInterval(5000)
                .timeoutMs(30000)
                .build();
        
        // Create replication manager
        ReplicationManager replicationManager = new ReplicationManager(
                "replica-node", "localhost", 6380, cacheEngine, wal, serviceDiscovery, config);
        
        // Start replication manager
        replicationManager.start();
        
        // Register with service discovery
        serviceDiscovery.registerNode("replica-node", "localhost", 6380, "cache");
        
        // Connect to primary (in a real scenario, you'd get this from service discovery)
        CompletableFuture<Boolean> connectionFuture = replicationManager.connectToPrimary(
                "primary-node", "localhost", 6379);
        
        try {
            boolean connected = connectionFuture.get(30, TimeUnit.SECONDS);
            if (connected) {
                System.out.println("Replica successfully connected to primary");
                System.out.println("Replication info: " + replicationManager.getReplicationInfo());
            } else {
                System.err.println("Failed to connect replica to primary");
            }
        } catch (Exception e) {
            System.err.println("Error connecting replica to primary: " + e.getMessage());
        }
    }
    
    /**
     * Demonstrates replication by writing to primary and reading from replica.
     */
    private static void demonstrateReplication() {
        System.out.println("\n--- Demonstrating Replication ---");
        
        // In a real scenario, you would:
        // 1. Write data to the primary node
        // 2. Verify the data is replicated to the replica node
        // 3. Test failover scenarios
        
        System.out.println("Replication demonstration would include:");
        System.out.println("1. Writing data to primary node");
        System.out.println("2. Verifying data appears on replica node");
        System.out.println("3. Testing automatic failover");
        System.out.println("4. Testing replica promotion to primary");
        
        // Example replication commands that would be used:
        System.out.println("\nExample replication commands:");
        System.out.println("- Primary writes: SET key1 value1");
        System.out.println("- Replica reads: GET key1 (should return value1)");
        System.out.println("- Primary fails: Replica promotes to primary");
        System.out.println("- New replica connects: Full sync with new primary");
    }
    
    /**
     * Utility method to print replication statistics.
     */
    private static void printReplicationStats(ReplicationManager manager) {
        ReplicationInfo info = manager.getReplicationInfo();
        System.out.println("Replication Statistics:");
        System.out.println("  Role: " + info.getRole());
        System.out.println("  State: " + info.getState());
        System.out.println("  Replication ID: " + info.getReplicationId());
        System.out.println("  Replication Offset: " + info.getReplicationOffset());
        
        if ("primary".equals(info.getRole())) {
            System.out.println("  Connected Replicas: " + info.getConnectedReplicas());
        } else {
            System.out.println("  Primary Node: " + info.getPrimaryNodeId());
        }
        
        System.out.println("  Backlog Active: " + info.isBacklogActive());
        System.out.println("  Backlog Length: " + info.getBacklogLen());
        System.out.println("  Replication Lag: " + info.getReplicationLag() + "ms");
    }
} 