package com.fastcache.cluster;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Consistent hashing implementation for distributed cache partitioning.
 * This implementation provides efficient key distribution across cache nodes
 * with minimal rehashing when nodes are added or removed.
 */
public class ConsistentHash {
    
    private final HashFunction hashFunction;
    private final int numberOfReplicas;
    private final SortedMap<Long, CacheNode> circle = new TreeMap<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    
    /**
     * Hash function interface for consistent hashing.
     */
    public interface HashFunction {
        long hash(String key);
    }
    
    /**
     * MD5 hash function implementation.
     */
    public static class MD5HashFunction implements HashFunction {
        private final MessageDigest md;
        
        public MD5HashFunction() {
            try {
                this.md = MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException("MD5 algorithm not available", e);
            }
        }
        
        @Override
        public long hash(String key) {
            md.reset();
            md.update(key.getBytes());
            byte[] digest = md.digest();
            
            // Convert to long using first 8 bytes
            long hash = 0;
            for (int i = 0; i < 8; i++) {
                hash = (hash << 8) | (digest[i] & 0xFF);
            }
            return hash;
        }
    }
    
    /**
     * FNV-1a hash function implementation (faster than MD5).
     */
    public static class FNVHashFunction implements HashFunction {
        private static final long FNV_64_INIT = 0xcbf29ce484222325L;
        private static final long FNV_64_PRIME = 0x100000001b3L;
        
        @Override
        public long hash(String key) {
            long hash = FNV_64_INIT;
            for (byte b : key.getBytes()) {
                hash ^= b;
                hash *= FNV_64_PRIME;
            }
            return hash;
        }
    }
    
    /**
     * Creates a new consistent hash ring.
     * @param hashFunction The hash function to use
     * @param numberOfReplicas Number of virtual nodes per physical node
     */
    public ConsistentHash(HashFunction hashFunction, int numberOfReplicas) {
        this.hashFunction = hashFunction;
        this.numberOfReplicas = numberOfReplicas;
    }
    
    /**
     * Creates a new consistent hash ring with MD5 hash function.
     * @param numberOfReplicas Number of virtual nodes per physical node
     */
    public ConsistentHash(int numberOfReplicas) {
        this(new MD5HashFunction(), numberOfReplicas);
    }
    
    /**
     * Adds a cache node to the hash ring.
     * @param node The cache node to add
     */
    public void addNode(CacheNode node) {
        lock.writeLock().lock();
        try {
            for (int i = 0; i < numberOfReplicas; i++) {
                String virtualNodeName = node.getId() + "-" + i;
                long hash = hashFunction.hash(virtualNodeName);
                circle.put(hash, node);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Removes a cache node from the hash ring.
     * @param node The cache node to remove
     */
    public void removeNode(CacheNode node) {
        lock.writeLock().lock();
        try {
            for (int i = 0; i < numberOfReplicas; i++) {
                String virtualNodeName = node.getId() + "-" + i;
                long hash = hashFunction.hash(virtualNodeName);
                circle.remove(hash);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Gets the cache node responsible for a given key.
     * @param key The key to find the responsible node for
     * @return The cache node responsible for the key, or null if no nodes exist
     */
    public CacheNode getNode(String key) {
        lock.readLock().lock();
        try {
            if (circle.isEmpty()) {
                return null;
            }
            
            long hash = hashFunction.hash(key);
            
            // If the hash is not in the circle, get the next node clockwise
            if (!circle.containsKey(hash)) {
                SortedMap<Long, CacheNode> tailMap = circle.tailMap(hash);
                hash = tailMap.isEmpty() ? circle.firstKey() : tailMap.firstKey();
            }
            
            return circle.get(hash);
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Gets multiple cache nodes for a given key (for replication).
     * @param key The key to find nodes for
     * @param count Number of nodes to return
     * @return Collection of cache nodes
     */
    public Collection<CacheNode> getNodes(String key, int count) {
        lock.readLock().lock();
        try {
            if (circle.isEmpty()) {
                return java.util.Collections.emptyList();
            }
            
            long hash = hashFunction.hash(key);
            java.util.List<CacheNode> nodes = new java.util.ArrayList<>();
            java.util.Set<CacheNode> uniqueNodes = new java.util.HashSet<>();
            
            // Start from the hash position and collect nodes clockwise
            SortedMap<Long, CacheNode> tailMap = circle.tailMap(hash);
            java.util.Iterator<Long> iterator = tailMap.keySet().iterator();
            
            // Add nodes from tail map
            while (iterator.hasNext() && uniqueNodes.size() < count) {
                CacheNode node = tailMap.get(iterator.next());
                if (uniqueNodes.add(node)) {
                    nodes.add(node);
                }
            }
            
            // If we need more nodes, wrap around to the beginning
            if (uniqueNodes.size() < count) {
                iterator = circle.keySet().iterator();
                while (iterator.hasNext() && uniqueNodes.size() < count) {
                    Long keyHash = iterator.next();
                    if (keyHash >= hash) {
                        continue; // Skip nodes we already processed
                    }
                    CacheNode node = circle.get(keyHash);
                    if (uniqueNodes.add(node)) {
                        nodes.add(node);
                    }
                }
            }
            
            return nodes;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Gets all nodes in the hash ring.
     * @return Collection of all cache nodes
     */
    public Collection<CacheNode> getAllNodes() {
        lock.readLock().lock();
        try {
            return new java.util.HashSet<>(circle.values());
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Gets the number of virtual nodes in the hash ring.
     * @return Number of virtual nodes
     */
    public int getVirtualNodeCount() {
        lock.readLock().lock();
        try {
            return circle.size();
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Gets the number of physical nodes in the hash ring.
     * @return Number of physical nodes
     */
    public int getPhysicalNodeCount() {
        lock.readLock().lock();
        try {
            return getAllNodes().size();
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Calculates the distribution statistics of the hash ring.
     * @return Distribution statistics
     */
    public DistributionStats getDistributionStats() {
        lock.readLock().lock();
        try {
            java.util.Map<CacheNode, Integer> nodeCounts = new java.util.HashMap<>();
            
            for (CacheNode node : circle.values()) {
                nodeCounts.merge(node, 1, Integer::sum);
            }
            
            if (nodeCounts.isEmpty()) {
                return new DistributionStats(0, 0, 0, 0);
            }
            
            int min = nodeCounts.values().stream().mapToInt(Integer::intValue).min().orElse(0);
            int max = nodeCounts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            double avg = nodeCounts.values().stream().mapToInt(Integer::intValue).average().orElse(0);
            double stdDev = calculateStandardDeviation(nodeCounts.values(), avg);
            
            return new DistributionStats(min, max, avg, stdDev);
        } finally {
            lock.readLock().unlock();
        }
    }
    
    private double calculateStandardDeviation(Collection<Integer> values, double mean) {
        return Math.sqrt(values.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .average()
                .orElse(0));
    }
    
    /**
     * Statistics about the distribution of virtual nodes across physical nodes.
     */
    public static class DistributionStats {
        private final int minVirtualNodes;
        private final int maxVirtualNodes;
        private final double avgVirtualNodes;
        private final double standardDeviation;
        
        public DistributionStats(int minVirtualNodes, int maxVirtualNodes, 
                               double avgVirtualNodes, double standardDeviation) {
            this.minVirtualNodes = minVirtualNodes;
            this.maxVirtualNodes = maxVirtualNodes;
            this.avgVirtualNodes = avgVirtualNodes;
            this.standardDeviation = standardDeviation;
        }
        
        public int getMinVirtualNodes() { return minVirtualNodes; }
        public int getMaxVirtualNodes() { return maxVirtualNodes; }
        public double getAvgVirtualNodes() { return avgVirtualNodes; }
        public double getStandardDeviation() { return standardDeviation; }
        
        @Override
        public String toString() {
            return String.format("DistributionStats{min=%d, max=%d, avg=%.2f, stdDev=%.2f}", 
                    minVirtualNodes, maxVirtualNodes, avgVirtualNodes, standardDeviation);
        }
    }
} 