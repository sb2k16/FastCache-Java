package com.fastcache.core;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * Represents a single cache entry with value, expiration, and metadata.
 */
public class CacheEntry implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final String key;
    private final Object value;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final long ttlSeconds;
    private final EntryType type;
    private volatile long lastAccessed;
    private volatile int accessCount;
    
    public enum EntryType {
        STRING, LIST, SET, HASH, SORTED_SET
    }
    
    public CacheEntry(String key, Object value, long ttlSeconds, EntryType type) {
        this.key = Objects.requireNonNull(key, "Key cannot be null");
        this.value = Objects.requireNonNull(value, "Value cannot be null");
        this.createdAt = Instant.now();
        this.ttlSeconds = ttlSeconds;
        this.type = Objects.requireNonNull(type, "Type cannot be null");
        this.lastAccessed = System.currentTimeMillis();
        this.accessCount = 0;
        
        if (ttlSeconds > 0) {
            this.expiresAt = createdAt.plusSeconds(ttlSeconds);
        } else {
            this.expiresAt = null;
        }
    }
    
    public CacheEntry(String key, Object value, EntryType type) {
        this(key, value, -1, type);
    }
    
    public String getKey() {
        return key;
    }
    
    public Object getValue() {
        updateAccess();
        return value;
    }
    
    public Instant getCreatedAt() {
        return createdAt;
    }
    
    public Instant getExpiresAt() {
        return expiresAt;
    }
    
    public long getTtlSeconds() {
        return ttlSeconds;
    }
    
    public EntryType getType() {
        return type;
    }
    
    public long getLastAccessed() {
        return lastAccessed;
    }
    
    public int getAccessCount() {
        return accessCount;
    }
    
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }
    
    public long getRemainingTtl() {
        if (expiresAt == null) {
            return -1;
        }
        long remaining = expiresAt.getEpochSecond() - Instant.now().getEpochSecond();
        return Math.max(0, remaining);
    }
    
    private void updateAccess() {
        this.lastAccessed = System.currentTimeMillis();
        this.accessCount++;
    }
    
    public void touch() {
        updateAccess();
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CacheEntry that = (CacheEntry) o;
        return Objects.equals(key, that.key);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(key);
    }
    
    @Override
    public String toString() {
        return "CacheEntry{" +
                "key='" + key + '\'' +
                ", type=" + type +
                ", ttl=" + ttlSeconds +
                ", expired=" + isExpired() +
                '}';
    }
} 