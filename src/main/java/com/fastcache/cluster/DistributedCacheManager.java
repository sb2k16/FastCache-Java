package com.fastcache.cluster;

import com.fastcache.core.CacheEngine;
import com.fastcache.core.CacheEntry;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Distributed cache manager that coordinates multiple cache nodes using consistent hashing.
 * Provides transparent partitioning and sharding across the cache cluster.
 */
public class DistributedCacheManager {
    private final ConsistentHash consistentHash;
    private final Map<String, CacheNode> nodes;
    private final Map<String, CacheEngine> localEngines;
    private final ExecutorService executor;
    private final int replicationFactor;
    private final boolean enableReplication;
    
    public DistributedCacheManager() {
        this(150, 2, true);
    }
    
    public DistributedCacheManager(int virtualNodes, int replicationFactor, boolean enableReplication) {
        this.consistentHash = new ConsistentHash(virtualNodes);
        this.nodes = new ConcurrentHashMap<>();
        this.localEngines = new ConcurrentHashMap<>();
        this.executor = Executors.newCachedThreadPool();
        this.replicationFactor = replicationFactor;
        this.enableReplication = enableReplication;
        
        System.out.println("Distributed cache manager initialized with virtualNodes=" + virtualNodes + 
                          ", replicationFactor=" + replicationFactor + 
                          ", enableReplication=" + enableReplication);
    }
    
    /**
     * Adds a cache node to the cluster.
     * @param node The cache node to add
     * @param localEngine The local cache engine for this node
     */
    public void addNode(CacheNode node, CacheEngine localEngine) {
        nodes.put(node.getId(), node);
        localEngines.put(node.getId(), localEngine);
        consistentHash.addNode(node);
        
        System.out.println("Added node to cluster: " + node);
    }
    
    /**
     * Removes a cache node from the cluster.
     * @param nodeId The ID of the node to remove
     */
    public void removeNode(String nodeId) {
        CacheNode node = nodes.remove(nodeId);
        if (node != null) {
            localEngines.remove(nodeId);
            consistentHash.removeNode(node);
            System.out.println("Removed node from cluster: " + node);
        }
    }
    
    /**
     * Gets the cache node responsible for a given key.
     * @param key The cache key
     * @return The responsible cache node
     */
    public CacheNode getResponsibleNode(String key) {
        return consistentHash.getNode(key);
    }
    
    /**
     * Gets multiple cache nodes for replication.
     * @param key The cache key
     * @return Collection of cache nodes for replication
     */
    public Collection<CacheNode> getReplicationNodes(String key) {
        if (!enableReplication) {
            CacheNode node = getResponsibleNode(key);
            return node != null ? List.of(node) : List.of();
        }
        return consistentHash.getNodes(key, replicationFactor);
    }
    
    /**
     * Stores a value in the distributed cache.
     * @param key The cache key
     * @param value The value to store
     * @param ttlSeconds Time to live in seconds
     * @param type The data type
     * @return CompletableFuture that completes when the operation is done
     */
    public CompletableFuture<Boolean> set(String key, Object value, long ttlSeconds, CacheEntry.EntryType type) {
        Collection<CacheNode> nodes = getReplicationNodes(key);
        
        if (nodes.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        
        List<CompletableFuture<Boolean>> futures = nodes.stream()
                .map(node -> CompletableFuture.supplyAsync(() -> {
                    CacheEngine engine = localEngines.get(node.getId());
                    if (engine != null) {
                        return engine.set(key, value, ttlSeconds, type);
                    }
                    return false;
                }, executor))
                .toList();
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream().anyMatch(CompletableFuture::join));
    }
    
    /**
     * Stores a value in the distributed cache without expiration.
     * @param key The cache key
     * @param value The value to store
     * @param type The data type
     * @return CompletableFuture that completes when the operation is done
     */
    public CompletableFuture<Boolean> set(String key, Object value, CacheEntry.EntryType type) {
        return set(key, value, -1, type);
    }
    
    /**
     * Retrieves a value from the distributed cache.
     * @param key The cache key
     * @return CompletableFuture that completes with the cached value
     */
    public CompletableFuture<Object> get(String key) {
        Collection<CacheNode> nodes = getReplicationNodes(key);
        
        if (nodes.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        
        List<CompletableFuture<Object>> futures = nodes.stream()
                .map(node -> CompletableFuture.supplyAsync(() -> {
                    CacheEngine engine = localEngines.get(node.getId());
                    if (engine != null) {
                        return engine.get(key);
                    }
                    return null;
                }, executor))
                .toList();
        
        return CompletableFuture.anyOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(result -> result);
    }
    
    /**
     * Removes a key from the distributed cache.
     * @param key The cache key to remove
     * @return CompletableFuture that completes when the operation is done
     */
    public CompletableFuture<Boolean> delete(String key) {
        Collection<CacheNode> nodes = getReplicationNodes(key);
        
        if (nodes.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        
        List<CompletableFuture<Boolean>> futures = nodes.stream()
                .map(node -> CompletableFuture.supplyAsync(() -> {
                    CacheEngine engine = localEngines.get(node.getId());
                    if (engine != null) {
                        return engine.delete(key);
                    }
                    return false;
                }, executor))
                .toList();
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream().anyMatch(CompletableFuture::join));
    }
    
    /**
     * Checks if a key exists in the distributed cache.
     * @param key The cache key to check
     * @return CompletableFuture that completes with the existence result
     */
    public CompletableFuture<Boolean> exists(String key) {
        Collection<CacheNode> nodes = getReplicationNodes(key);
        
        if (nodes.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        
        List<CompletableFuture<Boolean>> futures = nodes.stream()
                .map(node -> CompletableFuture.supplyAsync(() -> {
                    CacheEngine engine = localEngines.get(node.getId());
                    if (engine != null) {
                        return engine.exists(key);
                    }
                    return false;
                }, executor))
                .toList();
        
        return CompletableFuture.anyOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(result -> (Boolean) result);
    }
    
    /**
     * Gets the time to live for a key.
     * @param key The cache key
     * @return CompletableFuture that completes with the TTL
     */
    public CompletableFuture<Long> ttl(String key) {
        CacheNode node = getResponsibleNode(key);
        if (node == null) {
            return CompletableFuture.completedFuture(-2L);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            CacheEngine engine = localEngines.get(node.getId());
            if (engine != null) {
                return engine.ttl(key);
            }
            return -2L;
        }, executor);
    }
    
    /**
     * Sets the expiration time for a key.
     * @param key The cache key
     * @param ttlSeconds Time to live in seconds
     * @return CompletableFuture that completes when the operation is done
     */
    public CompletableFuture<Boolean> expire(String key, long ttlSeconds) {
        Collection<CacheNode> nodes = getReplicationNodes(key);
        
        if (nodes.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        
        List<CompletableFuture<Boolean>> futures = nodes.stream()
                .map(node -> CompletableFuture.supplyAsync(() -> {
                    CacheEngine engine = localEngines.get(node.getId());
                    if (engine != null) {
                        return engine.expire(key, ttlSeconds);
                    }
                    return false;
                }, executor))
                .toList();
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream().anyMatch(CompletableFuture::join));
    }
    
    /**
     * Clears all entries from the distributed cache.
     * @return CompletableFuture that completes when the operation is done
     */
    public CompletableFuture<Void> flush() {
        List<CompletableFuture<Void>> futures = localEngines.values().stream()
                .map(engine -> CompletableFuture.runAsync(engine::flush, executor))
                .toList();
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }
    
    /**
     * Gets cluster statistics.
     * @return Cluster statistics
     */
    public ClusterStats getClusterStats() {
        Map<String, CacheEngine.CacheStats> nodeStats = new ConcurrentHashMap<>();
        
        localEngines.forEach((nodeId, engine) -> {
            nodeStats.put(nodeId, engine.getStats());
        });
        
        ConsistentHash.DistributionStats distributionStats = consistentHash.getDistributionStats();
        
        return new ClusterStats(
            nodes.size(),
            consistentHash.getVirtualNodeCount(),
            distributionStats,
            nodeStats
        );
    }
    
    /**
     * Gets all nodes in the cluster.
     * @return Collection of all cache nodes
     */
    public Collection<CacheNode> getAllNodes() {
        return consistentHash.getAllNodes();
    }
    
    /**
     * Gets the number of nodes in the cluster.
     * @return Number of nodes
     */
    public int getNodeCount() {
        return nodes.size();
    }
    
    /**
     * Checks if the cluster is empty.
     * @return true if the cluster is empty
     */
    public boolean isEmpty() {
        return nodes.isEmpty();
    }
    
    /**
     * Shuts down the distributed cache manager and cleans up resources.
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        localEngines.values().forEach(CacheEngine::shutdown);
        System.out.println("Distributed cache manager shutdown complete");
    }
    
    /**
     * Cluster statistics.
     */
    public static class ClusterStats {
        private final int nodeCount;
        private final int virtualNodeCount;
        private final ConsistentHash.DistributionStats distributionStats;
        private final Map<String, CacheEngine.CacheStats> nodeStats;
        
        public ClusterStats(int nodeCount, int virtualNodeCount, 
                           ConsistentHash.DistributionStats distributionStats,
                           Map<String, CacheEngine.CacheStats> nodeStats) {
            this.nodeCount = nodeCount;
            this.virtualNodeCount = virtualNodeCount;
            this.distributionStats = distributionStats;
            this.nodeStats = nodeStats;
        }
        
        public int getNodeCount() { return nodeCount; }
        public int getVirtualNodeCount() { return virtualNodeCount; }
        public ConsistentHash.DistributionStats getDistributionStats() { return distributionStats; }
        public Map<String, CacheEngine.CacheStats> getNodeStats() { return nodeStats; }
        
        public long getTotalHits() {
            return nodeStats.values().stream().mapToLong(CacheEngine.CacheStats::getHits).sum();
        }
        
        public long getTotalMisses() {
            return nodeStats.values().stream().mapToLong(CacheEngine.CacheStats::getMisses).sum();
        }
        
        public long getTotalEvictions() {
            return nodeStats.values().stream().mapToLong(CacheEngine.CacheStats::getEvictions).sum();
        }
        
        public int getTotalSize() {
            return nodeStats.values().stream().mapToInt(CacheEngine.CacheStats::getSize).sum();
        }
        
        public double getOverallHitRate() {
            long totalHits = getTotalHits();
            long totalMisses = getTotalMisses();
            long total = totalHits + totalMisses;
            return total > 0 ? (double) totalHits / total : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format("ClusterStats{nodes=%d, virtualNodes=%d, totalSize=%d, hitRate=%.2f%%, distribution=%s}",
                    nodeCount, virtualNodeCount, getTotalSize(), getOverallHitRate() * 100, distributionStats);
        }
    }
} 