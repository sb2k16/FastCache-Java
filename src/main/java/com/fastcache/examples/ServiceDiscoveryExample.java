package com.fastcache.examples;

import com.fastcache.cluster.CacheNode;
import com.fastcache.cluster.ConsistentHash;
import com.fastcache.discovery.ServiceDiscoveryClient;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Example demonstrating the use of Service Discovery for dynamic node registration and discovery.
 * This example shows how nodes can register themselves and how proxies can discover them.
 */
public class ServiceDiscoveryExample {
    
    public static void main(String[] args) {
        System.out.println("=== FastCache Service Discovery Example ===");
        
        // Start the service discovery API (in a real scenario, this would be a separate service)
        System.out.println("Note: Service Discovery API should be running on http://localhost:8081");
        
        // Create a service discovery client
        ServiceDiscoveryClient discoveryClient = new ServiceDiscoveryClient("http://localhost:8081", 3000);
        
        try {
            // Check if service discovery is available
            if (!discoveryClient.isAvailable()) {
                System.out.println("Service Discovery API is not available. Please start it first.");
                System.out.println("You can start it by running: java -cp build/libs/FastCache-1.0.0-fat.jar com.fastcache.discovery.ServiceDiscoveryAPI");
                return;
            }
            
            System.out.println("Service Discovery API is available!");
            
            // Example 1: Register cache nodes
            System.out.println("\n--- Example 1: Registering Cache Nodes ---");
            registerCacheNodes(discoveryClient);
            
            // Example 2: Start periodic discovery
            System.out.println("\n--- Example 2: Dynamic Node Discovery ---");
            startDynamicDiscovery(discoveryClient);
            
            // Example 3: Simulate node failures and recovery
            System.out.println("\n--- Example 3: Node Failure Simulation ---");
            simulateNodeFailures(discoveryClient);
            
            // Keep the example running for a while to see the dynamic behavior
            System.out.println("\n--- Monitoring for 30 seconds ---");
            Thread.sleep(30000);
            
        } catch (Exception e) {
            System.err.println("Error in service discovery example: " + e.getMessage());
            e.printStackTrace();
        } finally {
            discoveryClient.shutdown();
            System.out.println("Service Discovery Example completed.");
        }
    }
    
    /**
     * Example: Register multiple cache nodes with the service discovery.
     */
    private static void registerCacheNodes(ServiceDiscoveryClient client) {
        System.out.println("Registering cache nodes...");
        
        // Register 3 cache nodes
        @SuppressWarnings("unchecked")
        CompletableFuture<Boolean>[] registrations = (CompletableFuture<Boolean>[]) new CompletableFuture[3];
        
        registrations[0] = client.registerNode("node1", "localhost", 7001, "CACHE");
        registrations[1] = client.registerNode("node2", "localhost", 7002, "CACHE");
        registrations[2] = client.registerNode("node3", "localhost", 7003, "CACHE");
        
        // Wait for all registrations to complete
        CompletableFuture.allOf(registrations).join();
        
        for (int i = 0; i < registrations.length; i++) {
            try {
                boolean success = registrations[i].get();
                System.out.println("Node" + (i + 1) + " registration: " + (success ? "SUCCESS" : "FAILED"));
            } catch (Exception e) {
                System.err.println("Node" + (i + 1) + " registration failed: " + e.getMessage());
            }
        }
    }
    
    /**
     * Example: Start dynamic discovery and show how proxies can use it.
     */
    private static void startDynamicDiscovery(ServiceDiscoveryClient client) {
        System.out.println("Starting dynamic node discovery...");
        
        // Start periodic refresh
        client.startPeriodicRefresh();
        
        // Show initial nodes
        showCurrentNodes(client);
        
        // Simulate a proxy using the discovered nodes
        simulateProxyUsage(client);
    }
    
    /**
     * Example: Simulate node failures and recovery.
     */
    private static void simulateNodeFailures(ServiceDiscoveryClient client) {
        System.out.println("Simulating node failures and recovery...");
        
        // Simulate node2 going down
        System.out.println("Simulating node2 failure...");
        try {
            // In a real scenario, this would be done by the health checker
            // For this example, we'll just show the concept
            Thread.sleep(2000);
            System.out.println("node2 is now considered unhealthy");
            
            // Show updated node list
            showCurrentNodes(client);
            
            // Simulate node2 recovery
            Thread.sleep(3000);
            System.out.println("node2 has recovered");
            showCurrentNodes(client);
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Helper method to show current nodes.
     */
    private static void showCurrentNodes(ServiceDiscoveryClient client) {
        try {
            List<CacheNode> nodes = client.getCachedNodes();
            System.out.println("Current cache nodes (" + nodes.size() + "):");
            for (CacheNode node : nodes) {
                System.out.println("  - " + node.getId() + " (" + node.getHost() + ":" + node.getPort() + ")");
            }
            System.out.println("Last refresh: " + client.getLastRefresh());
        } catch (Exception e) {
            System.err.println("Failed to get current nodes: " + e.getMessage());
        }
    }
    
    /**
     * Helper method to simulate proxy usage of discovered nodes.
     */
    private static void simulateProxyUsage(ServiceDiscoveryClient client) {
        System.out.println("Simulating proxy usage with discovered nodes...");
        
        try {
            List<CacheNode> nodes = client.getCachedNodes();
            if (nodes.isEmpty()) {
                System.out.println("No nodes available for proxy");
                return;
            }
            
            // Create a consistent hash ring with the discovered nodes
            ConsistentHash hashRing = new ConsistentHash(150);
            for (CacheNode node : nodes) {
                hashRing.addNode(node);
            }
            
            // Simulate routing some keys
            String[] testKeys = {"user:123", "product:456", "session:789", "cache:key1", "cache:key2"};
            
            System.out.println("Routing test keys:");
            for (String key : testKeys) {
                CacheNode responsibleNode = hashRing.getNode(key);
                if (responsibleNode != null) {
                    System.out.println("  " + key + " -> " + responsibleNode.getId());
                } else {
                    System.out.println("  " + key + " -> NO NODE AVAILABLE");
                }
            }
            
        } catch (Exception e) {
            System.err.println("Failed to simulate proxy usage: " + e.getMessage());
        }
    }
} 