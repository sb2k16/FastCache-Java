package com.fastcache.replication;

import java.time.Duration;

/**
 * Configuration for Redis-inspired replication.
 * Similar to Redis replication configuration options.
 */
public class ReplicationConfig {
    
    // Connection settings
    private String authPassword = null;
    private int timeoutMs = 60000; // 60 seconds
    private int heartbeatInterval = 10000; // 10 seconds
    private int replicaCheckInterval = 30000; // 30 seconds
    private int replicaTimeoutMs = 60000; // 60 seconds
    
    // Replication settings
    private int backlogSize = 1024 * 1024; // 1MB
    private int backlogTtl = 3600; // 1 hour
    private boolean disklessSync = false;
    private int disklessSyncDelay = 5; // seconds
    private boolean disableTcpNodelay = false;
    
    // Performance settings
    private int replicationFactor = 2;
    private boolean enableSynchronousReplication = false;
    private int minReplicasToWrite = 1;
    private int minReplicasMaxLag = 10; // seconds
    
    // Failover settings
    private boolean enableAutomaticFailover = true;
    private int failoverTimeoutMs = 30000; // 30 seconds
    private int electionTimeoutMs = 5000; // 5 seconds
    
    public ReplicationConfig() {}
    
    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private ReplicationConfig config = new ReplicationConfig();
        
        public Builder authPassword(String password) {
            config.authPassword = password;
            return this;
        }
        
        public Builder timeoutMs(int timeoutMs) {
            config.timeoutMs = timeoutMs;
            return this;
        }
        
        public Builder heartbeatInterval(int interval) {
            config.heartbeatInterval = interval;
            return this;
        }
        
        public Builder backlogSize(int size) {
            config.backlogSize = size;
            return this;
        }
        
        public Builder replicationFactor(int factor) {
            config.replicationFactor = factor;
            return this;
        }
        
        public Builder enableSynchronousReplication(boolean enable) {
            config.enableSynchronousReplication = enable;
            return this;
        }
        
        public Builder enableAutomaticFailover(boolean enable) {
            config.enableAutomaticFailover = enable;
            return this;
        }
        
        public ReplicationConfig build() {
            return config;
        }
    }
    
    // Getters
    public String getAuthPassword() { return authPassword; }
    public int getTimeoutMs() { return timeoutMs; }
    public int getHeartbeatInterval() { return heartbeatInterval; }
    public int getReplicaCheckInterval() { return replicaCheckInterval; }
    public int getReplicaTimeoutMs() { return replicaTimeoutMs; }
    public int getBacklogSize() { return backlogSize; }
    public int getBacklogTtl() { return backlogTtl; }
    public boolean isDisklessSync() { return disklessSync; }
    public int getDisklessSyncDelay() { return disklessSyncDelay; }
    public boolean isDisableTcpNodelay() { return disableTcpNodelay; }
    public int getReplicationFactor() { return replicationFactor; }
    public boolean isEnableSynchronousReplication() { return enableSynchronousReplication; }
    public int getMinReplicasToWrite() { return minReplicasToWrite; }
    public int getMinReplicasMaxLag() { return minReplicasMaxLag; }
    public boolean isEnableAutomaticFailover() { return enableAutomaticFailover; }
    public int getFailoverTimeoutMs() { return failoverTimeoutMs; }
    public int getElectionTimeoutMs() { return electionTimeoutMs; }
    
    // Setters
    public void setAuthPassword(String authPassword) { this.authPassword = authPassword; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    public void setHeartbeatInterval(int heartbeatInterval) { this.heartbeatInterval = heartbeatInterval; }
    public void setReplicaCheckInterval(int replicaCheckInterval) { this.replicaCheckInterval = replicaCheckInterval; }
    public void setReplicaTimeoutMs(int replicaTimeoutMs) { this.replicaTimeoutMs = replicaTimeoutMs; }
    public void setBacklogSize(int backlogSize) { this.backlogSize = backlogSize; }
    public void setBacklogTtl(int backlogTtl) { this.backlogTtl = backlogTtl; }
    public void setDisklessSync(boolean disklessSync) { this.disklessSync = disklessSync; }
    public void setDisklessSyncDelay(int disklessSyncDelay) { this.disklessSyncDelay = disklessSyncDelay; }
    public void setDisableTcpNodelay(boolean disableTcpNodelay) { this.disableTcpNodelay = disableTcpNodelay; }
    public void setReplicationFactor(int replicationFactor) { this.replicationFactor = replicationFactor; }
    public void setEnableSynchronousReplication(boolean enableSynchronousReplication) { this.enableSynchronousReplication = enableSynchronousReplication; }
    public void setMinReplicasToWrite(int minReplicasToWrite) { this.minReplicasToWrite = minReplicasToWrite; }
    public void setMinReplicasMaxLag(int minReplicasMaxLag) { this.minReplicasMaxLag = minReplicasMaxLag; }
    public void setEnableAutomaticFailover(boolean enableAutomaticFailover) { this.enableAutomaticFailover = enableAutomaticFailover; }
    public void setFailoverTimeoutMs(int failoverTimeoutMs) { this.failoverTimeoutMs = failoverTimeoutMs; }
    public void setElectionTimeoutMs(int electionTimeoutMs) { this.electionTimeoutMs = electionTimeoutMs; }
} 