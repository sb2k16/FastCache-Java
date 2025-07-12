package com.fastcache.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Core cache engine that provides thread-safe in-memory caching with
 * configurable eviction policies and expiration support.
 * 
 * This implementation uses fine-grained locking for optimal concurrency:
 * - ConcurrentHashMap for thread-safe cache operations
 * - Fine-grained locks only for eviction policy operations
 * - No global read-writer lock to maximize concurrency
 */
public class CacheEngine {
    
    private final Map<String, CacheEntry> cache;
    private final Map<String, SortedSet> sortedSets;
    private final EvictionPolicy evictionPolicy;
    private final int maxSize;
    private final Object evictionLock = new Object(); // Fine-grained lock for eviction operations
    private final Object sortedSetLock = new Object(); // Fine-grained lock for sorted set operations
    private final ScheduledExecutorService cleanupExecutor;
    
    private volatile long hits;
    private volatile long misses;
    private volatile long evictions;
    
    public CacheEngine() {
        this(10000, new EvictionPolicy.LRU());
    }
    
    public CacheEngine(int maxSize, EvictionPolicy evictionPolicy) {
        this.maxSize = maxSize;
        this.evictionPolicy = evictionPolicy;
        this.cache = new ConcurrentHashMap<>();
        this.sortedSets = new ConcurrentHashMap<>();
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cache-cleanup");
            t.setDaemon(true);
            return t;
        });
        
        // Schedule periodic cleanup of expired entries
        this.cleanupExecutor.scheduleAtFixedRate(this::cleanupExpiredEntries, 1, 1, TimeUnit.MINUTES);
        
        System.out.println("Cache engine initialized with maxSize=" + maxSize + ", evictionPolicy=" + evictionPolicy.getClass().getSimpleName());
    }
    
    /**
     * Stores a value in the cache.
     * @param key The cache key
     * @param value The value to store
     * @param ttlSeconds Time to live in seconds (-1 for no expiration)
     * @param type The data type
     * @return true if the value was stored successfully
     */
    public boolean set(String key, Object value, long ttlSeconds, CacheEntry.EntryType type) {
        Objects.requireNonNull(key, "Key cannot be null");
        Objects.requireNonNull(value, "Value cannot be null");
        
        CacheEntry entry = new CacheEntry(key, value, ttlSeconds, type);
        cache.put(key, entry);
        
        // Eviction policy operations need synchronization
        synchronized(evictionLock) {
            evictionPolicy.onAdd(entry);
        }
        
        // Check if we need to evict entries AFTER adding the item
        if (cache.size() > maxSize) {
            synchronized(evictionLock) {
                evictEntries();
            }
        }
        
        System.out.println("Stored key: " + key);
        return true;
    }
    
    /**
     * Stores a value in the cache without expiration.
     * @param key The cache key
     * @param value The value to store
     * @param type The data type
     * @return true if the value was stored successfully
     */
    public boolean set(String key, Object value, CacheEntry.EntryType type) {
        return set(key, value, -1, type);
    }
    
    /**
     * Retrieves a value from the cache.
     * @param key The cache key
     * @return The cached value, or null if not found or expired
     */
    public Object get(String key) {
        Objects.requireNonNull(key, "Key cannot be null");
        
        CacheEntry entry = cache.get(key);
        
        if (entry == null) {
            misses++;
            System.out.println("Cache miss for key: " + key);
            return null;
        }
        
        if (entry.isExpired()) {
            // Remove expired entry atomically
            if (cache.remove(key, entry)) {
                synchronized(evictionLock) {
                    evictionPolicy.onRemove(entry);
                }
                misses++;
                System.out.println("Expired entry removed for key: " + key);
            }
            return null;
        }
        
        hits++;
        // Eviction policy access tracking needs synchronization
        synchronized(evictionLock) {
            evictionPolicy.onAccess(entry);
        }
        System.out.println("Cache hit for key: " + key);
        return entry.getValue();
    }
    
    /**
     * Removes a key from the cache.
     * @param key The cache key to remove
     * @return true if the key was removed, false if it didn't exist
     */
    public boolean delete(String key) {
        Objects.requireNonNull(key, "Key cannot be null");
        
        CacheEntry entry = cache.remove(key);
        if (entry != null) {
            synchronized(evictionLock) {
                evictionPolicy.onRemove(entry);
            }
            System.out.println("Deleted key: " + key);
            return true;
        }
        return false;
    }
    
    /**
     * Checks if a key exists in the cache.
     * @param key The cache key to check
     * @return true if the key exists and is not expired
     */
    public boolean exists(String key) {
        Objects.requireNonNull(key, "Key cannot be null");
        
        CacheEntry entry = cache.get(key);
        if (entry == null) {
            return false;
        }
        
        if (entry.isExpired()) {
            // Remove expired entry atomically
            if (cache.remove(key, entry)) {
                synchronized(evictionLock) {
                    evictionPolicy.onRemove(entry);
                }
            }
            return false;
        }
        
        return true;
    }
    
    /**
     * Gets the time to live for a key.
     * @param key The cache key
     * @return TTL in seconds, -1 if no expiration, -2 if key doesn't exist
     */
    public long ttl(String key) {
        Objects.requireNonNull(key, "Key cannot be null");
        
        CacheEntry entry = cache.get(key);
        if (entry == null) {
            return -2;
        }
        
        if (entry.isExpired()) {
            // Remove expired entry atomically
            if (cache.remove(key, entry)) {
                synchronized(evictionLock) {
                    evictionPolicy.onRemove(entry);
                }
            }
            return -2;
        }
        
        return entry.getRemainingTtl();
    }
    
    /**
     * Sets the expiration time for a key.
     * @param key The cache key
     * @param ttlSeconds Time to live in seconds
     * @return true if the key exists and expiration was set
     */
    public boolean expire(String key, long ttlSeconds) {
        Objects.requireNonNull(key, "Key cannot be null");
        
        CacheEntry entry = cache.get(key);
        if (entry == null || entry.isExpired()) {
            return false;
        }
        
        // Create a new entry with the new TTL
        CacheEntry newEntry = new CacheEntry(key, entry.getValue(), ttlSeconds, entry.getType());
        cache.put(key, newEntry);
        
        // Eviction policy operations need synchronization
        synchronized(evictionLock) {
            evictionPolicy.onAdd(newEntry);
        }
        
        System.out.println("Set expiration for key: " + key + " to " + ttlSeconds + " seconds");
        return true;
    }
    
    /**
     * Removes expiration from a key.
     * @param key The cache key
     * @return true if the key exists and expiration was removed
     */
    public boolean persist(String key) {
        return expire(key, -1);
    }
    
    /**
     * Clears all entries from the cache.
     */
    public void flush() {
        cache.clear();
        System.out.println("Cache flushed");
    }
    
    /**
     * Gets cache statistics.
     * @return Cache statistics
     */
    public CacheStats getStats() {
        return new CacheStats(
            cache.size(),
            hits,
            misses,
            evictions,
            maxSize,
            evictionPolicy.getClass().getSimpleName()
        );
    }
    
    /**
     * Gets all keys in the cache.
     * @return List of all keys
     */
    public List<String> keys() {
        return cache.keySet().stream()
                .filter(key -> {
                    CacheEntry entry = cache.get(key);
                    return entry != null && !entry.isExpired();
                })
                .toList();
    }
    
    /**
     * Gets the number of entries in the cache.
     * @return Number of entries
     */
    public int size() {
        return cache.size();
    }
    
    /**
     * Checks if the cache is empty.
     * @return true if the cache is empty
     */
    public boolean isEmpty() {
        return size() == 0;
    }
    
    /**
     * Shuts down the cache engine and cleans up resources.
     */
    public void shutdown() {
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        System.out.println("Cache engine shutdown complete");
    }
    
    // ==================== SORTED SET OPERATIONS ====================
    
    /**
     * Adds a member with score to a sorted set.
     * @param key The sorted set key
     * @param member The member to add
     * @param score The score for the member
     * @return true if a new member was added, false if an existing member was updated
     */
    public boolean zadd(String key, String member, double score) {
        Objects.requireNonNull(key, "Key cannot be null");
        Objects.requireNonNull(member, "Member cannot be null");
        
        SortedSet sortedSet = sortedSets.computeIfAbsent(key, k -> new SortedSet());
        return sortedSet.add(member, score);
    }
    
    /**
     * Adds multiple members with scores to a sorted set.
     * @param key The sorted set key
     * @param memberScores Map of member to score
     * @return Number of new members added
     */
    public int zadd(String key, Map<String, Double> memberScores) {
        Objects.requireNonNull(key, "Key cannot be null");
        Objects.requireNonNull(memberScores, "Member scores cannot be null");
        
        SortedSet sortedSet = sortedSets.computeIfAbsent(key, k -> new SortedSet());
        return sortedSet.add(memberScores);
    }
    
    /**
     * Removes a member from a sorted set.
     * @param key The sorted set key
     * @param member The member to remove
     * @return true if the member was removed, false if it didn't exist
     */
    public boolean zrem(String key, String member) {
        Objects.requireNonNull(key, "Key cannot be null");
        Objects.requireNonNull(member, "Member cannot be null");
        
        SortedSet sortedSet = sortedSets.get(key);
        if (sortedSet == null) {
            return false;
        }
        
        boolean removed = sortedSet.remove(member);
        if (sortedSet.isEmpty()) {
            sortedSets.remove(key);
        }
        return removed;
    }
    
    /**
     * Removes multiple members from a sorted set.
     * @param key The sorted set key
     * @param members The members to remove
     * @return Number of members removed
     */
    public int zrem(String key, Collection<String> members) {
        Objects.requireNonNull(key, "Key cannot be null");
        Objects.requireNonNull(members, "Members cannot be null");
        
        SortedSet sortedSet = sortedSets.get(key);
        if (sortedSet == null) {
            return 0;
        }
        
        int removed = sortedSet.remove(members);
        if (sortedSet.isEmpty()) {
            sortedSets.remove(key);
        }
        return removed;
    }
    
    /**
     * Gets the score of a member in a sorted set.
     * @param key The sorted set key
     * @param member The member
     * @return The score, or null if the member doesn't exist
     */
    public Double zscore(String key, String member) {
        Objects.requireNonNull(key, "Key cannot be null");
        Objects.requireNonNull(member, "Member cannot be null");
        
        SortedSet sortedSet = sortedSets.get(key);
        return sortedSet != null ? sortedSet.getScore(member) : null;
    }
    
    /**
     * Gets the rank of a member in a sorted set (0-based, ascending order).
     * @param key The sorted set key
     * @param member The member
     * @return The rank, or -1 if the member doesn't exist
     */
    public int zrank(String key, String member) {
        Objects.requireNonNull(key, "Key cannot be null");
        Objects.requireNonNull(member, "Member cannot be null");
        SortedSet sortedSet = sortedSets.get(key);
        return sortedSet != null ? sortedSet.getRank(member) : -1;
    }
    
    /**
     * Gets the reverse rank of a member in a sorted set (0-based, descending order).
     * @param key The sorted set key
     * @param member The member
     * @return The reverse rank, or -1 if the member doesn't exist
     */
    public int zrevrank(String key, String member) {
        Objects.requireNonNull(key, "Key cannot be null");
        Objects.requireNonNull(member, "Member cannot be null");
        SortedSet sortedSet = sortedSets.get(key);
        return sortedSet != null ? sortedSet.getReverseRank(member) : -1;
    }
    
    /**
     * Gets a range of members by rank.
     * @param key The sorted set key
     * @param start Start rank (inclusive)
     * @param stop Stop rank (inclusive)
     * @return List of members in the range
     */
    public List<String> zrange(String key, int start, int stop) {
        Objects.requireNonNull(key, "Key cannot be null");
        SortedSet sortedSet = sortedSets.get(key);
        if (sortedSet == null) {
            return new ArrayList<>();
        }
        
        // Handle negative indices
        int size = sortedSet.size();
        int actualStart = start < 0 ? size + start : start;
        int actualStop = stop < 0 ? size + stop : stop;
        
        if (actualStart > actualStop || actualStart >= size) {
            return new ArrayList<>();
        }
        
        actualStart = Math.max(0, actualStart);
        actualStop = Math.min(size - 1, actualStop);
        
        return sortedSet.getRangeByRank(actualStart, actualStop);
    }
    
    /**
     * Gets a range of members with scores by rank.
     * @param key The sorted set key
     * @param start Start rank (inclusive)
     * @param stop Stop rank (inclusive)
     * @return Map of member to score in the range
     */
    public Map<String, Double> zrangeWithScores(String key, int start, int stop) {
        Objects.requireNonNull(key, "Key cannot be null");
        SortedSet sortedSet = sortedSets.get(key);
        if (sortedSet == null) {
            return new LinkedHashMap<>();
        }
        
        // Handle negative indices
        int size = sortedSet.size();
        int actualStart = start < 0 ? size + start : start;
        int actualStop = stop < 0 ? size + stop : stop;
        
        if (actualStart > actualStop || actualStart >= size) {
            return new LinkedHashMap<>();
        }
        
        actualStart = Math.max(0, actualStart);
        actualStop = Math.min(size - 1, actualStop);
        
        return sortedSet.getRangeWithScoresByRank(actualStart, actualStop);
    }
    
    /**
     * Gets a range of members by reverse rank.
     * @param key The sorted set key
     * @param start Start reverse rank (inclusive)
     * @param stop Stop reverse rank (inclusive)
     * @return List of members in the range
     */
    public List<String> zrevrange(String key, int start, int stop) {
        Objects.requireNonNull(key, "Key cannot be null");
        SortedSet sortedSet = sortedSets.get(key);
        if (sortedSet == null) {
            return new ArrayList<>();
        }
        
        // Handle negative indices
        int size = sortedSet.size();
        int actualStart = start < 0 ? size + start : start;
        int actualStop = stop < 0 ? size + stop : stop;
        
        if (actualStart > actualStop || actualStart >= size) {
            return new ArrayList<>();
        }
        
        actualStart = Math.max(0, actualStart);
        actualStop = Math.min(size - 1, actualStop);
        
        return sortedSet.getRangeByReverseRank(actualStart, actualStop);
    }
    
    /**
     * Gets a range of members with scores by reverse rank.
     * @param key The sorted set key
     * @param start Start reverse rank (inclusive)
     * @param stop Stop reverse rank (inclusive)
     * @return Map of member to score in the range
     */
    public Map<String, Double> zrevrangeWithScores(String key, int start, int stop) {
        Objects.requireNonNull(key, "Key cannot be null");
        SortedSet sortedSet = sortedSets.get(key);
        if (sortedSet == null) {
            return new LinkedHashMap<>();
        }
        
        // Handle negative indices
        int size = sortedSet.size();
        int actualStart = start < 0 ? size + start : start;
        int actualStop = stop < 0 ? size + stop : stop;
        
        if (actualStart > actualStop || actualStart >= size) {
            return new LinkedHashMap<>();
        }
        
        actualStart = Math.max(0, actualStart);
        actualStop = Math.min(size - 1, actualStop);
        
        return sortedSet.getRangeWithScoresByReverseRank(actualStart, actualStop);
    }
    
    /**
     * Gets a range of members by score.
     * @param key The sorted set key
     * @param minScore Minimum score (inclusive)
     * @param maxScore Maximum score (inclusive)
     * @return List of members in the score range
     */
    public List<String> zrangeByScore(String key, double minScore, double maxScore) {
        Objects.requireNonNull(key, "Key cannot be null");
        SortedSet sortedSet = sortedSets.get(key);
        return sortedSet != null ? sortedSet.getRangeByScore(minScore, maxScore) : new ArrayList<>();
    }
    
    /**
     * Gets a range of members with scores by score.
     * @param key The sorted set key
     * @param minScore Minimum score (inclusive)
     * @param maxScore Maximum score (inclusive)
     * @return Map of member to score in the score range
     */
    public Map<String, Double> zrangeByScoreWithScores(String key, double minScore, double maxScore) {
        Objects.requireNonNull(key, "Key cannot be null");
        SortedSet sortedSet = sortedSets.get(key);
        return sortedSet != null ? sortedSet.getRangeWithScoresByScore(minScore, maxScore) : new LinkedHashMap<>();
    }
    
    /**
     * Increments the score of a member in a sorted set.
     * @param key The sorted set key
     * @param member The member
     * @param increment The amount to increment by
     * @return The new score
     */
    public double zincrby(String key, String member, double increment) {
        Objects.requireNonNull(key, "Key cannot be null");
        Objects.requireNonNull(member, "Member cannot be null");
        SortedSet sortedSet = sortedSets.computeIfAbsent(key, k -> new SortedSet());
        return sortedSet.incrementScore(member, increment);
    }
    
    /**
     * Gets the size of a sorted set.
     * @param key The sorted set key
     * @return Number of members, or 0 if the key doesn't exist
     */
    public int zcard(String key) {
        Objects.requireNonNull(key, "Key cannot be null");
        SortedSet sortedSet = sortedSets.get(key);
        return sortedSet != null ? sortedSet.size() : 0;
    }
    
    /**
     * Counts members in a sorted set within a score range.
     * @param key The sorted set key
     * @param minScore Minimum score (inclusive)
     * @param maxScore Maximum score (inclusive)
     * @return Number of members in the score range
     */
    public int zcount(String key, double minScore, double maxScore) {
        Objects.requireNonNull(key, "Key cannot be null");
        SortedSet sortedSet = sortedSets.get(key);
        if (sortedSet == null) {
            return 0;
        }
        return sortedSet.getRangeByScore(minScore, maxScore).size();
    }
    
    /**
     * Removes members from a sorted set by rank.
     * @param key The sorted set key
     * @param start Start rank (inclusive)
     * @param stop Stop rank (inclusive)
     * @return Number of members removed
     */
    public int zremrangeByRank(String key, int start, int stop) {
        Objects.requireNonNull(key, "Key cannot be null");
        SortedSet sortedSet = sortedSets.get(key);
        if (sortedSet == null) {
            return 0;
        }
        
        // Handle negative indices
        int size = sortedSet.size();
        int actualStart = start < 0 ? size + start : start;
        int actualStop = stop < 0 ? size + stop : stop;
        
        if (actualStart > actualStop || actualStart >= size) {
            return 0;
        }
        
        actualStart = Math.max(0, actualStart);
        actualStop = Math.min(size - 1, actualStop);
        
        List<String> membersToRemove = sortedSet.getRangeByRank(actualStart, actualStop);
        int removed = sortedSet.remove(membersToRemove);
        
        if (sortedSet.isEmpty()) {
            sortedSets.remove(key);
        }
        
        return removed;
    }
    
    /**
     * Removes members from a sorted set by score.
     * @param key The sorted set key
     * @param minScore Minimum score (inclusive)
     * @param maxScore Maximum score (inclusive)
     * @return Number of members removed
     */
    public int zremrangeByScore(String key, double minScore, double maxScore) {
        Objects.requireNonNull(key, "Key cannot be null");
        SortedSet sortedSet = sortedSets.get(key);
        if (sortedSet == null) {
            return 0;
        }
        
        List<String> membersToRemove = sortedSet.getRangeByScore(minScore, maxScore);
        int removed = sortedSet.remove(membersToRemove);
        
        if (sortedSet.isEmpty()) {
            sortedSets.remove(key);
        }
        
        return removed;
    }
    
    /**
     * Gets all members of a sorted set.
     * @param key The sorted set key
     * @return List of all members, or empty list if key doesn't exist
     */
    public List<String> zmembers(String key) {
        Objects.requireNonNull(key, "Key cannot be null");
        SortedSet sortedSet = sortedSets.get(key);
        return sortedSet != null ? sortedSet.getAllMembers() : new ArrayList<>();
    }
    
    /**
     * Gets all members with scores of a sorted set.
     * @param key The sorted set key
     * @return Map of all members to their scores, or empty map if key doesn't exist
     */
    public Map<String, Double> zmembersWithScores(String key) {
        Objects.requireNonNull(key, "Key cannot be null");
        SortedSet sortedSet = sortedSets.get(key);
        return sortedSet != null ? sortedSet.getAllWithScores() : new LinkedHashMap<>();
    }
    
    /**
     * Checks if a sorted set exists.
     * @param key The sorted set key
     * @return true if the sorted set exists
     */
    public boolean zexists(String key) {
        Objects.requireNonNull(key, "Key cannot be null");       
        return sortedSets.containsKey(key);
    }
    
    /**
     * Deletes a sorted set.
     * @param key The sorted set key
     * @return true if the sorted set was deleted, false if it didn't exist
     */
    public boolean zdel(String key) {
        Objects.requireNonNull(key, "Key cannot be null");
        return sortedSets.remove(key) != null;
    }
    
    private void evictEntries() {
        List<String> keysToEvict = evictionPolicy.selectForEviction(cache, maxSize);
        
        for (String key : keysToEvict) {
            CacheEntry entry = cache.remove(key);
            if (entry != null) {
                evictionPolicy.onRemove(entry);
                evictions++;
                System.out.println("Evicted key: " + key);
            }
        }
        
        if (!keysToEvict.isEmpty()) {
            System.out.println("Evicted " + keysToEvict.size() + " entries from cache");
        }
    }
    
    private void cleanupExpiredEntries() {
        int removed = 0;
        var iterator = cache.entrySet().iterator();
        
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue().isExpired()) {
                iterator.remove();
                evictionPolicy.onRemove(entry.getValue());
                removed++;
            }
        }
        
        if (removed > 0) {
            System.out.println("Cleaned up " + removed + " expired entries");
        }
    }
    
    /**
     * Cache statistics.
     */
    public static class CacheStats {
        private final int size;
        private final long hits;
        private final long misses;
        private final long evictions;
        private final int maxSize;
        private final String evictionPolicy;
        
        public CacheStats(int size, long hits, long misses, long evictions, 
                         int maxSize, String evictionPolicy) {
            this.size = size;
            this.hits = hits;
            this.misses = misses;
            this.evictions = evictions;
            this.maxSize = maxSize;
            this.evictionPolicy = evictionPolicy;
        }
        
        public int getSize() { return size; }
        public long getHits() { return hits; }
        public long getMisses() { return misses; }
        public long getEvictions() { return evictions; }
        public int getMaxSize() { return maxSize; }
        public String getEvictionPolicy() { return evictionPolicy; }
        
        public double getHitRate() {
            long total = hits + misses;
            return total > 0 ? (double) hits / total : 0.0;
        }
        
        public double getUsagePercentage() {
            return maxSize > 0 ? (double) size / maxSize * 100 : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format("CacheStats{size=%d/%d (%.1f%%), hits=%d, misses=%d, hitRate=%.2f%%, evictions=%d, policy=%s}",
                    size, maxSize, getUsagePercentage(), hits, misses, getHitRate() * 100, evictions, evictionPolicy);
        }
    }
} 