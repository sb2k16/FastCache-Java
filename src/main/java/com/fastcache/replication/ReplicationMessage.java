package com.fastcache.replication;

import java.io.Serializable;

/**
 * Redis-inspired replication protocol messages.
 * Used for communication between primary and replica nodes.
 */
public class ReplicationMessage implements Serializable {
    
    public enum Type {
        PING,                   // Health check
        PONG,                   // Health check response
        AUTH,                   // Authentication
        PORT,                   // Port information
        IP,                     // IP information
        CAPA,                   // Capabilities
        PSYNC,                  // Partial synchronization
        FULLRESYNC,             // Full resynchronization
        CONTINUE,               // Continue partial sync
        RDB,                    // RDB data
        REPLICATION_COMMAND,    // Replication command
        ACK,                    // Acknowledgment
        HEARTBEAT,              // Heartbeat
        PROMOTE,                // Promote replica to primary
        DEMOTE,                 // Demote primary to replica
        FAILOVER,               // Failover notification
        ERROR                   // Error message
    }
    
    private final Type type;
    private final String operation;  // For replication commands
    private final String key;        // For replication commands
    private final Object value;      // For replication commands
    private final long offset;       // Replication offset
    private final String data;       // Additional data (auth password, etc.)
    private final long timestamp;
    
    public ReplicationMessage(Type type, String operation, String key, Object value, long offset) {
        this.type = type;
        this.operation = operation;
        this.key = key;
        this.value = value;
        this.offset = offset;
        this.data = null;
        this.timestamp = System.currentTimeMillis();
    }
    
    public ReplicationMessage(Type type, String data, long offset) {
        this.type = type;
        this.operation = null;
        this.key = null;
        this.value = null;
        this.offset = offset;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }
    
    public ReplicationMessage(Type type, long offset) {
        this.type = type;
        this.operation = null;
        this.key = null;
        this.value = null;
        this.offset = offset;
        this.data = null;
        this.timestamp = System.currentTimeMillis();
    }
    
    // Getters
    public Type getType() { return type; }
    public String getOperation() { return operation; }
    public String getKey() { return key; }
    public Object getValue() { return value; }
    public long getOffset() { return offset; }
    public String getData() { return data; }
    public long getTimestamp() { return timestamp; }
    
    @Override
    public String toString() {
        return String.format("ReplicationMessage{type=%s, operation=%s, key=%s, offset=%d, timestamp=%d}",
                           type, operation, key, offset, timestamp);
    }
} 