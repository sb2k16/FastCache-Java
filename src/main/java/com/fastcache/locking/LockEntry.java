package com.fastcache.locking;

import java.io.Serializable;
import java.time.Instant;

/**
 * Represents a distributed lock entry with metadata.
 */
public class LockEntry implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final String lockId;
    private final String resource;
    private final String owner;
    private final LockType type;
    private long timestamp; // was final, now mutable for renewal
    private final long ttl;
    private int renewalCount;
    private LockState state;
    
    public enum LockType {
        EXCLUSIVE,   // Only one client can hold the lock
        SHARED       // Multiple clients can hold the lock simultaneously
    }
    
    public enum LockState {
        ACQUIRED,    // Lock is currently held
        EXPIRED,     // Lock has expired
        RELEASED     // Lock has been explicitly released
    }
    
    public LockEntry(String lockId, String resource, String owner, LockType type, long ttl) {
        this.lockId = lockId;
        this.resource = resource;
        this.owner = owner;
        this.type = type;
        this.timestamp = System.currentTimeMillis();
        this.ttl = ttl;
        this.renewalCount = 0;
        this.state = LockState.ACQUIRED;
    }
    
    // Getters
    public String getLockId() { return lockId; }
    public String getResource() { return resource; }
    public String getOwner() { return owner; }
    public LockType getType() { return type; }
    public long getTimestamp() { return timestamp; }
    public long getTtl() { return ttl; }
    public int getRenewalCount() { return renewalCount; }
    public LockState getState() { return state; }
    
    // Setters
    public void setRenewalCount(int renewalCount) { this.renewalCount = renewalCount; }
    public void setState(LockState state) { this.state = state; }
    
    /**
     * Checks if the lock has expired.
     * @return true if the lock has expired, false otherwise
     */
    public boolean isExpired() {
        return System.currentTimeMillis() > (timestamp + ttl);
    }
    
    /**
     * Gets the remaining time to live in milliseconds.
     * @return remaining TTL in milliseconds, or 0 if expired
     */
    public long getRemainingTtl() {
        long remaining = (timestamp + ttl) - System.currentTimeMillis();
        return Math.max(0, remaining);
    }
    
    /**
     * Renews the lock by updating the timestamp and incrementing renewal count.
     */
    public void renew() {
        this.timestamp = System.currentTimeMillis();
        this.renewalCount++;
    }
    
    /**
     * Checks if this lock is compatible with another lock.
     * @param other The other lock to check compatibility with
     * @return true if locks are compatible, false otherwise
     */
    public boolean isCompatibleWith(LockEntry other) {
        if (other == null) return true;
        
        // Same resource check
        if (!resource.equals(other.resource)) return true;
        
        // If either lock is expired or released, they're compatible
        if (state != LockState.ACQUIRED || other.state != LockState.ACQUIRED) return true;
        
        // Shared locks are compatible with other shared locks
        if (type == LockType.SHARED && other.type == LockType.SHARED) return true;
        
        // Exclusive locks are not compatible with any other locks
        return false;
    }
    
    /**
     * Checks if this lock conflicts with another lock.
     * @param other The other lock to check conflict with
     * @return true if locks conflict, false otherwise
     */
    public boolean conflictsWith(LockEntry other) {
        return !isCompatibleWith(other);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        LockEntry lockEntry = (LockEntry) obj;
        return lockId.equals(lockEntry.lockId);
    }
    
    @Override
    public int hashCode() {
        return lockId.hashCode();
    }
    
    @Override
    public String toString() {
        return String.format("LockEntry{lockId='%s', resource='%s', owner='%s', type=%s, state=%s, ttl=%dms, renewals=%d}",
                lockId, resource, owner, type, state, ttl, renewalCount);
    }
} 