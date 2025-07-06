package com.fastcache.core;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Health check information for FastCache nodes and proxies.
 */
public class HealthCheck {
    public enum Status {
        HEALTHY,
        UNHEALTHY,
        UNKNOWN
    }
    
    private final String nodeId;
    private final String nodeType; // "node" or "proxy"
    private final String host;
    private final int port;
    private final Status status;
    private final Instant lastCheck;
    private final Instant lastHealthy;
    private final Map<String, Object> metrics;
    private final String errorMessage;
    
    public HealthCheck(String nodeId, String nodeType, String host, int port, 
                      Status status, String errorMessage) {
        this.nodeId = nodeId;
        this.nodeType = nodeType;
        this.host = host;
        this.port = port;
        this.status = status;
        this.lastCheck = Instant.now();
        this.lastHealthy = status == Status.HEALTHY ? this.lastCheck : null;
        this.metrics = new ConcurrentHashMap<>();
        this.errorMessage = errorMessage;
    }
    
    public HealthCheck(String nodeId, String nodeType, String host, int port, Status status) {
        this(nodeId, nodeType, host, port, status, null);
    }
    
    // Getters
    public String getNodeId() { return nodeId; }
    public String getNodeType() { return nodeType; }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public Status getStatus() { return status; }
    public Instant getLastCheck() { return lastCheck; }
    public Instant getLastHealthy() { return lastHealthy; }
    public Map<String, Object> getMetrics() { return metrics; }
    public String getErrorMessage() { return errorMessage; }
    
    public boolean isHealthy() {
        return status == Status.HEALTHY;
    }
    
    public boolean isNode() {
        return "node".equals(nodeType);
    }
    
    public boolean isProxy() {
        return "proxy".equals(nodeType);
    }
    
    public void addMetric(String key, Object value) {
        metrics.put(key, value);
    }
    
    @Override
    public String toString() {
        return String.format("HealthCheck{nodeId='%s', type='%s', host='%s', port=%d, status=%s, lastCheck=%s}",
                nodeId, nodeType, host, port, status, lastCheck);
    }
} 