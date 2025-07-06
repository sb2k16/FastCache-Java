package com.fastcache.discovery;

import com.fastcache.cluster.CacheNode;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Service Discovery component for FastCache that provides dynamic node registration
 * and discovery capabilities. This allows nodes to register themselves and proxies
 * to discover available nodes dynamically.
 */
public class ServiceDiscovery {
    
    private final Map<String, ServiceNode> registeredNodes = new ConcurrentHashMap<>();
    private final Map<String, ServiceNode> healthyNodes = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupExecutor;
    private final int healthCheckIntervalMs;
    private final int nodeTimeoutMs;
    
    public ServiceDiscovery() {
        this(30000, 60000); // 30s health check, 60s timeout
    }
    
    public ServiceDiscovery(int healthCheckIntervalMs, int nodeTimeoutMs) {
        this.healthCheckIntervalMs = healthCheckIntervalMs;
        this.nodeTimeoutMs = nodeTimeoutMs;
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "service-discovery-cleanup");
            t.setDaemon(true);
            return t;
        });
        
        // Start periodic cleanup of stale nodes
        this.cleanupExecutor.scheduleAtFixedRate(this::cleanupStaleNodes, 
            healthCheckIntervalMs, healthCheckIntervalMs, TimeUnit.MILLISECONDS);
        
        System.out.println("ServiceDiscovery initialized with healthCheckInterval=" + 
            healthCheckIntervalMs + "ms, nodeTimeout=" + nodeTimeoutMs + "ms");
    }
    
    /**
     * Registers a new node with the service discovery.
     * @param nodeId Unique identifier for the node
     * @param host Host address
     * @param port Port number
     * @param nodeType Type of node (CACHE, PROXY, etc.)
     * @return true if registration was successful
     */
    public boolean registerNode(String nodeId, String host, int port, String nodeType) {
        ServiceNode node = new ServiceNode(nodeId, host, port, nodeType, Instant.now());
        registeredNodes.put(nodeId, node);
        System.out.println("Registered node: " + nodeId + " (" + host + ":" + port + ")");
        return true;
    }
    
    /**
     * Deregisters a node from service discovery.
     * @param nodeId Node identifier to deregister
     * @return true if deregistration was successful
     */
    public boolean deregisterNode(String nodeId) {
        ServiceNode removed = registeredNodes.remove(nodeId);
        healthyNodes.remove(nodeId);
        if (removed != null) {
            System.out.println("Deregistered node: " + nodeId);
            return true;
        }
        return false;
    }
    
    /**
     * Updates the health status of a node.
     * @param nodeId Node identifier
     * @param healthy Health status
     * @return true if update was successful
     */
    public boolean updateNodeHealth(String nodeId, boolean healthy) {
        ServiceNode node = registeredNodes.get(nodeId);
        if (node != null) {
            node.updateHealth(healthy);
            if (healthy) {
                healthyNodes.put(nodeId, node);
            } else {
                healthyNodes.remove(nodeId);
            }
            return true;
        }
        return false;
    }
    
    /**
     * Updates the role of a node (for replication purposes).
     * @param nodeId Node identifier
     * @param role Node role (e.g., "primary", "replica")
     * @return true if the node was found and updated
     */
    public boolean updateNodeRole(String nodeId, String role) {
        ServiceNode node = registeredNodes.get(nodeId);
        if (node != null) {
            node.updateRole(role);
            return true;
        }
        return false;
    }
    
    /**
     * Heartbeat from a node to indicate it's still alive.
     * @param nodeId Node identifier
     * @return true if heartbeat was processed
     */
    public boolean heartbeat(String nodeId) {
        ServiceNode node = registeredNodes.get(nodeId);
        if (node != null) {
            node.updateLastSeen();
            return true;
        }
        return false;
    }
    
    /**
     * Gets all registered nodes.
     * @return List of all registered nodes
     */
    public List<ServiceNode> getAllNodes() {
        return new ArrayList<>(registeredNodes.values());
    }
    
    /**
     * Gets all healthy nodes.
     * @return List of healthy nodes
     */
    public List<ServiceNode> getHealthyNodes() {
        return new ArrayList<>(healthyNodes.values());
    }
    
    /**
     * Gets healthy nodes of a specific type.
     * @param nodeType Type of nodes to return
     * @return List of healthy nodes of the specified type
     */
    public List<ServiceNode> getHealthyNodesByType(String nodeType) {
        return healthyNodes.values().stream()
            .filter(node -> nodeType.equals(node.getNodeType()))
            .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }
    
    /**
     * Gets a specific node by ID.
     * @param nodeId Node identifier
     * @return ServiceNode if found, null otherwise
     */
    public ServiceNode getNode(String nodeId) {
        return registeredNodes.get(nodeId);
    }
    
    /**
     * Converts ServiceNode to CacheNode for use with consistent hashing.
     * @param nodeType Type of nodes to convert
     * @return List of CacheNode objects
     */
    public List<CacheNode> getCacheNodes(String nodeType) {
        List<CacheNode> cacheNodes = new ArrayList<>();
        for (ServiceNode node : getHealthyNodesByType(nodeType)) {
            cacheNodes.add(new CacheNode(node.getNodeId(), node.getHost(), node.getPort()));
        }
        return cacheNodes;
    }
    
    /**
     * Gets service discovery statistics.
     * @return ServiceDiscoveryStats object
     */
    public ServiceDiscoveryStats getStats() {
        int totalNodes = registeredNodes.size();
        int healthyNodes = this.healthyNodes.size();
        int unhealthyNodes = totalNodes - healthyNodes;
        
        Map<String, Integer> nodesByType = new HashMap<>();
        for (ServiceNode node : registeredNodes.values()) {
            String type = node.getNodeType();
            nodesByType.put(type, nodesByType.getOrDefault(type, 0) + 1);
        }
        
        return new ServiceDiscoveryStats(totalNodes, healthyNodes, unhealthyNodes, nodesByType);
    }
    
    /**
     * Cleans up stale nodes that haven't sent heartbeats.
     */
    private void cleanupStaleNodes() {
        Instant cutoff = Instant.now().minusMillis(nodeTimeoutMs);
        List<String> staleNodes = new ArrayList<>();
        
        for (Map.Entry<String, ServiceNode> entry : registeredNodes.entrySet()) {
            if (entry.getValue().getLastSeen().isBefore(cutoff)) {
                staleNodes.add(entry.getKey());
            }
        }
        
        for (String nodeId : staleNodes) {
            System.out.println("Removing stale node: " + nodeId);
            deregisterNode(nodeId);
        }
    }
    
    /**
     * Shuts down the service discovery component.
     */
    public void shutdown() {
        if (cleanupExecutor != null) {
            cleanupExecutor.shutdown();
            try {
                if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    cleanupExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                cleanupExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        System.out.println("ServiceDiscovery shutdown complete");
    }
    
    /**
     * Represents a service node in the discovery system.
     */
    public static class ServiceNode {
        private final String nodeId;
        private final String host;
        private final int port;
        private final String nodeType;
        private final Instant registeredAt;
        private volatile boolean healthy;
        private volatile Instant lastSeen;
        private volatile Instant lastHealthCheck;
        
        public ServiceNode(String nodeId, String host, int port, String nodeType, Instant registeredAt) {
            this.nodeId = nodeId;
            this.host = host;
            this.port = port;
            this.nodeType = nodeType;
            this.registeredAt = registeredAt;
            this.lastSeen = registeredAt;
            this.lastHealthCheck = registeredAt;
            this.healthy = true; // Assume healthy initially
        }
        
        public void updateHealth(boolean healthy) {
            this.healthy = healthy;
            this.lastHealthCheck = Instant.now();
        }
        
        public void updateLastSeen() {
            this.lastSeen = Instant.now();
        }
        
        public void updateRole(String role) {
            // For now, we'll just log the role update
            // In a full implementation, you might want to store the role
            System.out.println("Node " + nodeId + " role updated to: " + role);
        }
        
        // Getters
        public String getNodeId() { return nodeId; }
        public String getHost() { return host; }
        public int getPort() { return port; }
        public String getNodeType() { return nodeType; }
        public Instant getRegisteredAt() { return registeredAt; }
        public boolean isHealthy() { return healthy; }
        public Instant getLastSeen() { return lastSeen; }
        public Instant getLastHealthCheck() { return lastHealthCheck; }
        
        @Override
        public String toString() {
            return String.format("ServiceNode{id=%s, host=%s, port=%d, type=%s, healthy=%s}", 
                nodeId, host, port, nodeType, healthy);
        }
    }
    
    /**
     * Statistics for the service discovery system.
     */
    public static class ServiceDiscoveryStats {
        private final int totalNodes;
        private final int healthyNodes;
        private final int unhealthyNodes;
        private final Map<String, Integer> nodesByType;
        private final Instant timestamp;
        
        public ServiceDiscoveryStats(int totalNodes, int healthyNodes, int unhealthyNodes, 
                                   Map<String, Integer> nodesByType) {
            this.totalNodes = totalNodes;
            this.healthyNodes = healthyNodes;
            this.unhealthyNodes = unhealthyNodes;
            this.nodesByType = new HashMap<>(nodesByType);
            this.timestamp = Instant.now();
        }
        
        // Getters
        public int getTotalNodes() { return totalNodes; }
        public int getHealthyNodes() { return healthyNodes; }
        public int getUnhealthyNodes() { return unhealthyNodes; }
        public Map<String, Integer> getNodesByType() { return nodesByType; }
        public Instant getTimestamp() { return timestamp; }
        
        @Override
        public String toString() {
            return String.format("ServiceDiscoveryStats{total=%d, healthy=%d, unhealthy=%d, types=%s, time=%s}", 
                totalNodes, healthyNodes, unhealthyNodes, nodesByType, timestamp);
        }
    }
} 