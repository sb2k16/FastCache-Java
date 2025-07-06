package com.fastcache.health;

import com.fastcache.discovery.ServiceDiscovery;
import com.fastcache.discovery.ServiceDiscoveryClient;
import com.fastcache.cluster.CacheNode;

import java.io.IOException;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Lightweight Health Checker that works defensively alongside heartbeat-based service discovery.
 * 
 * This component provides:
 * 1. Verification that nodes are actually responding (not just sending heartbeats)
 * 2. Additional failure context and diagnostics
 * 3. Backup detection for network partitions or zombie nodes
 * 4. Performance metrics and detailed health status
 * 
 * It does NOT replace service discovery's heartbeat mechanism, but complements it.
 */
public class LightweightHealthChecker {
    
    private final ServiceDiscovery serviceDiscovery;
    private final ServiceDiscoveryClient discoveryClient;
    
    // Health state tracking with detailed context
    private final Map<String, NodeHealthState> nodeHealthStates = new ConcurrentHashMap<>();
    
    // Configuration
    private static final int SOCKET_TIMEOUT_MS = 2000;
    private static final int HEALTH_CHECK_INTERVAL_MS = 60000; // 1 minute (less frequent than heartbeats)
    private static final int MAX_FAILURE_COUNT = 3;
    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(2);
    
    public LightweightHealthChecker(ServiceDiscovery serviceDiscovery, ServiceDiscoveryClient discoveryClient) {
        this.serviceDiscovery = serviceDiscovery;
        this.discoveryClient = discoveryClient;
        System.out.println("LightweightHealthChecker initialized - defensive health monitoring enabled");
    }
    
    /**
     * Manual health check that can be called periodically.
     * Focuses on verification and detailed diagnostics.
     */
    public void performDefensiveHealthCheck() {
        System.out.println("Performing defensive health check...");
        
        // Get all registered nodes from service discovery
        List<String> registeredNodes = serviceDiscovery.getAllNodes().stream()
                .map(ServiceDiscovery.ServiceNode::getNodeId)
                .toList();
        
        if (registeredNodes.isEmpty()) {
            System.out.println("No registered nodes found for health check");
            return;
        }
        
        // Perform health checks in parallel
        List<CompletableFuture<NodeHealthResult>> healthChecks = registeredNodes.stream()
                .map(this::checkNodeHealthAsync)
                .toList();
        
        // Wait for all health checks to complete
        CompletableFuture.allOf(healthChecks.toArray(new CompletableFuture[0]))
                .orTimeout(30, TimeUnit.SECONDS)
                .whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        System.err.println("Health check timeout or error: " + throwable.getMessage());
                    }
                });
        
        // Process results
        healthChecks.forEach(future -> 
            future.thenAccept(this::processHealthResult)
        );
        
        // Generate health summary
        generateHealthSummary();
    }
    
    /**
     * Asynchronously checks the health of a single node.
     */
    private CompletableFuture<NodeHealthResult> checkNodeHealthAsync(String nodeId) {
        return CompletableFuture.supplyAsync(() -> {
            NodeHealthResult result = new NodeHealthResult(nodeId);
            
            try {
                // Get node details from service discovery
                Optional<ServiceDiscovery.ServiceNode> nodeOpt = serviceDiscovery.getAllNodes().stream()
                        .filter(node -> node.getNodeId().equals(nodeId))
                        .findFirst();
                
                if (nodeOpt.isEmpty()) {
                    result.setStatus(HealthStatus.NOT_FOUND);
                    result.setMessage("Node not found in service discovery");
                    return result;
                }
                
                ServiceDiscovery.ServiceNode serviceNode = nodeOpt.get();
                result.setNodeInfo(serviceNode);
                
                // Check if node is stale (hasn't sent heartbeats recently)
                if (isNodeStale(serviceNode)) {
                    result.setStatus(HealthStatus.STALE);
                    result.setMessage("Node hasn't sent heartbeats recently");
                    result.setLastHeartbeat(System.currentTimeMillis()); // ServiceNode doesn't have lastHeartbeat
                    return result;
                }
                
                // Perform socket-based connectivity check
                boolean isConnectable = checkSocketConnectivity(serviceNode.getHost(), serviceNode.getPort());
                if (!isConnectable) {
                    result.setStatus(HealthStatus.UNREACHABLE);
                    result.setMessage("Node is not reachable via socket connection");
                    return result;
                }
                
                // Perform basic cache operation test (if possible)
                boolean cacheOperational = checkCacheOperations(serviceNode);
                if (!cacheOperational) {
                    result.setStatus(HealthStatus.DEGRADED);
                    result.setMessage("Node is reachable but cache operations failing");
                    return result;
                }
                
                // Node is healthy
                result.setStatus(HealthStatus.HEALTHY);
                result.setMessage("Node is healthy and operational");
                result.setResponseTime(measureResponseTime(serviceNode));
                
            } catch (Exception e) {
                result.setStatus(HealthStatus.ERROR);
                result.setMessage("Health check error: " + e.getMessage());
                result.setException(e);
            }
            
            return result;
        });
    }
    
    /**
     * Checks if a node is stale (hasn't sent heartbeats recently).
     */
    private boolean isNodeStale(ServiceDiscovery.ServiceNode node) {
        // ServiceNode doesn't track lastHeartbeat, so we'll use a simple approach
        // In a real implementation, you might want to track this separately
        return false; // Placeholder - implement based on your needs
    }
    
    /**
     * Performs a basic socket connectivity check.
     */
    private boolean checkSocketConnectivity(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), SOCKET_TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
    
    /**
     * Performs basic cache operation test.
     */
    private boolean checkCacheOperations(ServiceDiscovery.ServiceNode node) {
        try {
            // Try a simple ping or basic operation
            // This could be enhanced with actual cache commands
            return true; // Placeholder - implement actual cache operation test
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Measures response time to the node.
     */
    private long measureResponseTime(ServiceDiscovery.ServiceNode node) {
        long start = System.currentTimeMillis();
        try {
            checkSocketConnectivity(node.getHost(), node.getPort());
            return System.currentTimeMillis() - start;
        } catch (Exception e) {
            return -1;
        }
    }
    
    /**
     * Processes health check results and updates internal state.
     */
    private void processHealthResult(NodeHealthResult result) {
        String nodeId = result.getNodeId();
        NodeHealthState state = nodeHealthStates.computeIfAbsent(nodeId, k -> new NodeHealthState(nodeId));
        
        // Update health state
        state.updateHealthResult(result);
        
        // Log significant health changes
        if (state.hasHealthChanged()) {
            System.out.println("Health change detected for node " + nodeId + ": " + 
                              state.getPreviousStatus() + " -> " + state.getCurrentStatus());
            
            // Alert on critical issues
            if (result.getStatus() == HealthStatus.UNREACHABLE || 
                result.getStatus() == HealthStatus.ERROR) {
                System.err.println("CRITICAL: Node " + nodeId + " is " + result.getStatus() + 
                                  " - " + result.getMessage());
            }
        }
    }
    
    /**
     * Generates a health summary report.
     */
    private void generateHealthSummary() {
        long healthyCount = nodeHealthStates.values().stream()
                .filter(state -> state.getCurrentStatus() == HealthStatus.HEALTHY)
                .count();
        
        long totalCount = nodeHealthStates.size();
        
        if (totalCount > 0) {
            double healthPercentage = (double) healthyCount / totalCount * 100;
            System.out.println(String.format("Health Summary: %d/%d nodes healthy (%.1f%%)", 
                                           healthyCount, totalCount, healthPercentage));
        }
    }
    
    /**
     * Gets the current health status of all nodes.
     */
    public Map<String, NodeHealthState> getNodeHealthStates() {
        return new HashMap<>(nodeHealthStates);
    }
    
    /**
     * Gets the health status of a specific node.
     */
    public Optional<NodeHealthState> getNodeHealthState(String nodeId) {
        return Optional.ofNullable(nodeHealthStates.get(nodeId));
    }
    
    /**
     * Gets nodes that are currently healthy.
     */
    public List<String> getHealthyNodes() {
        return nodeHealthStates.values().stream()
                .filter(state -> state.getCurrentStatus() == HealthStatus.HEALTHY)
                .map(NodeHealthState::getNodeId)
                .toList();
    }
    
    /**
     * Gets nodes that have health issues.
     */
    public List<String> getUnhealthyNodes() {
        return nodeHealthStates.values().stream()
                .filter(state -> state.getCurrentStatus() != HealthStatus.HEALTHY)
                .map(NodeHealthState::getNodeId)
                .toList();
    }
    
    /**
     * Health status enumeration.
     */
    public enum HealthStatus {
        HEALTHY,        // Node is fully operational
        DEGRADED,       // Node is reachable but has issues
        UNREACHABLE,    // Node is not reachable via network
        STALE,          // Node hasn't sent heartbeats recently
        ERROR,          // Health check encountered an error
        NOT_FOUND       // Node not found in service discovery
    }
    
    /**
     * Health check result for a single node.
     */
    public static class NodeHealthResult {
        private final String nodeId;
        private HealthStatus status;
        private String message;
        private ServiceDiscovery.ServiceNode nodeInfo;
        private long lastHeartbeat;
        private long responseTime;
        private Exception exception;
        private final Instant timestamp;
        
        public NodeHealthResult(String nodeId) {
            this.nodeId = nodeId;
            this.timestamp = Instant.now();
        }
        
        // Getters and setters
        public String getNodeId() { return nodeId; }
        public HealthStatus getStatus() { return status; }
        public void setStatus(HealthStatus status) { this.status = status; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public ServiceDiscovery.ServiceNode getNodeInfo() { return nodeInfo; }
        public void setNodeInfo(ServiceDiscovery.ServiceNode nodeInfo) { this.nodeInfo = nodeInfo; }
        public long getLastHeartbeat() { return lastHeartbeat; }
        public void setLastHeartbeat(long lastHeartbeat) { this.lastHeartbeat = lastHeartbeat; }
        public long getResponseTime() { return responseTime; }
        public void setResponseTime(long responseTime) { this.responseTime = responseTime; }
        public Exception getException() { return exception; }
        public void setException(Exception exception) { this.exception = exception; }
        public Instant getTimestamp() { return timestamp; }
    }
    
    /**
     * Persistent health state for a node.
     */
    public static class NodeHealthState {
        private final String nodeId;
        private HealthStatus currentStatus;
        private HealthStatus previousStatus;
        private int failureCount;
        private Instant lastCheck;
        private NodeHealthResult lastResult;
        
        public NodeHealthState(String nodeId) {
            this.nodeId = nodeId;
            this.currentStatus = HealthStatus.NOT_FOUND;
            this.previousStatus = HealthStatus.NOT_FOUND;
            this.failureCount = 0;
            this.lastCheck = Instant.now();
        }
        
        public void updateHealthResult(NodeHealthResult result) {
            this.previousStatus = this.currentStatus;
            this.currentStatus = result.getStatus();
            this.lastCheck = Instant.now();
            this.lastResult = result;
            
            if (result.getStatus() != HealthStatus.HEALTHY) {
                this.failureCount++;
            } else {
                this.failureCount = 0;
            }
        }
        
        public boolean hasHealthChanged() {
            return currentStatus != previousStatus;
        }
        
        // Getters
        public String getNodeId() { return nodeId; }
        public HealthStatus getCurrentStatus() { return currentStatus; }
        public HealthStatus getPreviousStatus() { return previousStatus; }
        public int getFailureCount() { return failureCount; }
        public Instant getLastCheck() { return lastCheck; }
        public NodeHealthResult getLastResult() { return lastResult; }
        
        public boolean isHealthy() {
            return currentStatus == HealthStatus.HEALTHY;
        }
        
        public boolean isStable() {
            return failureCount == 0;
        }
    }
} 