package com.fastcache.replication;

import com.fastcache.core.WriteAheadLog;
import com.fastcache.core.CacheEngine;
import com.fastcache.discovery.ServiceDiscovery;
import com.fastcache.cluster.CacheNode;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Redis-inspired replication manager for FastCache.
 * Handles primary-replica relationships, replication state, and coordination.
 */
public class ReplicationManager {
    
    // Replication state
    private final AtomicReference<ReplicationState> state = new AtomicReference<>(ReplicationState.REPL_NONE);
    private final AtomicReference<String> primaryNodeId = new AtomicReference<>();
    private final AtomicReference<String> replicationId = new AtomicReference<>();
    private final AtomicLong replicationOffset = new AtomicLong(0);
    
    // Node configuration
    private final String nodeId;
    private final String host;
    private final int port;
    private final CacheEngine cacheEngine;
    private final WriteAheadLog writeAheadLog;
    private final ServiceDiscovery serviceDiscovery;
    
    // Replication components
    private final ReplicationBacklog replicationBacklog;
    private final ReplicationTransport transport;
    private final ScheduledExecutorService heartbeatExecutor;
    private final ExecutorService replicationExecutor;
    
    // Configuration
    private final ReplicationConfig config;
    private final AtomicBoolean isPrimary = new AtomicBoolean(false);
    private final AtomicBoolean isReadOnly = new AtomicBoolean(false);
    
    // Replica tracking
    private final Map<String, ReplicaInfo> connectedReplicas = new ConcurrentHashMap<>();
    private final AtomicLong lastPingTime = new AtomicLong(0);
    
    public ReplicationManager(String nodeId, String host, int port, 
                            CacheEngine cacheEngine, WriteAheadLog writeAheadLog,
                            ServiceDiscovery serviceDiscovery, ReplicationConfig config) {
        this.nodeId = nodeId;
        this.host = host;
        this.port = port;
        this.cacheEngine = cacheEngine;
        this.writeAheadLog = writeAheadLog;
        this.serviceDiscovery = serviceDiscovery;
        this.config = config;
        
        // Initialize components
        this.replicationBacklog = new ReplicationBacklog(config.getBacklogSize());
        this.transport = new ReplicationTransportImpl();
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "replication-heartbeat");
            t.setDaemon(true);
            return t;
        });
        this.replicationExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "replication-worker");
            t.setDaemon(true);
            return t;
        });
        
        // Generate replication ID
        this.replicationId.set(generateReplicationId());
        
        System.out.println("ReplicationManager initialized for node: " + nodeId);
    }
    
    /**
     * Starts the replication manager.
     */
    public void start() {
        System.out.println("Starting ReplicationManager...");
        
        // Start heartbeat monitoring
        heartbeatExecutor.scheduleAtFixedRate(this::sendHeartbeat, 
            config.getHeartbeatInterval(), config.getHeartbeatInterval(), TimeUnit.MILLISECONDS);
        
        // Start replica health monitoring
        heartbeatExecutor.scheduleAtFixedRate(this::checkReplicaHealth,
            config.getReplicaCheckInterval(), config.getReplicaCheckInterval(), TimeUnit.MILLISECONDS);
        
        System.out.println("ReplicationManager started");
    }
    
    /**
     * Promotes this node to primary.
     */
    public void promoteToPrimary() {
        if (isPrimary.compareAndSet(false, true)) {
            state.set(ReplicationState.REPL_CONNECTED);
            isReadOnly.set(false);
            primaryNodeId.set(nodeId);
            
            System.out.println("Node " + nodeId + " promoted to primary");
            
            // Notify service discovery
            serviceDiscovery.updateNodeRole(nodeId, "primary");
        }
    }
    
    /**
     * Demotes this node from primary to replica.
     */
    public void demoteFromPrimary() {
        if (isPrimary.compareAndSet(true, false)) {
            state.set(ReplicationState.REPL_NONE);
            isReadOnly.set(true);
            primaryNodeId.set(null);
            
            // Clear connected replicas
            connectedReplicas.clear();
            
            System.out.println("Node " + nodeId + " demoted from primary");
            
            // Notify service discovery
            serviceDiscovery.updateNodeRole(nodeId, "replica");
        }
    }
    
    /**
     * Connects this node as a replica to a primary.
     */
    public CompletableFuture<Boolean> connectToPrimary(String primaryNodeId, String primaryHost, int primaryPort) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                System.out.println("Connecting to primary: " + primaryNodeId + " at " + primaryHost + ":" + primaryPort);
                
                state.set(ReplicationState.REPL_CONNECT);
                
                // Step 1: Establish connection
                if (!transport.connectToNode(primaryNodeId, primaryHost, primaryPort)) {
                    throw new RuntimeException("Failed to connect to primary");
                }
                
                state.set(ReplicationState.REPL_CONNECTING);
                
                // Step 2: Send PING
                if (!sendPing(primaryNodeId)) {
                    throw new RuntimeException("Primary did not respond to PING");
                }
                
                state.set(ReplicationState.REPL_RECEIVE_PONG);
                
                // Step 3: Send AUTH (if configured)
                if (config.getAuthPassword() != null) {
                    if (!sendAuth(primaryNodeId, config.getAuthPassword())) {
                        throw new RuntimeException("Authentication failed");
                    }
                }
                
                state.set(ReplicationState.REPL_RECEIVE_AUTH);
                
                // Step 4: Send PORT
                if (!sendPort(primaryNodeId, port)) {
                    throw new RuntimeException("Failed to send PORT");
                }
                
                state.set(ReplicationState.REPL_RECEIVE_PORT);
                
                // Step 5: Send IP
                if (!sendIp(primaryNodeId, host)) {
                    throw new RuntimeException("Failed to send IP");
                }
                
                state.set(ReplicationState.REPL_RECEIVE_IP);
                
                // Step 6: Send CAPA
                if (!sendCapa(primaryNodeId)) {
                    throw new RuntimeException("Failed to send CAPA");
                }
                
                state.set(ReplicationState.REPL_RECEIVE_CAPA);
                
                // Step 7: Send PSYNC
                if (!sendPsync(primaryNodeId)) {
                    throw new RuntimeException("Failed to send PSYNC");
                }
                
                state.set(ReplicationState.REPL_RECEIVE_PSYNC);
                
                // Step 8: Receive RDB and commands
                if (!receiveInitialSync(primaryNodeId)) {
                    throw new RuntimeException("Failed to receive initial sync");
                }
                
                // Success - mark as replica
                this.primaryNodeId.set(primaryNodeId);
                isPrimary.set(false);
                isReadOnly.set(true);
                state.set(ReplicationState.REPL_CONNECTED);
                
                System.out.println("Successfully connected to primary: " + primaryNodeId);
                return true;
                
            } catch (Exception e) {
                System.err.println("Failed to connect to primary: " + e.getMessage());
                state.set(ReplicationState.REPL_NONE);
                return false;
            }
        }, replicationExecutor);
    }
    
    /**
     * Handles a write operation on the primary node.
     */
    public void onWriteOperation(String operation, String key, Object value) {
        if (!isPrimary.get()) {
            throw new IllegalStateException("Node is not primary");
        }
        
        // Add to replication backlog
        replicationBacklog.addCommand(operation, key, value, replicationOffset.incrementAndGet());
        
        // Broadcast to all replicas
        broadcastToReplicas(operation, key, value);
    }
    
    /**
     * Handles a replication command from the primary.
     */
    public void onReplicationCommand(String operation, String key, Object value, long offset) {
        try {
            // Execute the command locally
            executeReplicationCommand(operation, key, value);
            
            // Update replication offset
            replicationOffset.set(offset);
            
            // Send acknowledgment to primary
            if (primaryNodeId.get() != null) {
                sendAck(primaryNodeId.get(), offset);
            }
            
        } catch (Exception e) {
            System.err.println("Failed to execute replication command: " + e.getMessage());
        }
    }
    
    /**
     * Gets replication information (similar to Redis INFO replication).
     */
    public ReplicationInfo getReplicationInfo() {
        ReplicationInfo info = new ReplicationInfo();
        info.setRole(isPrimary.get() ? "primary" : "replica");
        info.setReplicationId(replicationId.get());
        info.setReplicationOffset(replicationOffset.get());
        info.setState(state.get());
        
        if (isPrimary.get()) {
            info.setConnectedReplicas(connectedReplicas.size());
            info.setReplicaInfos(new ArrayList<>(connectedReplicas.values()));
        } else {
            info.setPrimaryNodeId(primaryNodeId.get());
        }
        
        return info;
    }
    
    // Private helper methods
    
    private String generateReplicationId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
    
    private boolean sendPing(String nodeId) {
        try {
            ReplicationMessage message = new ReplicationMessage(ReplicationMessage.Type.PING, null, null, null, 0);
            return transport.sendMessage(nodeId, message).get(config.getTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return false;
        }
    }
    
    private boolean sendAuth(String nodeId, String password) {
        try {
            ReplicationMessage message = new ReplicationMessage(ReplicationMessage.Type.AUTH, null, password, null, 0);
            return transport.sendMessage(nodeId, message).get(config.getTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return false;
        }
    }
    
    private boolean sendPort(String nodeId, int port) {
        try {
            ReplicationMessage message = new ReplicationMessage(ReplicationMessage.Type.PORT, null, String.valueOf(port), null, 0);
            return transport.sendMessage(nodeId, message).get(config.getTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return false;
        }
    }
    
    private boolean sendIp(String nodeId, String ip) {
        try {
            ReplicationMessage message = new ReplicationMessage(ReplicationMessage.Type.IP, null, ip, null, 0);
            return transport.sendMessage(nodeId, message).get(config.getTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return false;
        }
    }
    
    private boolean sendCapa(String nodeId) {
        try {
            ReplicationMessage message = new ReplicationMessage(ReplicationMessage.Type.CAPA, null, "psync2", null, 0);
            return transport.sendMessage(nodeId, message).get(config.getTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return false;
        }
    }
    
    private boolean sendPsync(String nodeId) {
        try {
            String psyncCommand = "? -1"; // Full resync
            ReplicationMessage message = new ReplicationMessage(ReplicationMessage.Type.PSYNC, null, psyncCommand, null, 0);
            return transport.sendMessage(nodeId, message).get(config.getTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return false;
        }
    }
    
    private boolean receiveInitialSync(String nodeId) {
        try {
            // Receive RDB snapshot
            byte[] rdbData = transport.receiveRDB(nodeId).get(config.getTimeoutMs(), TimeUnit.MILLISECONDS);
            
            // Load RDB into cache
            loadRDB(rdbData);
            
            // Receive buffered commands
            List<ReplicationMessage> commands = transport.receiveCommands(nodeId).get(config.getTimeoutMs(), TimeUnit.MILLISECONDS);
            
            // Apply commands
            for (ReplicationMessage command : commands) {
                executeReplicationCommand(command.getOperation(), command.getKey(), command.getValue());
            }
            
            return true;
        } catch (Exception e) {
            System.err.println("Failed to receive initial sync: " + e.getMessage());
            return false;
        }
    }
    
    private void loadRDB(byte[] rdbData) {
        // TODO: Implement RDB loading
        System.out.println("Loading RDB data of size: " + rdbData.length);
    }
    
    private void executeReplicationCommand(String operation, String key, Object value) {
        switch (operation) {
            case "SET":
                cacheEngine.set(key, value, com.fastcache.core.CacheEntry.EntryType.STRING);
                break;
            case "DELETE":
                cacheEngine.delete(key);
                break;
            case "ZADD":
                // Handle sorted set operations
                break;
            default:
                System.err.println("Unknown replication command: " + operation);
        }
    }
    
    private void broadcastToReplicas(String operation, String key, Object value) {
        for (String replicaId : connectedReplicas.keySet()) {
            replicationExecutor.submit(() -> {
                try {
                    ReplicationMessage message = new ReplicationMessage(
                        ReplicationMessage.Type.REPLICATION_COMMAND, operation, key, value, replicationOffset.get());
                    transport.sendMessage(replicaId, message);
                } catch (Exception e) {
                    System.err.println("Failed to send replication command to " + replicaId + ": " + e.getMessage());
                }
            });
        }
    }
    
    private void sendAck(String primaryId, long offset) {
        try {
            ReplicationMessage message = new ReplicationMessage(ReplicationMessage.Type.ACK, null, null, null, offset);
            transport.sendMessage(primaryId, message);
        } catch (Exception e) {
            System.err.println("Failed to send ACK to primary: " + e.getMessage());
        }
    }
    
    private void sendHeartbeat() {
        if (primaryNodeId.get() != null && !isPrimary.get()) {
            try {
                ReplicationMessage message = new ReplicationMessage(ReplicationMessage.Type.HEARTBEAT, null, null, null, 0);
                transport.sendMessage(primaryNodeId.get(), message);
                lastPingTime.set(System.currentTimeMillis());
            } catch (Exception e) {
                System.err.println("Failed to send heartbeat: " + e.getMessage());
            }
        }
    }
    
    private void checkReplicaHealth() {
        if (isPrimary.get()) {
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<String, ReplicaInfo>> it = connectedReplicas.entrySet().iterator();
            
            while (it.hasNext()) {
                Map.Entry<String, ReplicaInfo> entry = it.next();
                ReplicaInfo replica = entry.getValue();
                
                if (now - replica.getLastPingTime() > config.getReplicaTimeoutMs()) {
                    System.err.println("Replica " + entry.getKey() + " is unresponsive, removing");
                    it.remove();
                }
            }
        }
    }
    
    /**
     * Shuts down the replication manager.
     */
    public void shutdown() {
        System.out.println("Shutting down ReplicationManager...");
        
        heartbeatExecutor.shutdown();
        replicationExecutor.shutdown();
        
        try {
            if (!heartbeatExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                heartbeatExecutor.shutdownNow();
            }
            if (!replicationExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                replicationExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            heartbeatExecutor.shutdownNow();
            replicationExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        System.out.println("ReplicationManager shutdown complete");
    }
    
    // Getters
    public boolean isPrimary() { return isPrimary.get(); }
    public boolean isReadOnly() { return isReadOnly.get(); }
    public ReplicationState getState() { return state.get(); }
    public String getPrimaryNodeId() { return primaryNodeId.get(); }
    public String getReplicationId() { return replicationId.get(); }
    public long getReplicationOffset() { return replicationOffset.get(); }
} 