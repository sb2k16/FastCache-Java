package com.fastcache.proxy;

import com.fastcache.core.HealthCheck;
import com.fastcache.core.HealthChecker;
import com.fastcache.core.HealthRegistry;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages multiple FastCache proxy instances with health monitoring and load balancing.
 */
public class MultiProxyManager {
    private final HealthRegistry healthRegistry;
    private final HealthChecker healthChecker;
    private final AtomicInteger requestCounter;
    
    public MultiProxyManager() {
        this.healthRegistry = new HealthRegistry();
        this.healthChecker = new HealthChecker(healthRegistry::updateHealth);
        this.requestCounter = new AtomicInteger(0);
    }
    
    /**
     * Register a proxy instance for health monitoring.
     */
    public void registerProxy(String proxyId, String host, int port) {
        healthChecker.startHealthCheck(proxyId, "proxy", host, port);
    }
    
    /**
     * Register a node for health monitoring.
     */
    public void registerNode(String nodeId, String host, int port) {
        healthChecker.startHealthCheck(nodeId, "node", host, port);
    }
    
    /**
     * Get a healthy proxy for load balancing.
     */
    public HealthCheck getHealthyProxy() {
        List<HealthCheck> healthyProxies = healthRegistry.getHealthyProxies();
        if (healthyProxies.isEmpty()) {
            return null;
        }
        
        // Simple round-robin load balancing
        int index = requestCounter.getAndIncrement() % healthyProxies.size();
        return healthyProxies.get(index);
    }
    
    /**
     * Get all healthy nodes.
     */
    public List<HealthCheck> getHealthyNodes() {
        return healthRegistry.getHealthyNodes();
    }
    
    /**
     * Get cluster health summary.
     */
    public HealthRegistry.ClusterHealthSummary getClusterHealthSummary() {
        return healthRegistry.getClusterHealthSummary();
    }
    
    /**
     * Check if cluster is healthy.
     */
    public boolean isClusterHealthy() {
        return healthRegistry.getClusterHealthSummary().isClusterHealthy();
    }
    
    /**
     * Get health status for a specific proxy.
     */
    public HealthCheck getProxyHealth(String proxyId) {
        return healthRegistry.getHealth(proxyId);
    }
    
    /**
     * Get health status for a specific node.
     */
    public HealthCheck getNodeHealth(String nodeId) {
        return healthRegistry.getHealth(nodeId);
    }
    
    /**
     * Get all proxy health statuses.
     */
    public List<HealthCheck> getAllProxyHealth() {
        return healthRegistry.getProxyHealth();
    }
    
    /**
     * Get all node health statuses.
     */
    public List<HealthCheck> getAllNodeHealth() {
        return healthRegistry.getNodeHealth();
    }
    
    /**
     * Perform a health check on a specific proxy.
     */
    public CompletableFuture<HealthCheck> checkProxyHealth(String proxyId, String host, int port) {
        return healthChecker.performHealthCheckAsync(proxyId, "proxy", host, port);
    }
    
    /**
     * Perform a health check on a specific node.
     */
    public CompletableFuture<HealthCheck> checkNodeHealth(String nodeId, String host, int port) {
        return healthChecker.performHealthCheckAsync(nodeId, "node", host, port);
    }
    
    /**
     * Remove a proxy from health monitoring.
     */
    public void removeProxy(String proxyId) {
        healthRegistry.removeHealth(proxyId);
    }
    
    /**
     * Remove a node from health monitoring.
     */
    public void removeNode(String nodeId) {
        healthRegistry.removeHealth(nodeId);
    }
    
    /**
     * Shutdown the health checker.
     */
    public void shutdown() {
        healthChecker.shutdown();
    }
    
    /**
     * Get the number of healthy proxies.
     */
    public int getHealthyProxyCount() {
        return healthRegistry.getHealthyProxies().size();
    }
    
    /**
     * Get the number of healthy nodes.
     */
    public int getHealthyNodeCount() {
        return healthRegistry.getHealthyNodes().size();
    }
    
    /**
     * Get the total number of registered proxies.
     */
    public int getTotalProxyCount() {
        return healthRegistry.getProxyHealth().size();
    }
    
    /**
     * Get the total number of registered nodes.
     */
    public int getTotalNodeCount() {
        return healthRegistry.getNodeHealth().size();
    }
} 