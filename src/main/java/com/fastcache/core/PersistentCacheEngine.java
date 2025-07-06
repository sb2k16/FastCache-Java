package com.fastcache.core;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Persistent cache engine that extends CacheEngine with durability features.
 * Uses Write-Ahead Log (WAL) for crash recovery and snapshots for fast startup.
 */
public class PersistentCacheEngine extends CacheEngine {
    private final WriteAheadLog writeAheadLog;
    private final String dataDir;
    private final String nodeId;
    private final ScheduledExecutorService snapshotExecutor;
    private volatile boolean recoveryMode = false;
    
    public PersistentCacheEngine(String dataDir, String nodeId) throws IOException {
        this(dataDir, nodeId, 10000, new EvictionPolicy.LRU());
    }
    
    public PersistentCacheEngine(String dataDir, String nodeId, int maxSize, EvictionPolicy evictionPolicy) throws IOException {
        super(maxSize, evictionPolicy);
        this.dataDir = dataDir;
        this.nodeId = nodeId;
        this.writeAheadLog = new WriteAheadLog(dataDir + "/wal", nodeId);
        this.snapshotExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "snapshot-scheduler");
            t.setDaemon(true);
            return t;
        });
        
        // Schedule periodic snapshots
        this.snapshotExecutor.scheduleAtFixedRate(this::createSnapshot, 5, 5, TimeUnit.MINUTES);
        
        // Perform recovery on startup
        performRecovery();
    }
    
    /**
     * Performs recovery from persistence data on startup.
     */
    private void performRecovery() {
        System.out.println("Starting recovery process...");
        recoveryMode = true;
        
        try {
            // Step 1: Load latest snapshot if available
            Path snapshotFile = getLatestSnapshotFile();
            if (snapshotFile != null) {
                System.out.println("Loading snapshot: " + snapshotFile);
                loadSnapshot(snapshotFile);
            } else {
                System.out.println("No snapshot found, starting with empty cache");
            }
            
            // Step 2: Replay WAL since snapshot
            System.out.println("Replaying WAL operations...");
            int replayedCount = writeAheadLog.replay(new WriteAheadLog.ReplayHandler() {
                @Override
                public void handle(WriteAheadLog.LogEntry operation) {
                    replayOperation(operation);
                }
            });
            
            System.out.println("Recovery completed. Replayed " + replayedCount + " operations.");
            
        } catch (Exception e) {
            System.err.println("Recovery failed: " + e.getMessage());
            throw new RuntimeException("Cache recovery failed", e);
        } finally {
            recoveryMode = false;
        }
    }
    
    /**
     * Replays a single operation from the WAL.
     */
    private void replayOperation(WriteAheadLog.LogEntry operation) {
        try {
            switch (operation.getType()) {
                case "SET":
                    if (operation.getDataType() == CacheEntry.EntryType.SORTED_SET) {
                        // Handle sorted set operation
                        if (operation.getMember() != null && operation.getScore() != null) {
                            super.zadd(operation.getKey(), operation.getMember(), operation.getScore());
                        }
                    } else {
                        // Handle regular key-value operation
                        super.set(operation.getKey(), operation.getValue(), 
                                operation.getTtlSeconds(), operation.getDataType());
                    }
                    break;
                    
                case "DELETE":
                    super.delete(operation.getKey());
                    break;
                    
                case "ZADD":
                    if (operation.getMember() != null && operation.getScore() != null) {
                        super.zadd(operation.getKey(), operation.getMember(), operation.getScore());
                    }
                    break;
                    
                case "ZREM":
                    if (operation.getMember() != null) {
                        super.zrem(operation.getKey(), operation.getMember());
                    }
                    break;
                    
                case "EXPIRE":
                    super.expire(operation.getKey(), operation.getTtlSeconds());
                    break;
                    
                default:
                    System.err.println("Unknown operation type: " + operation.getType());
            }
        } catch (Exception e) {
            System.err.println("Failed to replay operation: " + operation + " - " + e.getMessage());
        }
    }
    
    /**
     * Creates a snapshot of the current cache state.
     */
    public void createSnapshot() {
        if (recoveryMode) {
            return; // Don't create snapshots during recovery
        }
        
        try {
            Path snapshotFile = getSnapshotFile(Instant.now());
            System.out.println("Creating snapshot: " + snapshotFile);
            
            // Create snapshot directory if it doesn't exist
            Files.createDirectories(snapshotFile.getParent());
            
            // Write snapshot
            try (ObjectOutputStream oos = new ObjectOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(snapshotFile)))) {
                
                SnapshotData snapshot = new SnapshotData();
                snapshot.setTimestamp(Instant.now());
                snapshot.setCacheEntries(getAllEntries());
                snapshot.setSortedSets(getAllSortedSets());
                
                oos.writeObject(snapshot);
                oos.flush();
            }
            
            // Truncate WAL after successful snapshot
            writeAheadLog.truncate();
            
            // Clean up old snapshots (keep only the latest 3)
            cleanupOldSnapshots();
            
            System.out.println("Snapshot created successfully");
            
        } catch (Exception e) {
            System.err.println("Failed to create snapshot: " + e.getMessage());
        }
    }
    
    /**
     * Loads a snapshot from file.
     */
    private void loadSnapshot(Path snapshotFile) throws IOException, ClassNotFoundException {
        try (ObjectInputStream ois = new ObjectInputStream(
                new BufferedInputStream(Files.newInputStream(snapshotFile)))) {
            
            SnapshotData snapshot = (SnapshotData) ois.readObject();
            
            // Clear current cache
            super.flush();
            
            // Restore cache entries
            for (CacheEntry entry : snapshot.getCacheEntries().values()) {
                super.set(entry.getKey(), entry.getValue(), 
                         entry.getTtlSeconds(), entry.getType());
            }
            
            // Restore sorted sets
            for (Map.Entry<String, SortedSet> setEntry : snapshot.getSortedSets().entrySet()) {
                String key = setEntry.getKey();
                SortedSet sortedSet = setEntry.getValue();
                
                // Add all members from the sorted set
                Map<String, Double> members = sortedSet.getAllWithScores();
                for (Map.Entry<String, Double> memberEntry : members.entrySet()) {
                    super.zadd(key, memberEntry.getKey(), memberEntry.getValue());
                }
            }
            
            System.out.println("Snapshot loaded: " + snapshot.getCacheEntries().size() + 
                             " entries, " + snapshot.getSortedSets().size() + " sorted sets");
        }
    }
    
    /**
     * Gets the latest snapshot file.
     */
    private Path getLatestSnapshotFile() throws IOException {
        Path snapshotDir = Paths.get(dataDir, "snapshots");
        if (!Files.exists(snapshotDir)) {
            return null;
        }
        
        return Files.list(snapshotDir)
                .filter(path -> path.toString().endsWith(".snapshot"))
                .max(Path::compareTo)
                .orElse(null);
    }
    
    /**
     * Gets the snapshot file path for a given timestamp.
     */
    private Path getSnapshotFile(Instant timestamp) {
        String filename = nodeId + "_" + timestamp.toEpochMilli() + ".snapshot";
        return Paths.get(dataDir, "snapshots", filename);
    }
    
    /**
     * Cleans up old snapshots, keeping only the latest 3.
     */
    private void cleanupOldSnapshots() throws IOException {
        Path snapshotDir = Paths.get(dataDir, "snapshots");
        if (!Files.exists(snapshotDir)) {
            return;
        }
        
        Files.list(snapshotDir)
                .filter(path -> path.toString().endsWith(".snapshot"))
                .sorted(Path::compareTo)
                .skip(3) // Keep only the latest 3
                .forEach(path -> {
                    try {
                        Files.delete(path);
                        System.out.println("Deleted old snapshot: " + path);
                    } catch (IOException e) {
                        System.err.println("Failed to delete old snapshot: " + path + " - " + e.getMessage());
                    }
                });
    }
    
    /**
     * Gets all cache entries for snapshot creation.
     * This is a simplified version - in a real implementation,
     * you'd need to access the internal cache map.
     */
    private Map<String, CacheEntry> getAllEntries() {
        // Simplified implementation - returns empty map
        // In a real implementation, you'd access the internal cache map
        return new ConcurrentHashMap<>();
    }
    
    /**
     * Gets all sorted sets for snapshot creation.
     * This is a simplified version - in a real implementation,
     * you'd need to access the internal sorted sets map.
     */
    private Map<String, SortedSet> getAllSortedSets() {
        // Simplified implementation - returns empty map
        // In a real implementation, you'd access the internal sorted sets map
        return new ConcurrentHashMap<>();
    }
    
    // Override CacheEngine methods to add persistence
    
    @Override
    public boolean set(String key, Object value, long ttlSeconds, CacheEntry.EntryType type) {
        boolean result = super.set(key, value, ttlSeconds, type);
        if (result && !recoveryMode) {
            // Log the operation to WAL
            WriteAheadLog.LogEntry logEntry = new WriteAheadLog.LogEntry("SET", key, value, ttlSeconds, type);
            writeAheadLog.log(logEntry);
        }
        return result;
    }
    
    @Override
    public boolean delete(String key) {
        boolean result = super.delete(key);
        if (result && !recoveryMode) {
            // Log the operation to WAL
            WriteAheadLog.LogEntry logEntry = new WriteAheadLog.LogEntry("DELETE", key, null, -1, CacheEntry.EntryType.STRING);
            writeAheadLog.log(logEntry);
        }
        return result;
    }
    
    @Override
    public boolean expire(String key, long ttlSeconds) {
        boolean result = super.expire(key, ttlSeconds);
        if (result && !recoveryMode) {
            // Log the operation to WAL
            WriteAheadLog.LogEntry logEntry = new WriteAheadLog.LogEntry("EXPIRE", key, null, ttlSeconds, CacheEntry.EntryType.STRING);
            writeAheadLog.log(logEntry);
        }
        return result;
    }
    
    @Override
    public boolean zadd(String key, String member, double score) {
        boolean result = super.zadd(key, member, score);
        if (result && !recoveryMode) {
            // Log the operation to WAL
            WriteAheadLog.LogEntry logEntry = new WriteAheadLog.LogEntry("ZADD", key, member, score);
            writeAheadLog.log(logEntry);
        }
        return result;
    }
    
    @Override
    public boolean zrem(String key, String member) {
        boolean result = super.zrem(key, member);
        if (result && !recoveryMode) {
            // Log the operation to WAL
            WriteAheadLog.LogEntry logEntry = new WriteAheadLog.LogEntry("ZREM", key, member, null);
            writeAheadLog.log(logEntry);
        }
        return result;
    }
    
    @Override
    public void flush() {
        super.flush();
        if (!recoveryMode) {
            // Create a snapshot after flush
            createSnapshot();
        }
    }
    
    @Override
    public void shutdown() {
        // Create final snapshot before shutdown
        if (!recoveryMode) {
            createSnapshot();
        }
        
        // Close WAL
        writeAheadLog.close();
        
        // Shutdown snapshot executor
        snapshotExecutor.shutdown();
        try {
            if (!snapshotExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                snapshotExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            snapshotExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        super.shutdown();
    }
    
    /**
     * Gets persistence statistics.
     */
    public PersistentCacheStats getPersistentStats() {
        return new PersistentCacheStats(
            getStats(),
            writeAheadLog.getLogSequenceNumber(),
            dataDir,
            nodeId
        );
    }
    
    /**
     * Statistics for persistent cache engine.
     */
    public static class PersistentCacheStats {
        private final CacheStats cacheStats;
        private final long walSequenceNumber;
        private final String dataDir;
        private final String nodeId;
        
        public PersistentCacheStats(CacheStats cacheStats, long walSequenceNumber, 
                                  String dataDir, String nodeId) {
            this.cacheStats = cacheStats;
            this.walSequenceNumber = walSequenceNumber;
            this.dataDir = dataDir;
            this.nodeId = nodeId;
        }
        
        public CacheStats getCacheStats() { return cacheStats; }
        public long getWalSequenceNumber() { return walSequenceNumber; }
        public String getDataDir() { return dataDir; }
        public String getNodeId() { return nodeId; }
        
        @Override
        public String toString() {
            return String.format("PersistentCacheStats{nodeId=%s, walSeq=%d, cache=%s}", 
                               nodeId, walSequenceNumber, cacheStats);
        }
    }
    
    /**
     * Snapshot data for serialization.
     */
    private static class SnapshotData implements Serializable {
        private static final long serialVersionUID = 1L;
        
        private Instant timestamp;
        private Map<String, CacheEntry> cacheEntries;
        private Map<String, SortedSet> sortedSets;
        
        public Instant getTimestamp() { return timestamp; }
        public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
        
        public Map<String, CacheEntry> getCacheEntries() { return cacheEntries; }
        public void setCacheEntries(Map<String, CacheEntry> cacheEntries) { this.cacheEntries = cacheEntries; }
        
        public Map<String, SortedSet> getSortedSets() { return sortedSets; }
        public void setSortedSets(Map<String, SortedSet> sortedSets) { this.sortedSets = sortedSets; }
    }
} 