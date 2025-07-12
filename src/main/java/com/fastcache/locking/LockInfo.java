package com.fastcache.locking;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Provides information about a distributed lock for monitoring and debugging.
 */
public class LockInfo {
    private final String lockId;
    private final String resource;
    private final String owner;
    private final LockEntry.LockType type;
    private final LockEntry.LockState state;
    private final long timestamp;
    private final long ttl;
    private final int renewalCount;
    private final long remainingTtl;
    private final boolean expired;
    
    public LockInfo(LockEntry lockEntry) {
        this.lockId = lockEntry.getLockId();
        this.resource = lockEntry.getResource();
        this.owner = lockEntry.getOwner();
        this.type = lockEntry.getType();
        this.state = lockEntry.getState();
        this.timestamp = lockEntry.getTimestamp();
        this.ttl = lockEntry.getTtl();
        this.renewalCount = lockEntry.getRenewalCount();
        this.remainingTtl = lockEntry.getRemainingTtl();
        this.expired = lockEntry.isExpired();
    }
    
    // Getters
    public String getLockId() { return lockId; }
    public String getResource() { return resource; }
    public String getOwner() { return owner; }
    public LockEntry.LockType getType() { return type; }
    public LockEntry.LockState getState() { return state; }
    public long getTimestamp() { return timestamp; }
    public long getTtl() { return ttl; }
    public int getRenewalCount() { return renewalCount; }
    public long getRemainingTtl() { return remainingTtl; }
    public boolean isExpired() { return expired; }
    
    /**
     * Gets the lock acquisition time as a LocalDateTime.
     * @return LocalDateTime when the lock was acquired
     */
    public LocalDateTime getAcquisitionTime() {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault());
    }
    
    /**
     * Gets the lock expiration time as a LocalDateTime.
     * @return LocalDateTime when the lock will expire
     */
    public LocalDateTime getExpirationTime() {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp + ttl), ZoneId.systemDefault());
    }
    
    /**
     * Gets the lock duration in milliseconds.
     * @return duration in milliseconds
     */
    public long getDuration() {
        return System.currentTimeMillis() - timestamp;
    }
    
    /**
     * Checks if the lock is currently active (acquired and not expired).
     * @return true if the lock is active, false otherwise
     */
    public boolean isActive() {
        return state == LockEntry.LockState.ACQUIRED && !expired;
    }
    
    @Override
    public String toString() {
        return String.format("LockInfo{lockId='%s', resource='%s', owner='%s', type=%s, state=%s, " +
                           "acquisitionTime=%s, expirationTime=%s, duration=%dms, renewals=%d, remainingTtl=%dms}",
                lockId, resource, owner, type, state, getAcquisitionTime(), getExpirationTime(), 
                getDuration(), renewalCount, remainingTtl);
    }
} 