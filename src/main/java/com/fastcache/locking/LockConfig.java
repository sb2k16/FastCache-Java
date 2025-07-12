package com.fastcache.locking;

import java.time.Duration;

/**
 * Configuration for distributed locking system.
 */
public class LockConfig {
    private long defaultTtl = 30000; // 30 seconds
    private long renewalInterval = 10000; // 10 seconds
    private int maxRenewalCount = 10;
    private boolean enableReplication = true;
    private int replicationFactor = 2;
    private long cleanupInterval = 60000; // 1 minute
    private long lockTimeout = 5000; // 5 seconds for lock acquisition
    private boolean enableMetrics = true;
    private int maxConcurrentLocks = 10000;
    
    public LockConfig() {}
    
    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private LockConfig config = new LockConfig();
        
        public Builder defaultTtl(long ttl) {
            config.defaultTtl = ttl;
            return this;
        }
        
        public Builder defaultTtl(Duration duration) {
            config.defaultTtl = duration.toMillis();
            return this;
        }
        
        public Builder renewalInterval(long interval) {
            config.renewalInterval = interval;
            return this;
        }
        
        public Builder renewalInterval(Duration duration) {
            config.renewalInterval = duration.toMillis();
            return this;
        }
        
        public Builder maxRenewalCount(int count) {
            config.maxRenewalCount = count;
            return this;
        }
        
        public Builder enableReplication(boolean enable) {
            config.enableReplication = enable;
            return this;
        }
        
        public Builder replicationFactor(int factor) {
            config.replicationFactor = factor;
            return this;
        }
        
        public Builder cleanupInterval(long interval) {
            config.cleanupInterval = interval;
            return this;
        }
        
        public Builder cleanupInterval(Duration duration) {
            config.cleanupInterval = duration.toMillis();
            return this;
        }
        
        public Builder lockTimeout(long timeout) {
            config.lockTimeout = timeout;
            return this;
        }
        
        public Builder lockTimeout(Duration duration) {
            config.lockTimeout = duration.toMillis();
            return this;
        }
        
        public Builder enableMetrics(boolean enable) {
            config.enableMetrics = enable;
            return this;
        }
        
        public Builder maxConcurrentLocks(int max) {
            config.maxConcurrentLocks = max;
            return this;
        }
        
        public LockConfig build() {
            return config;
        }
    }
    
    // Getters
    public long getDefaultTtl() { return defaultTtl; }
    public long getRenewalInterval() { return renewalInterval; }
    public int getMaxRenewalCount() { return maxRenewalCount; }
    public boolean isEnableReplication() { return enableReplication; }
    public int getReplicationFactor() { return replicationFactor; }
    public long getCleanupInterval() { return cleanupInterval; }
    public long getLockTimeout() { return lockTimeout; }
    public boolean isEnableMetrics() { return enableMetrics; }
    public int getMaxConcurrentLocks() { return maxConcurrentLocks; }
    
    // Setters
    public void setDefaultTtl(long defaultTtl) { this.defaultTtl = defaultTtl; }
    public void setRenewalInterval(long renewalInterval) { this.renewalInterval = renewalInterval; }
    public void setMaxRenewalCount(int maxRenewalCount) { this.maxRenewalCount = maxRenewalCount; }
    public void setEnableReplication(boolean enableReplication) { this.enableReplication = enableReplication; }
    public void setReplicationFactor(int replicationFactor) { this.replicationFactor = replicationFactor; }
    public void setCleanupInterval(long cleanupInterval) { this.cleanupInterval = cleanupInterval; }
    public void setLockTimeout(long lockTimeout) { this.lockTimeout = lockTimeout; }
    public void setEnableMetrics(boolean enableMetrics) { this.enableMetrics = enableMetrics; }
    public void setMaxConcurrentLocks(int maxConcurrentLocks) { this.maxConcurrentLocks = maxConcurrentLocks; }
    
    @Override
    public String toString() {
        return String.format("LockConfig{defaultTtl=%dms, renewalInterval=%dms, maxRenewalCount=%d, " +
                           "enableReplication=%s, replicationFactor=%d, cleanupInterval=%dms, " +
                           "lockTimeout=%dms, enableMetrics=%s, maxConcurrentLocks=%d}",
                defaultTtl, renewalInterval, maxRenewalCount, enableReplication, replicationFactor,
                cleanupInterval, lockTimeout, enableMetrics, maxConcurrentLocks);
    }
} 