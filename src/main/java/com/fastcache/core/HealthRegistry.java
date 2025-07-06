package com.fastcache.core;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Registry for managing health status of all nodes and proxies in the cluster.
 */
public class HealthRegistry {
    private final Map<String, HealthCheck> healthStatus;
    private final Map<String, Instant> lastUpdate;
    
    public HealthRegistry() {
        this.healthStatus = new ConcurrentHashMap<>();
        this.lastUpdate = new ConcurrentHashMap<>();
    }
    
    /**
     * Update health status for a node or proxy.
     */
    public void updateHealth(HealthCheck healthCheck) {
        String key = healthCheck.getNodeId();
        healthStatus.put(key, healthCheck);
        lastUpdate.put(key, Instant.now());
    }
    
    /**
     * Get health status for a specific node or proxy.
     */
    public HealthCheck getHealth(String nodeId) {
        return healthStatus.get(nodeId);
    }
    
    /**
     * Get all health statuses.
     */
    public Map<String, HealthCheck> getAllHealth() {
        return new ConcurrentHashMap<>(healthStatus);
    }
    
    /**
     * Get health status for all nodes.
     */
    public List<HealthCheck> getNodeHealth() {
        return healthStatus.values().stream()
                .filter(HealthCheck::isNode)
                .collect(Collectors.toList());
    }
    
    /**
     * Get health status for all proxies.
     */
    public List<HealthCheck> getProxyHealth() {
        return healthStatus.values().stream()
                .filter(HealthCheck::isProxy)
                .collect(Collectors.toList());
    }
    
    /**
     * Get healthy nodes.
     */
    public List<HealthCheck> getHealthyNodes() {
        return healthStatus.values().stream()
                .filter(HealthCheck::isNode)
                .filter(HealthCheck::isHealthy)
                .collect(Collectors.toList());
    }
    
    /**
     * Get healthy proxies.
     */
    public List<HealthCheck> getHealthyProxies() {
        return healthStatus.values().stream()
                .filter(HealthCheck::isProxy)
                .filter(HealthCheck::isHealthy)
                .collect(Collectors.toList());
    }
    
    /**
     * Get unhealthy nodes.
     */
    public List<HealthCheck> getUnhealthyNodes() {
        return healthStatus.values().stream()
                .filter(HealthCheck::isNode)
                .filter(h -> !h.isHealthy())
                .collect(Collectors.toList());
    }
    
    /**
     * Get unhealthy proxies.
     */
    public List<HealthCheck> getUnhealthyProxies() {
        return healthStatus.values().stream()
                .filter(HealthCheck::isProxy)
                .filter(h -> !h.isHealthy())
                .collect(Collectors.toList());
    }
    
    /**
     * Check if a node is healthy.
     */
    public boolean isNodeHealthy(String nodeId) {
        HealthCheck health = healthStatus.get(nodeId);
        return health != null && health.isNode() && health.isHealthy();
    }
    
    /**
     * Check if a proxy is healthy.
     */
    public boolean isProxyHealthy(String proxyId) {
        HealthCheck health = healthStatus.get(proxyId);
        return health != null && health.isProxy() && health.isHealthy();
    }
    
    /**
     * Get cluster health summary.
     */
    public ClusterHealthSummary getClusterHealthSummary() {
        List<HealthCheck> allHealth = healthStatus.values().stream().collect(Collectors.toList());
        
        long totalNodes = allHealth.stream().filter(HealthCheck::isNode).count();
        long healthyNodes = allHealth.stream().filter(HealthCheck::isNode).filter(HealthCheck::isHealthy).count();
        
        long totalProxies = allHealth.stream().filter(HealthCheck::isProxy).count();
        long healthyProxies = allHealth.stream().filter(HealthCheck::isProxy).filter(HealthCheck::isHealthy).count();
        
        return new ClusterHealthSummary(
            (int) totalNodes, (int) healthyNodes,
            (int) totalProxies, (int) healthyProxies,
            Instant.now()
        );
    }
    
    /**
     * Remove health status for a node or proxy.
     */
    public void removeHealth(String nodeId) {
        healthStatus.remove(nodeId);
        lastUpdate.remove(nodeId);
    }
    
    /**
     * Clear all health statuses.
     */
    public void clear() {
        healthStatus.clear();
        lastUpdate.clear();
    }
    
    /**
     * Get the number of registered nodes and proxies.
     */
    public int size() {
        return healthStatus.size();
    }
    
    /**
     * Cluster health summary.
     */
    public static class ClusterHealthSummary {
        private final int totalNodes;
        private final int healthyNodes;
        private final int totalProxies;
        private final int healthyProxies;
        private final Instant timestamp;
        
        public ClusterHealthSummary(int totalNodes, int healthyNodes, 
                                  int totalProxies, int healthyProxies, 
                                  Instant timestamp) {
            this.totalNodes = totalNodes;
            this.healthyNodes = healthyNodes;
            this.totalProxies = totalProxies;
            this.healthyProxies = healthyProxies;
            this.timestamp = timestamp;
        }
        
        public int getTotalNodes() { return totalNodes; }
        public int getHealthyNodes() { return healthyNodes; }
        public int getTotalProxies() { return totalProxies; }
        public int getHealthyProxies() { return healthyProxies; }
        public Instant getTimestamp() { return timestamp; }
        
        public double getNodeHealthPercentage() {
            return totalNodes > 0 ? (double) healthyNodes / totalNodes * 100 : 0.0;
        }
        
        public double getProxyHealthPercentage() {
            return totalProxies > 0 ? (double) healthyProxies / totalProxies * 100 : 0.0;
        }
        
        public boolean isClusterHealthy() {
            return healthyNodes > 0 && healthyProxies > 0;
        }
        
        @Override
        public String toString() {
            return String.format("ClusterHealthSummary{nodes=%d/%d (%.1f%%), proxies=%d/%d (%.1f%%), timestamp=%s}",
                    healthyNodes, totalNodes, getNodeHealthPercentage(),
                    healthyProxies, totalProxies, getProxyHealthPercentage(),
                    timestamp);
        }
    }
} 