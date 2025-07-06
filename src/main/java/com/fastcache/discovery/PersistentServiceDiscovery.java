package com.fastcache.discovery;

import com.fastcache.cluster.CacheNode;
import com.fastcache.core.WriteAheadLog;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Persistent Service Discovery component that provides durable node registration
 * and discovery capabilities with crash recovery and data persistence.
 * 
 * Uses Write-Ahead Log (WAL) for crash recovery and snapshots for fast startup.
 */
public class PersistentServiceDiscovery extends ServiceDiscovery {
    
    private final WriteAheadLog writeAheadLog;
    private final String dataDir;
    private final String serviceId;
    private final ScheduledExecutorService snapshotExecutor;
    private volatile boolean recoveryMode = false;
    
    public PersistentServiceDiscovery(String dataDir, String serviceId) throws IOException {
        this(dataDir, serviceId, 30000, 60000);
    }
    
    public PersistentServiceDiscovery(String dataDir, String serviceId, 
                                    int healthCheckIntervalMs, int nodeTimeoutMs) throws IOException {
        super(healthCheckIntervalMs, nodeTimeoutMs);
        this.dataDir = dataDir;
        this.serviceId = serviceId;
        this.writeAheadLog = new WriteAheadLog(dataDir + "/wal", serviceId);
        this.snapshotExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "service-discovery-snapshot");
            t.setDaemon(true);
            return t;
        });
        
        // Create data directory if it doesn't exist
        Files.createDirectories(Paths.get(dataDir));
        Files.createDirectories(Paths.get(dataDir, "snapshots"));
        
        // Schedule periodic snapshots
        this.snapshotExecutor.scheduleAtFixedRate(this::createSnapshot, 5, 5, TimeUnit.MINUTES);
        
        // Perform recovery on startup
        performRecovery();
        
        System.out.println("PersistentServiceDiscovery initialized with dataDir=" + dataDir + 
                          ", serviceId=" + serviceId);
    }
    
    /**
     * Performs recovery from persistence data on startup.
     */
    private void performRecovery() throws IOException {
        System.out.println("Starting service discovery recovery...");
        recoveryMode = true;
        
        try {
            // Load latest snapshot if available
            Path snapshotFile = getLatestSnapshotFile();
            if (snapshotFile != null) {
                try {
                    loadSnapshot(snapshotFile);
                } catch (ClassNotFoundException e) {
                    System.err.println("Failed to load snapshot due to class not found: " + e.getMessage());
                }
                System.out.println("Loaded snapshot: " + snapshotFile.getFileName());
            }
            
            // Replay WAL operations since snapshot
            replayWriteAheadLog();
            
            System.out.println("Service discovery recovery completed");
            
        } finally {
            recoveryMode = false;
        }
    }
    
    /**
     * Creates a snapshot of current node registrations.
     */
    private void createSnapshot() {
        try {
            long timestamp = System.currentTimeMillis();
            String snapshotFileName = serviceId + "_" + timestamp + ".snapshot";
            Path snapshotFile = Paths.get(dataDir, "snapshots", snapshotFileName);
            
            // Get current node data
            SnapshotData snapshot = new SnapshotData();
            snapshot.setTimestamp(timestamp);
            snapshot.setServiceId(serviceId);
            
            // Convert ServiceNode objects to serializable format
            Map<String, ServiceNodeData> nodeData = new HashMap<>();
            for (Map.Entry<String, ServiceNode> entry : getRegisteredNodesMap().entrySet()) {
                ServiceNode node = entry.getValue();
                ServiceNodeData nodeDataObj = new ServiceNodeData(
                    node.getNodeId(),
                    node.getHost(),
                    node.getPort(),
                    node.getNodeType(),
                    node.getRegisteredAt().toEpochMilli(),
                    node.isHealthy(),
                    node.getLastSeen().toEpochMilli(),
                    node.getLastHealthCheck().toEpochMilli()
                );
                nodeData.put(node.getNodeId(), nodeDataObj);
            }
            snapshot.setNodes(nodeData);
            
            // Write snapshot to file
            try (ObjectOutputStream oos = new ObjectOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(snapshotFile)))) {
                oos.writeObject(snapshot);
            }
            
            // Clean up old snapshots (keep last 5)
            cleanupOldSnapshots();
            
            System.out.println("Created service discovery snapshot: " + snapshotFileName);
            
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
            
            // Clear current registrations
            clearRegistrations();
            
            // Restore node registrations
            for (ServiceNodeData nodeData : snapshot.getNodes().values()) {
                ServiceNode node = new ServiceNode(
                    nodeData.getNodeId(),
                    nodeData.getHost(),
                    nodeData.getPort(),
                    nodeData.getNodeType(),
                    Instant.ofEpochMilli(nodeData.getRegisteredAt())
                );
                node.updateHealth(nodeData.isHealthy());
                node.updateLastSeen();
                
                // Register the node
                registerNodeInternal(node);
            }
            
            System.out.println("Loaded " + snapshot.getNodes().size() + " nodes from snapshot");
        }
    }
    
    /**
     * Replays operations from the write-ahead log.
     */
    private void replayWriteAheadLog() throws IOException {
        System.out.println("Replaying write-ahead log...");
        
        List<WriteAheadLog.LogEntry> entries = writeAheadLog.getAllEntries();
        int replayed = 0;
        
        for (WriteAheadLog.LogEntry entry : entries) {
            try {
                replayLogEntry(entry);
                replayed++;
            } catch (Exception e) {
                System.err.println("Failed to replay log entry: " + entry + ", error: " + e.getMessage());
            }
        }
        
        System.out.println("Replayed " + replayed + " log entries");
    }
    
    /**
     * Replays a single log entry.
     */
    private void replayLogEntry(WriteAheadLog.LogEntry entry) {
        switch (entry.getType()) {
            case "REGISTER":
                // Node registration is already handled by snapshot
                break;
            case "DEREGISTER":
                deregisterNodeInternal(entry.getKey());
                break;
            case "HEALTH_UPDATE":
                updateNodeHealthInternal(entry.getKey(), Boolean.parseBoolean(entry.getValue().toString()));
                break;
            case "HEARTBEAT":
                heartbeatInternal(entry.getKey());
                break;
            default:
                System.err.println("Unknown log entry type: " + entry.getType());
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
     * Cleans up old snapshots, keeping only the last 5.
     */
    private void cleanupOldSnapshots() throws IOException {
        Path snapshotDir = Paths.get(dataDir, "snapshots");
        if (!Files.exists(snapshotDir)) {
            return;
        }
        
        List<Path> snapshots = Files.list(snapshotDir)
                .filter(path -> path.toString().endsWith(".snapshot"))
                .sorted(Path::compareTo)
                .toList();
        
        // Keep only the last 5 snapshots
        if (snapshots.size() > 5) {
            for (int i = 0; i < snapshots.size() - 5; i++) {
                Files.delete(snapshots.get(i));
                System.out.println("Deleted old snapshot: " + snapshots.get(i).getFileName());
            }
        }
    }
    
    // Override ServiceDiscovery methods to add persistence
    
    @Override
    public boolean registerNode(String nodeId, String host, int port, String nodeType) {
        boolean result = super.registerNode(nodeId, host, port, nodeType);
        if (result && !recoveryMode) {
            // Log the operation to WAL
            WriteAheadLog.LogEntry logEntry = new WriteAheadLog.LogEntry("REGISTER", nodeId, 
                host + ":" + port + ":" + nodeType, -1, null);
            writeAheadLog.log(logEntry);
        }
        return result;
    }
    
    @Override
    public boolean deregisterNode(String nodeId) {
        boolean result = super.deregisterNode(nodeId);
        if (result && !recoveryMode) {
            // Log the operation to WAL
            WriteAheadLog.LogEntry logEntry = new WriteAheadLog.LogEntry("DEREGISTER", nodeId, 
                "deregistered", -1, null);
            writeAheadLog.log(logEntry);
        }
        return result;
    }
    
    @Override
    public boolean updateNodeHealth(String nodeId, boolean healthy) {
        boolean result = super.updateNodeHealth(nodeId, healthy);
        if (result && !recoveryMode) {
            // Log the operation to WAL
            WriteAheadLog.LogEntry logEntry = new WriteAheadLog.LogEntry("HEALTH_UPDATE", nodeId, 
                String.valueOf(healthy), -1, null);
            writeAheadLog.log(logEntry);
        }
        return result;
    }
    
    @Override
    public boolean heartbeat(String nodeId) {
        boolean result = super.heartbeat(nodeId);
        if (result && !recoveryMode) {
            // Log the operation to WAL
            WriteAheadLog.LogEntry logEntry = new WriteAheadLog.LogEntry("HEARTBEAT", nodeId, 
                "heartbeat", -1, null);
            writeAheadLog.log(logEntry);
        }
        return result;
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
        System.out.println("PersistentServiceDiscovery shutdown complete");
    }
    
    // Helper methods for internal operations during recovery
    
    private void registerNodeInternal(ServiceNode node) {
        getRegisteredNodesMap().put(node.getNodeId(), node);
        if (node.isHealthy()) {
            getHealthyNodesMap().put(node.getNodeId(), node);
        }
    }
    
    private void deregisterNodeInternal(String nodeId) {
        getRegisteredNodesMap().remove(nodeId);
        getHealthyNodesMap().remove(nodeId);
    }
    
    private void updateNodeHealthInternal(String nodeId, boolean healthy) {
        ServiceNode node = getRegisteredNodesMap().get(nodeId);
        if (node != null) {
            node.updateHealth(healthy);
            if (healthy) {
                getHealthyNodesMap().put(nodeId, node);
            } else {
                getHealthyNodesMap().remove(nodeId);
            }
        }
    }
    
    private void heartbeatInternal(String nodeId) {
        ServiceNode node = getRegisteredNodesMap().get(nodeId);
        if (node != null) {
            node.updateLastSeen();
        }
    }
    
    private void clearRegistrations() {
        getRegisteredNodesMap().clear();
        getHealthyNodesMap().clear();
    }
    
    // Access to protected fields from parent class
    @SuppressWarnings("unchecked")
    private Map<String, ServiceNode> getRegisteredNodesMap() {
        try {
            java.lang.reflect.Field field = ServiceDiscovery.class.getDeclaredField("registeredNodes");
            field.setAccessible(true);
            return (Map<String, ServiceNode>) field.get(this);
        } catch (Exception e) {
            throw new RuntimeException("Failed to access registeredNodes", e);
        }
    }
    
    @SuppressWarnings("unchecked")
    private Map<String, ServiceNode> getHealthyNodesMap() {
        try {
            java.lang.reflect.Field field = ServiceDiscovery.class.getDeclaredField("healthyNodes");
            field.setAccessible(true);
            return (Map<String, ServiceNode>) field.get(this);
        } catch (Exception e) {
            throw new RuntimeException("Failed to access healthyNodes", e);
        }
    }
    
    /**
     * Gets persistence statistics.
     */
    public PersistentServiceDiscoveryStats getPersistentStats() {
        return new PersistentServiceDiscoveryStats(
            getStats(),
            writeAheadLog.getLogSequenceNumber(),
            dataDir,
            serviceId
        );
    }
    
    /**
     * Statistics for persistent service discovery.
     */
    public static class PersistentServiceDiscoveryStats {
        private final ServiceDiscoveryStats serviceStats;
        private final long logSequenceNumber;
        private final String dataDir;
        private final String serviceId;
        
        public PersistentServiceDiscoveryStats(ServiceDiscoveryStats serviceStats, 
                                             long logSequenceNumber, String dataDir, String serviceId) {
            this.serviceStats = serviceStats;
            this.logSequenceNumber = logSequenceNumber;
            this.dataDir = dataDir;
            this.serviceId = serviceId;
        }
        
        public ServiceDiscoveryStats getServiceStats() { return serviceStats; }
        public long getLogSequenceNumber() { return logSequenceNumber; }
        public String getDataDir() { return dataDir; }
        public String getServiceId() { return serviceId; }
        
        @Override
        public String toString() {
            return String.format("PersistentServiceDiscoveryStats{serviceStats=%s, logSeq=%d, dataDir=%s, serviceId=%s}",
                    serviceStats, logSequenceNumber, dataDir, serviceId);
        }
    }
    
    /**
     * Serializable data for service node.
     */
    public static class ServiceNodeData implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String nodeId;
        private final String host;
        private final int port;
        private final String nodeType;
        private final long registeredAt;
        private final boolean healthy;
        private final long lastSeen;
        private final long lastHealthCheck;
        
        public ServiceNodeData(String nodeId, String host, int port, String nodeType, 
                             long registeredAt, boolean healthy, long lastSeen, long lastHealthCheck) {
            this.nodeId = nodeId;
            this.host = host;
            this.port = port;
            this.nodeType = nodeType;
            this.registeredAt = registeredAt;
            this.healthy = healthy;
            this.lastSeen = lastSeen;
            this.lastHealthCheck = lastHealthCheck;
        }
        
        // Getters
        public String getNodeId() { return nodeId; }
        public String getHost() { return host; }
        public int getPort() { return port; }
        public String getNodeType() { return nodeType; }
        public long getRegisteredAt() { return registeredAt; }
        public boolean isHealthy() { return healthy; }
        public long getLastSeen() { return lastSeen; }
        public long getLastHealthCheck() { return lastHealthCheck; }
    }
    
    /**
     * Serializable snapshot data.
     */
    public static class SnapshotData implements Serializable {
        private static final long serialVersionUID = 1L;
        private long timestamp;
        private String serviceId;
        private Map<String, ServiceNodeData> nodes;
        
        public SnapshotData() {
            this.nodes = new HashMap<>();
        }
        
        // Getters and setters
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
        
        public String getServiceId() { return serviceId; }
        public void setServiceId(String serviceId) { this.serviceId = serviceId; }
        
        public Map<String, ServiceNodeData> getNodes() { return nodes; }
        public void setNodes(Map<String, ServiceNodeData> nodes) { this.nodes = nodes; }
    }
} 