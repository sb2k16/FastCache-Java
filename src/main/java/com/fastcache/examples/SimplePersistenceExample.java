package com.fastcache.examples;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Simple example demonstrating persistence functionality.
 * This is a standalone implementation that doesn't depend on external libraries.
 */
public class SimplePersistenceExample {
    
    public static void main(String[] args) {
        System.out.println("=== Simple FastCache Persistence Example ===\n");
        
        String dataDir = "data/simple-node";
        String nodeId = "simple-node";
        
        try {
            // Clean up any existing data
            cleanupData(dataDir);
            
            // Step 1: Create a simple persistent cache
            System.out.println("Step 1: Creating persistent cache...");
            SimplePersistentCache cache = new SimplePersistentCache(dataDir, nodeId);
            
            // Step 2: Add some data
            System.out.println("Step 2: Adding data to cache...");
            cache.set("user:1", "John Doe");
            cache.set("user:2", "Jane Smith");
            cache.set("config:app", "production");
            cache.set("counter:visits", "1000");
            
            System.out.println("Cache contents:");
            System.out.println("- user:1 = " + cache.get("user:1"));
            System.out.println("- user:2 = " + cache.get("user:2"));
            System.out.println("- config:app = " + cache.get("config:app"));
            System.out.println("- counter:visits = " + cache.get("counter:visits"));
            
            // Step 3: Create a snapshot
            System.out.println("\nStep 3: Creating snapshot...");
            cache.createSnapshot();
            
            // Step 4: Simulate crash by closing the cache
            System.out.println("\nStep 4: Simulating node crash...");
            cache.close();
            
            // Step 5: Restart and recover
            System.out.println("\nStep 5: Restarting and recovering...");
            SimplePersistentCache recoveredCache = new SimplePersistentCache(dataDir, nodeId);
            
            // Step 6: Verify recovered data
            System.out.println("\nStep 6: Verifying recovered data...");
            System.out.println("Recovered cache contents:");
            System.out.println("- user:1 = " + recoveredCache.get("user:1"));
            System.out.println("- user:2 = " + recoveredCache.get("user:2"));
            System.out.println("- config:app = " + recoveredCache.get("config:app"));
            System.out.println("- counter:visits = " + recoveredCache.get("counter:visits"));
            
            // Step 7: Add more data after recovery
            System.out.println("\nStep 7: Adding more data after recovery...");
            recoveredCache.set("user:3", "Bob Johnson");
            recoveredCache.set("session:456", "active");
            
            System.out.println("Final cache contents:");
            System.out.println("- user:3 = " + recoveredCache.get("user:3"));
            System.out.println("- session:456 = " + recoveredCache.get("session:456"));
            
            // Cleanup
            recoveredCache.close();
            
            System.out.println("\n=== Persistence Example Completed Successfully ===");
            
        } catch (Exception e) {
            System.err.println("Persistence example failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Cleans up existing data directory.
     */
    private static void cleanupData(String dataDir) {
        try {
            Path dataPath = Paths.get(dataDir);
            if (Files.exists(dataPath)) {
                Files.walk(dataPath)
                    .sorted((a, b) -> b.compareTo(a)) // Delete files before directories
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            System.err.println("Failed to delete: " + path + " - " + e.getMessage());
                        }
                    });
                System.out.println("Cleaned up existing data directory: " + dataDir);
            }
        } catch (IOException e) {
            System.err.println("Failed to cleanup data directory: " + e.getMessage());
        }
    }
    
    /**
     * Simple persistent cache implementation.
     */
    static class SimplePersistentCache {
        private final java.util.Map<String, String> cache = new java.util.concurrent.ConcurrentHashMap<>();
        private final SimpleWriteAheadLog wal;
        private final String dataDir;
        private final String nodeId;
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        
        public SimplePersistentCache(String dataDir, String nodeId) throws IOException {
            this.dataDir = dataDir;
            this.nodeId = nodeId;
            this.wal = new SimpleWriteAheadLog(dataDir + "/wal", nodeId);
            
            // Perform recovery on startup
            performRecovery();
        }
        
        /**
         * Performs recovery from persistence data.
         */
        private void performRecovery() {
            System.out.println("Starting recovery process...");
            
            try {
                // Load latest snapshot if available
                Path snapshotFile = getLatestSnapshotFile();
                if (snapshotFile != null) {
                    System.out.println("Loading snapshot: " + snapshotFile);
                    loadSnapshot(snapshotFile);
                } else {
                    System.out.println("No snapshot found, starting with empty cache");
                }
                
                // Replay WAL operations
                System.out.println("Replaying WAL operations...");
                int replayedCount = wal.replay(operation -> {
                    switch (operation.getType()) {
                        case "SET":
                            cache.put(operation.getKey(), operation.getValue());
                            break;
                        case "DELETE":
                            cache.remove(operation.getKey());
                            break;
                    }
                });
                
                System.out.println("Recovery completed. Replayed " + replayedCount + " operations.");
                
            } catch (Exception e) {
                System.err.println("Recovery failed: " + e.getMessage());
            }
        }
        
        /**
         * Sets a value in the cache.
         */
        public void set(String key, String value) {
            lock.writeLock().lock();
            try {
                cache.put(key, value);
                wal.log(new SimpleWriteAheadLog.LogEntry("SET", key, value));
            } finally {
                lock.writeLock().unlock();
            }
        }
        
        /**
         * Gets a value from the cache.
         */
        public String get(String key) {
            lock.readLock().lock();
            try {
                return cache.get(key);
            } finally {
                lock.readLock().unlock();
            }
        }
        
        /**
         * Deletes a key from the cache.
         */
        public boolean delete(String key) {
            lock.writeLock().lock();
            try {
                String removed = cache.remove(key);
                if (removed != null) {
                    wal.log(new SimpleWriteAheadLog.LogEntry("DELETE", key, null));
                    return true;
                }
                return false;
            } finally {
                lock.writeLock().unlock();
            }
        }
        
        /**
         * Creates a snapshot of the current cache state.
         */
        public void createSnapshot() {
            try {
                Path snapshotFile = getSnapshotFile(Instant.now());
                System.out.println("Creating snapshot: " + snapshotFile);
                
                // Create snapshot directory if it doesn't exist
                Files.createDirectories(snapshotFile.getParent());
                
                // Write snapshot
                try (ObjectOutputStream oos = new ObjectOutputStream(
                        new BufferedOutputStream(Files.newOutputStream(snapshotFile)))) {
                    
                    oos.writeObject(new java.util.HashMap<>(cache));
                    oos.flush();
                }
                
                // Truncate WAL after successful snapshot
                wal.truncate();
                
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
                
                @SuppressWarnings("unchecked")
                java.util.Map<String, String> snapshot = (java.util.Map<String, String>) ois.readObject();
                
                // Clear current cache and restore from snapshot
                cache.clear();
                cache.putAll(snapshot);
                
                System.out.println("Snapshot loaded: " + snapshot.size() + " entries");
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
         * Closes the cache.
         */
        public void close() {
            try {
                // Create final snapshot before closing
                createSnapshot();
                wal.close();
                System.out.println("Cache closed");
            } catch (Exception e) {
                System.err.println("Failed to close cache: " + e.getMessage());
            }
        }
    }
    
    /**
     * Simple Write-Ahead Log implementation.
     */
    static class SimpleWriteAheadLog {
        private final Path logFile;
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        private BufferedWriter writer;
        private FileOutputStream fileOutputStream;
        private volatile long logSequenceNumber = 0;
        
        public SimpleWriteAheadLog(String logDir, String nodeId) throws IOException {
            Path logDirectory = Paths.get(logDir);
            if (!Files.exists(logDirectory)) {
                Files.createDirectories(logDirectory);
            }
            
            this.logFile = logDirectory.resolve(nodeId + ".wal");
            this.fileOutputStream = new FileOutputStream(logFile.toFile(), true);
            this.writer = new BufferedWriter(new OutputStreamWriter(fileOutputStream));
            
            System.out.println("Write-Ahead Log initialized: " + logFile);
        }
        
        /**
         * Logs an operation to the WAL.
         */
        public void log(LogEntry operation) {
            lock.writeLock().lock();
            try {
                operation.setSequenceNumber(++logSequenceNumber);
                operation.setTimestamp(Instant.now());
                
                String logLine = serializeLogEntry(operation);
                writer.write(logLine);
                writer.newLine();
                writer.flush();
                
                System.out.println("Logged operation: " + operation.getType() + " -> " + operation.getKey());
            } catch (IOException e) {
                System.err.println("Failed to log operation: " + operation + " - " + e.getMessage());
                throw new RuntimeException("WAL write failed", e);
            } finally {
                lock.writeLock().unlock();
            }
        }
        
        /**
         * Replays all logged operations from the WAL.
         */
        public int replay(ReplayHandler replayHandler) {
            lock.readLock().lock();
            try {
                if (!Files.exists(logFile)) {
                    System.out.println("No WAL file found, skipping replay");
                    return 0;
                }
                
                java.util.List<LogEntry> operations = new java.util.ArrayList<>();
                
                try (BufferedReader reader = Files.newBufferedReader(logFile)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.trim().isEmpty()) {
                            try {
                                LogEntry entry = deserializeLogEntry(line);
                                operations.add(entry);
                            } catch (Exception e) {
                                System.err.println("Failed to parse WAL entry: " + line + " - " + e.getMessage());
                            }
                        }
                    }
                }
                
                System.out.println("Replaying " + operations.size() + " operations from WAL");
                
                int replayed = 0;
                for (LogEntry operation : operations) {
                    try {
                        replayHandler.handle(operation);
                        replayed++;
                    } catch (Exception e) {
                        System.err.println("Failed to replay operation: " + operation + " - " + e.getMessage());
                    }
                }
                
                System.out.println("Successfully replayed " + replayed + " operations");
                return replayed;
            } catch (IOException e) {
                System.err.println("Failed to replay WAL: " + e.getMessage());
                throw new RuntimeException("WAL replay failed", e);
            } finally {
                lock.readLock().unlock();
            }
        }
        
        /**
         * Truncates the WAL file.
         */
        public void truncate() {
            lock.writeLock().lock();
            try {
                writer.close();
                fileOutputStream.close();
                
                // Clear the file
                Files.write(logFile, new byte[0], java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
                
                // Reopen for writing
                FileOutputStream newFileOutputStream = new FileOutputStream(logFile.toFile(), true);
                BufferedWriter newWriter = new BufferedWriter(new OutputStreamWriter(newFileOutputStream));
                
                // Replace references
                this.fileOutputStream = newFileOutputStream;
                this.writer = newWriter;
                this.logSequenceNumber = 0;
                
                System.out.println("WAL truncated and reset");
            } catch (IOException e) {
                System.err.println("Failed to truncate WAL: " + e.getMessage());
                throw new RuntimeException("WAL truncate failed", e);
            } finally {
                lock.writeLock().unlock();
            }
        }
        
        /**
         * Closes the WAL.
         */
        public void close() {
            lock.writeLock().lock();
            try {
                writer.flush();
                fileOutputStream.getFD().sync();
                writer.close();
                fileOutputStream.close();
                System.out.println("WAL closed");
            } catch (IOException e) {
                System.err.println("Failed to close WAL: " + e.getMessage());
            } finally {
                lock.writeLock().unlock();
            }
        }
        
        /**
         * Serializes a log entry to a string format.
         */
        private String serializeLogEntry(LogEntry entry) {
            return entry.getSequenceNumber() + "|" + 
                   entry.getTimestamp().toEpochMilli() + "|" + 
                   entry.getType() + "|" + 
                   entry.getKey() + "|" + 
                   (entry.getValue() != null ? entry.getValue() : "");
        }
        
        /**
         * Deserializes a log entry from a string format.
         */
        private LogEntry deserializeLogEntry(String line) {
            String[] parts = line.split("\\|");
            if (parts.length < 5) {
                throw new IllegalArgumentException("Invalid log entry format: " + line);
            }
            
            long sequenceNumber = Long.parseLong(parts[0]);
            long timestamp = Long.parseLong(parts[1]);
            String type = parts[2];
            String key = parts[3];
            String value = parts.length > 4 && !parts[4].isEmpty() ? parts[4] : null;
            
            LogEntry entry = new LogEntry(type, key, value);
            entry.setSequenceNumber(sequenceNumber);
            entry.setTimestamp(Instant.ofEpochMilli(timestamp));
            
            return entry;
        }
        
        /**
         * Represents a single log entry in the WAL.
         */
        static class LogEntry {
            private long sequenceNumber;
            private Instant timestamp;
            private String type;
            private String key;
            private String value;
            
            public LogEntry(String type, String key, String value) {
                this.type = type;
                this.key = key;
                this.value = value;
            }
            
            // Getters and setters
            public long getSequenceNumber() { return sequenceNumber; }
            public void setSequenceNumber(long sequenceNumber) { this.sequenceNumber = sequenceNumber; }
            
            public Instant getTimestamp() { return timestamp; }
            public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
            
            public String getType() { return type; }
            public void setType(String type) { this.type = type; }
            
            public String getKey() { return key; }
            public void setKey(String key) { this.key = key; }
            
            public String getValue() { return value; }
            public void setValue(String value) { this.value = value; }
            
            @Override
            public String toString() {
                return String.format("LogEntry{seq=%d, type=%s, key=%s, timestamp=%s}", 
                                   sequenceNumber, type, key, timestamp);
            }
        }
        
        /**
         * Handler interface for replaying WAL operations.
         */
        interface ReplayHandler {
            void handle(LogEntry operation);
        }
    }
} 