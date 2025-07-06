package com.fastcache.core;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Write-Ahead Log (WAL) for durable logging of cache operations.
 * Provides crash recovery by replaying logged operations.
 */
public class WriteAheadLog {
    private final Path logFile;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private BufferedWriter writer;
    private FileOutputStream fileOutputStream;
    private volatile long lastFlushTime;
    private volatile long logSequenceNumber = 0;
    
    public WriteAheadLog(String logDir, String nodeId) throws IOException {
        Path logDirectory = Paths.get(logDir);
        if (!Files.exists(logDirectory)) {
            Files.createDirectories(logDirectory);
        }
        
        this.logFile = logDirectory.resolve(nodeId + ".wal");
        this.fileOutputStream = new FileOutputStream(logFile.toFile(), true);
        this.writer = new BufferedWriter(new OutputStreamWriter(fileOutputStream));
        this.lastFlushTime = System.currentTimeMillis();
        
        System.out.println("Write-Ahead Log initialized: " + logFile);
    }
    
    /**
     * Logs a cache operation to the WAL.
     * @param operation The operation to log
     * @return The log sequence number
     */
    public long log(LogEntry operation) {
        lock.writeLock().lock();
        try {
            operation.setSequenceNumber(++logSequenceNumber);
            operation.setTimestamp(Instant.now());
            
            String logLine = serializeLogEntry(operation);
            writer.write(logLine);
            writer.newLine();
            writer.flush();
            
            // Force to disk periodically
            if (System.currentTimeMillis() - lastFlushTime > 1000) {
                fileOutputStream.getFD().sync();
                lastFlushTime = System.currentTimeMillis();
            }
            
            System.out.println("Logged operation: " + operation.getType() + " -> " + operation.getKey());
            return logSequenceNumber;
        } catch (IOException e) {
            System.err.println("Failed to log operation: " + operation + " - " + e.getMessage());
            throw new RuntimeException("WAL write failed", e);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Replays all logged operations from the WAL.
     * @param replayHandler Handler to process replayed operations
     * @return Number of operations replayed
     */
    public int replay(ReplayHandler replayHandler) {
        lock.readLock().lock();
        try {
            if (!Files.exists(logFile)) {
                System.out.println("No WAL file found, skipping replay");
                return 0;
            }
            
            List<LogEntry> operations = new ArrayList<>();
            
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
     * Truncates the WAL file (used after successful snapshot creation).
     */
    public void truncate() {
        lock.writeLock().lock();
        try {
            writer.close();
            fileOutputStream.close();
            
            // Clear the file
            Files.write(logFile, new byte[0], StandardOpenOption.TRUNCATE_EXISTING);
            
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
     * Flushes the WAL to disk.
     */
    public void flush() {
        lock.writeLock().lock();
        try {
            writer.flush();
            fileOutputStream.getFD().sync();
            lastFlushTime = System.currentTimeMillis();
        } catch (IOException e) {
            System.err.println("Failed to flush WAL: " + e.getMessage());
            throw new RuntimeException("WAL flush failed", e);
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
            flush();
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
     * Gets the current log sequence number.
     */
    public long getLogSequenceNumber() {
        return logSequenceNumber;
    }
    
    /**
     * Serializes a log entry to a string format.
     */
    private String serializeLogEntry(LogEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append(entry.getSequenceNumber()).append("|");
        sb.append(entry.getTimestamp().toEpochMilli()).append("|");
        sb.append(entry.getType()).append("|");
        sb.append(entry.getKey()).append("|");
        
        if (entry.getDataType() == CacheEntry.EntryType.SORTED_SET) {
            sb.append("SORTED_SET|");
            sb.append(entry.getMember()).append("|");
            sb.append(entry.getScore());
        } else {
            sb.append(entry.getDataType()).append("|");
            sb.append(entry.getValue()).append("|");
            sb.append(entry.getTtlSeconds());
        }
        
        return sb.toString();
    }
    
    /**
     * Deserializes a log entry from a string format.
     */
    private LogEntry deserializeLogEntry(String line) {
        String[] parts = line.split("\\|");
        if (parts.length < 6) {
            throw new IllegalArgumentException("Invalid log entry format: " + line);
        }
        
        long sequenceNumber = Long.parseLong(parts[0]);
        long timestamp = Long.parseLong(parts[1]);
        String type = parts[2];
        String key = parts[3];
        String dataTypeStr = parts[4];
        
        LogEntry entry = new LogEntry();
        entry.setSequenceNumber(sequenceNumber);
        entry.setTimestamp(Instant.ofEpochMilli(timestamp));
        entry.setType(type);
        entry.setKey(key);
        
        if ("SORTED_SET".equals(dataTypeStr)) {
            entry.setDataType(CacheEntry.EntryType.SORTED_SET);
            entry.setMember(parts[5]);
            entry.setScore(Double.parseDouble(parts[6]));
        } else {
            entry.setDataType(CacheEntry.EntryType.valueOf(dataTypeStr));
            entry.setValue(parts[5]);
            entry.setTtlSeconds(Long.parseLong(parts[6]));
        }
        
        return entry;
    }
    
    /**
     * Represents a single log entry in the WAL.
     */
    public static class LogEntry {
        private long sequenceNumber;
        private Instant timestamp;
        private String type;
        private String key;
        private Object value;
        private long ttlSeconds;
        private CacheEntry.EntryType dataType;
        private String member; // For sorted set operations
        private Double score; // For sorted set operations
        
        // Default constructor
        public LogEntry() {}
        
        public LogEntry(String type, String key, Object value, long ttlSeconds, CacheEntry.EntryType dataType) {
            this.type = type;
            this.key = key;
            this.value = value;
            this.ttlSeconds = ttlSeconds;
            this.dataType = dataType;
        }
        
        public LogEntry(String type, String key, String member, Double score) {
            this.type = type;
            this.key = key;
            this.member = member;
            this.score = score;
            this.dataType = CacheEntry.EntryType.SORTED_SET;
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
        
        public Object getValue() { return value; }
        public void setValue(Object value) { this.value = value; }
        
        public long getTtlSeconds() { return ttlSeconds; }
        public void setTtlSeconds(long ttlSeconds) { this.ttlSeconds = ttlSeconds; }
        
        public CacheEntry.EntryType getDataType() { return dataType; }
        public void setDataType(CacheEntry.EntryType dataType) { this.dataType = dataType; }
        
        public String getMember() { return member; }
        public void setMember(String member) { this.member = member; }
        
        public Double getScore() { return score; }
        public void setScore(Double score) { this.score = score; }
        
        @Override
        public String toString() {
            return String.format("LogEntry{seq=%d, type=%s, key=%s, timestamp=%s}", 
                               sequenceNumber, type, key, timestamp);
        }
    }
    
    /**
     * Handler interface for replaying WAL operations.
     */
    public interface ReplayHandler {
        void handle(LogEntry operation);
    }
    
    /**
     * Returns all log entries from the WAL file as a list.
     */
    public List<LogEntry> getAllEntries() {
        lock.readLock().lock();
        try {
            List<LogEntry> entries = new ArrayList<>();
            if (!Files.exists(logFile)) {
                return entries;
            }
            try (BufferedReader reader = Files.newBufferedReader(logFile)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().isEmpty()) {
                        try {
                            LogEntry entry = deserializeLogEntry(line);
                            entries.add(entry);
                        } catch (Exception e) {
                            System.err.println("Failed to parse WAL entry: " + line + " - " + e.getMessage());
                        }
                    }
                }
            }
            return entries;
        } catch (IOException e) {
            System.err.println("Failed to read WAL entries: " + e.getMessage());
            throw new RuntimeException("WAL read failed", e);
        } finally {
            lock.readLock().unlock();
        }
    }
} 