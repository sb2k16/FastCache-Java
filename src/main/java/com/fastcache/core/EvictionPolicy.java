package com.fastcache.core;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Interface for cache eviction policies.
 */
public interface EvictionPolicy {
    
    /**
     * Selects entries to evict when the cache is full.
     * @param entries Current cache entries
     * @param maxSize Maximum cache size
     * @return List of keys to evict
     */
    List<String> selectForEviction(Map<String, CacheEntry> entries, int maxSize);
    
    /**
     * Updates the policy when an entry is accessed.
     * @param entry The accessed entry
     */
    void onAccess(CacheEntry entry);
    
    /**
     * Updates the policy when an entry is added.
     * @param entry The added entry
     */
    void onAdd(CacheEntry entry);
    
    /**
     * Updates the policy when an entry is removed.
     * @param entry The removed entry
     */
    void onRemove(CacheEntry entry);
    
    /**
     * LRU (Least Recently Used) eviction policy.
     */
    class LRU implements EvictionPolicy {
        private final Map<String, Long> accessTimes = new ConcurrentHashMap<>();
        
        @Override
        public List<String> selectForEviction(Map<String, CacheEntry> entries, int maxSize) {
            int toEvict = entries.size() - maxSize;
            if (toEvict <= 0) {
                return List.of();
            }
            
            return accessTimes.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue())
                    .limit(toEvict)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
        }
        
        @Override
        public void onAccess(CacheEntry entry) {
            accessTimes.put(entry.getKey(), System.currentTimeMillis());
        }
        
        @Override
        public void onAdd(CacheEntry entry) {
            accessTimes.put(entry.getKey(), System.currentTimeMillis());
        }
        
        @Override
        public void onRemove(CacheEntry entry) {
            accessTimes.remove(entry.getKey());
        }
    }
    
    /**
     * LFU (Least Frequently Used) eviction policy.
     */
    class LFU implements EvictionPolicy {
        private final Map<String, Integer> accessCounts = new ConcurrentHashMap<>();
        
        @Override
        public List<String> selectForEviction(Map<String, CacheEntry> entries, int maxSize) {
            int toEvict = entries.size() - maxSize;
            if (toEvict <= 0) {
                return List.of();
            }
            
            return accessCounts.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue())
                    .limit(toEvict)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
        }
        
        @Override
        public void onAccess(CacheEntry entry) {
            accessCounts.merge(entry.getKey(), 1, Integer::sum);
        }
        
        @Override
        public void onAdd(CacheEntry entry) {
            accessCounts.put(entry.getKey(), 0);
        }
        
        @Override
        public void onRemove(CacheEntry entry) {
            accessCounts.remove(entry.getKey());
        }
    }
    
    /**
     * Random eviction policy.
     */
    class Random implements EvictionPolicy {
        
        @Override
        public List<String> selectForEviction(Map<String, CacheEntry> entries, int maxSize) {
            int toEvict = entries.size() - maxSize;
            if (toEvict <= 0) {
                return List.of();
            }
            
            return entries.keySet().stream()
                    .limit(toEvict)
                    .collect(Collectors.toList());
        }
        
        @Override
        public void onAccess(CacheEntry entry) {
            // No-op for random policy
        }
        
        @Override
        public void onAdd(CacheEntry entry) {
            // No-op for random policy
        }
        
        @Override
        public void onRemove(CacheEntry entry) {
            // No-op for random policy
        }
    }
    
    /**
     * TTL-based eviction policy (evicts expired entries first).
     */
    class TTL implements EvictionPolicy {
        
        @Override
        public List<String> selectForEviction(Map<String, CacheEntry> entries, int maxSize) {
            int toEvict = entries.size() - maxSize;
            if (toEvict <= 0) {
                return List.of();
            }
            
            return entries.values().stream()
                    .filter(CacheEntry::isExpired)
                    .limit(toEvict)
                    .map(CacheEntry::getKey)
                    .collect(Collectors.toList());
        }
        
        @Override
        public void onAccess(CacheEntry entry) {
            // No-op for TTL policy
        }
        
        @Override
        public void onAdd(CacheEntry entry) {
            // No-op for TTL policy
        }
        
        @Override
        public void onRemove(CacheEntry entry) {
            // No-op for TTL policy
        }
    }
} 