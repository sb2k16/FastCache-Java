package com.fastcache.cluster;

import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * Represents a cache node in the distributed cache system.
 */
public class CacheNode {
    private final String id;
    private final String host;
    private final int port;
    private final InetSocketAddress address;
    private volatile NodeStatus status;
    private volatile long lastHeartbeat;
    private volatile int weight;
    
    public enum NodeStatus {
        ACTIVE, INACTIVE, FAILED, JOINING, LEAVING
    }
    
    public CacheNode(String id, String host, int port) {
        this(id, host, port, 1);
    }
    
    public CacheNode(String id, String host, int port, int weight) {
        this.id = Objects.requireNonNull(id, "Node ID cannot be null");
        this.host = Objects.requireNonNull(host, "Host cannot be null");
        this.port = port;
        this.weight = weight;
        this.address = new InetSocketAddress(host, port);
        this.status = NodeStatus.ACTIVE;
        this.lastHeartbeat = System.currentTimeMillis();
    }
    
    public String getId() {
        return id;
    }
    
    public String getHost() {
        return host;
    }
    
    public int getPort() {
        return port;
    }
    
    public InetSocketAddress getAddress() {
        return address;
    }
    
    public NodeStatus getStatus() {
        return status;
    }
    
    public void setStatus(NodeStatus status) {
        this.status = status;
    }
    
    public long getLastHeartbeat() {
        return lastHeartbeat;
    }
    
    public void updateHeartbeat() {
        this.lastHeartbeat = System.currentTimeMillis();
    }
    
    public int getWeight() {
        return weight;
    }
    
    public void setWeight(int weight) {
        this.weight = weight;
    }
    
    public boolean isActive() {
        return status == NodeStatus.ACTIVE;
    }
    
    public boolean isFailed() {
        return status == NodeStatus.FAILED;
    }
    
    public String getConnectionString() {
        return host + ":" + port;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CacheNode cacheNode = (CacheNode) o;
        return Objects.equals(id, cacheNode.id);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
    
    @Override
    public String toString() {
        return "CacheNode{" +
                "id='" + id + '\'' +
                ", host='" + host + '\'' +
                ", port=" + port +
                ", status=" + status +
                ", weight=" + weight +
                '}';
    }
} 