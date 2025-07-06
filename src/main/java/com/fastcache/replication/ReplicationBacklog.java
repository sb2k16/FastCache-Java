package com.fastcache.replication;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis-inspired replication backlog.
 * Buffers recent commands for quick resynchronization without full RDB transfer.
 */
public class ReplicationBacklog {
    
    private final int maxSize;
    private final Map<Long, BacklogEntry> entries = new ConcurrentHashMap<>();
    private final AtomicLong firstByteOffset = new AtomicLong(0);
    private final AtomicLong lastByteOffset = new AtomicLong(0);
    private final AtomicLong currentSize = new AtomicLong(0);
    
    public ReplicationBacklog(int maxSize) {
        this.maxSize = maxSize;
        System.out.println("ReplicationBacklog initialized with maxSize=" + maxSize);
    }
    
    /**
     * Adds a command to the replication backlog.
     */
    public void addCommand(String operation, String key, Object value, long offset) {
        BacklogEntry entry = new BacklogEntry(operation, key, value, offset, System.currentTimeMillis());
        entries.put(offset, entry);
        
        // Update size tracking
        long entrySize = estimateEntrySize(entry);
        currentSize.addAndGet(entrySize);
        
        // Update offset tracking
        if (firstByteOffset.get() == 0) {
            firstByteOffset.set(offset);
        }
        lastByteOffset.set(offset);
        
        // Trim if backlog is too large
        trimBacklog();
    }
    
    /**
     * Gets commands from the backlog starting from a specific offset.
     */
    public List<BacklogEntry> getCommandsFromOffset(long fromOffset) {
        List<BacklogEntry> result = new ArrayList<>();
        
        for (long offset = fromOffset; offset <= lastByteOffset.get(); offset++) {
            BacklogEntry entry = entries.get(offset);
            if (entry != null) {
                result.add(entry);
            }
        }
        
        return result;
    }
    
    /**
     * Gets commands from the backlog within a size limit.
     */
    public List<BacklogEntry> getCommandsWithinSize(long maxBytes) {
        List<BacklogEntry> result = new ArrayList<>();
        long currentBytes = 0;
        
        // Start from the oldest entries
        for (long offset = firstByteOffset.get(); offset <= lastByteOffset.get(); offset++) {
            BacklogEntry entry = entries.get(offset);
            if (entry != null) {
                long entrySize = estimateEntrySize(entry);
                if (currentBytes + entrySize > maxBytes) {
                    break;
                }
                result.add(entry);
                currentBytes += entrySize;
            }
        }
        
        return result;
    }
    
    /**
     * Checks if the backlog contains entries from a specific offset.
     */
    public boolean hasOffset(long offset) {
        return offset >= firstByteOffset.get() && offset <= lastByteOffset.get() && entries.containsKey(offset);
    }
    
    /**
     * Gets the first available offset in the backlog.
     */
    public long getFirstByteOffset() {
        return firstByteOffset.get();
    }
    
    /**
     * Gets the last available offset in the backlog.
     */
    public long getLastByteOffset() {
        return lastByteOffset.get();
    }
    
    /**
     * Gets the current size of the backlog in bytes.
     */
    public long getCurrentSize() {
        return currentSize.get();
    }
    
    /**
     * Gets the maximum size of the backlog in bytes.
     */
    public int getMaxSize() {
        return maxSize;
    }
    
    /**
     * Clears the backlog.
     */
    public void clear() {
        entries.clear();
        firstByteOffset.set(0);
        lastByteOffset.set(0);
        currentSize.set(0);
    }
    
    /**
     * Trims the backlog to stay within size limits.
     */
    private void trimBacklog() {
        while (currentSize.get() > maxSize && !entries.isEmpty()) {
            // Remove oldest entries
            long oldestOffset = firstByteOffset.get();
            BacklogEntry oldestEntry = entries.remove(oldestOffset);
            
            if (oldestEntry != null) {
                long entrySize = estimateEntrySize(oldestEntry);
                currentSize.addAndGet(-entrySize);
                
                // Find next oldest offset
                long nextOffset = oldestOffset + 1;
                while (nextOffset <= lastByteOffset.get() && !entries.containsKey(nextOffset)) {
                    nextOffset++;
                }
                
                if (nextOffset <= lastByteOffset.get()) {
                    firstByteOffset.set(nextOffset);
                } else {
                    // Backlog is empty
                    firstByteOffset.set(0);
                    lastByteOffset.set(0);
                    break;
                }
            }
        }
    }
    
    /**
     * Estimates the size of a backlog entry in bytes.
     */
    private long estimateEntrySize(BacklogEntry entry) {
        long size = 0;
        
        if (entry.getOperation() != null) {
            size += entry.getOperation().length() * 2; // UTF-16
        }
        
        if (entry.getKey() != null) {
            size += entry.getKey().length() * 2; // UTF-16
        }
        
        if (entry.getValue() != null) {
            if (entry.getValue() instanceof String) {
                size += ((String) entry.getValue()).length() * 2; // UTF-16
            } else {
                size += 64; // Estimate for other types
            }
        }
        
        size += 32; // Overhead for object structure
        
        return size;
    }
    
    /**
     * Gets backlog statistics.
     */
    public BacklogStats getStats() {
        return new BacklogStats(
            entries.size(),
            currentSize.get(),
            maxSize,
            firstByteOffset.get(),
            lastByteOffset.get(),
            lastByteOffset.get() - firstByteOffset.get() + 1
        );
    }
    
    /**
     * Represents a single entry in the replication backlog.
     */
    public static class BacklogEntry {
        private final String operation;
        private final String key;
        private final Object value;
        private final long offset;
        private final long timestamp;
        
        public BacklogEntry(String operation, String key, Object value, long offset, long timestamp) {
            this.operation = operation;
            this.key = key;
            this.value = value;
            this.offset = offset;
            this.timestamp = timestamp;
        }
        
        // Getters
        public String getOperation() { return operation; }
        public String getKey() { return key; }
        public Object getValue() { return value; }
        public long getOffset() { return offset; }
        public long getTimestamp() { return timestamp; }
        
        @Override
        public String toString() {
            return String.format("BacklogEntry{offset=%d, operation=%s, key=%s, timestamp=%d}",
                               offset, operation, key, timestamp);
        }
    }
    
    /**
     * Statistics for the replication backlog.
     */
    public static class BacklogStats {
        private final int entryCount;
        private final long currentSize;
        private final int maxSize;
        private final long firstByteOffset;
        private final long lastByteOffset;
        private final long offsetRange;
        
        public BacklogStats(int entryCount, long currentSize, int maxSize, 
                          long firstByteOffset, long lastByteOffset, long offsetRange) {
            this.entryCount = entryCount;
            this.currentSize = currentSize;
            this.maxSize = maxSize;
            this.firstByteOffset = firstByteOffset;
            this.lastByteOffset = lastByteOffset;
            this.offsetRange = offsetRange;
        }
        
        // Getters
        public int getEntryCount() { return entryCount; }
        public long getCurrentSize() { return currentSize; }
        public int getMaxSize() { return maxSize; }
        public long getFirstByteOffset() { return firstByteOffset; }
        public long getLastByteOffset() { return lastByteOffset; }
        public long getOffsetRange() { return offsetRange; }
        
        public double getUsagePercentage() {
            return maxSize > 0 ? (double) currentSize / maxSize * 100 : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format("BacklogStats{entries=%d, size=%d/%d (%.1f%%), offsetRange=%d}",
                               entryCount, currentSize, maxSize, getUsagePercentage(), offsetRange);
        }
    }
} 