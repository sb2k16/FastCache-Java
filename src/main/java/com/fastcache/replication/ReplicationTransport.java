package com.fastcache.replication;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Transport interface for replication communication between nodes.
 * Handles network connections and message passing for replication protocol.
 */
public interface ReplicationTransport {
    
    /**
     * Connects to a node for replication.
     * @param nodeId The target node ID
     * @param host The target node host
     * @param port The target node port
     * @return true if connection was successful
     */
    boolean connectToNode(String nodeId, String host, int port);
    
    /**
     * Disconnects from a node.
     * @param nodeId The node ID to disconnect from
     */
    void disconnectFromNode(String nodeId);
    
    /**
     * Sends a replication message to a node.
     * @param nodeId The target node ID
     * @param message The message to send
     * @return CompletableFuture that completes with true if message was sent successfully
     */
    CompletableFuture<Boolean> sendMessage(String nodeId, ReplicationMessage message);
    
    /**
     * Receives RDB data from a node (for initial synchronization).
     * @param nodeId The source node ID
     * @return CompletableFuture that completes with the RDB data
     */
    CompletableFuture<byte[]> receiveRDB(String nodeId);
    
    /**
     * Receives buffered commands from a node (for initial synchronization).
     * @param nodeId The source node ID
     * @return CompletableFuture that completes with the list of commands
     */
    CompletableFuture<List<ReplicationMessage>> receiveCommands(String nodeId);
    
    /**
     * Checks if a connection to a node is active.
     * @param nodeId The node ID to check
     * @return true if connection is active
     */
    boolean isConnected(String nodeId);
    
    /**
     * Gets the list of connected node IDs.
     * @return List of connected node IDs
     */
    List<String> getConnectedNodes();
    
    /**
     * Shuts down the transport layer.
     */
    void shutdown();
} 