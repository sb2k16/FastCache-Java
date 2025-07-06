package com.fastcache.replication;

import java.io.*;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Basic TCP-based implementation of ReplicationTransport.
 * Uses socket connections for replication communication.
 */
public class ReplicationTransportImpl implements ReplicationTransport {
    
    private final Map<String, Socket> connections = new ConcurrentHashMap<>();
    private final Map<String, ObjectOutputStream> outputStreams = new ConcurrentHashMap<>();
    private final Map<String, ObjectInputStream> inputStreams = new ConcurrentHashMap<>();
    private final int connectionTimeout = 5000; // 5 seconds
    private final int readTimeout = 30000; // 30 seconds
    
    @Override
    public boolean connectToNode(String nodeId, String host, int port) {
        try {
            System.out.println("Connecting to node " + nodeId + " at " + host + ":" + port);
            
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), connectionTimeout);
            socket.setSoTimeout(readTimeout);
            
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
            
            connections.put(nodeId, socket);
            outputStreams.put(nodeId, out);
            inputStreams.put(nodeId, in);
            
            System.out.println("Successfully connected to node " + nodeId);
            return true;
            
        } catch (Exception e) {
            System.err.println("Failed to connect to node " + nodeId + ": " + e.getMessage());
            return false;
        }
    }
    
    @Override
    public void disconnectFromNode(String nodeId) {
        try {
            ObjectOutputStream out = outputStreams.remove(nodeId);
            ObjectInputStream in = inputStreams.remove(nodeId);
            Socket socket = connections.remove(nodeId);
            
            if (out != null) {
                out.close();
            }
            if (in != null) {
                in.close();
            }
            if (socket != null) {
                socket.close();
            }
            
            System.out.println("Disconnected from node " + nodeId);
            
        } catch (Exception e) {
            System.err.println("Error disconnecting from node " + nodeId + ": " + e.getMessage());
        }
    }
    
    @Override
    public CompletableFuture<Boolean> sendMessage(String nodeId, ReplicationMessage message) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ObjectOutputStream out = outputStreams.get(nodeId);
                if (out == null) {
                    throw new IOException("No connection to node " + nodeId);
                }
                
                out.writeObject(message);
                out.flush();
                
                return true;
                
            } catch (Exception e) {
                System.err.println("Failed to send message to node " + nodeId + ": " + e.getMessage());
                return false;
            }
        });
    }
    
    @Override
    public CompletableFuture<byte[]> receiveRDB(String nodeId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ObjectInputStream in = inputStreams.get(nodeId);
                if (in == null) {
                    throw new IOException("No connection to node " + nodeId);
                }
                
                // Read RDB size first
                Integer rdbSize = (Integer) in.readObject();
                if (rdbSize == null || rdbSize <= 0) {
                    throw new IOException("Invalid RDB size: " + rdbSize);
                }
                
                // Read RDB data
                byte[] rdbData = new byte[rdbSize];
                int bytesRead = 0;
                while (bytesRead < rdbSize) {
                    int count = in.read(rdbData, bytesRead, rdbSize - bytesRead);
                    if (count == -1) {
                        throw new IOException("Unexpected end of stream while reading RDB");
                    }
                    bytesRead += count;
                }
                
                System.out.println("Received RDB data of size " + rdbSize + " from node " + nodeId);
                return rdbData;
                
            } catch (Exception e) {
                System.err.println("Failed to receive RDB from node " + nodeId + ": " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }
    
    @Override
    public CompletableFuture<List<ReplicationMessage>> receiveCommands(String nodeId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ObjectInputStream in = inputStreams.get(nodeId);
                if (in == null) {
                    throw new IOException("No connection to node " + nodeId);
                }
                
                List<ReplicationMessage> commands = new ArrayList<>();
                
                // Read number of commands
                Integer commandCount = (Integer) in.readObject();
                if (commandCount != null && commandCount > 0) {
                    for (int i = 0; i < commandCount; i++) {
                        ReplicationMessage command = (ReplicationMessage) in.readObject();
                        commands.add(command);
                    }
                }
                
                System.out.println("Received " + commands.size() + " commands from node " + nodeId);
                return commands;
                
            } catch (Exception e) {
                System.err.println("Failed to receive commands from node " + nodeId + ": " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }
    
    @Override
    public boolean isConnected(String nodeId) {
        Socket socket = connections.get(nodeId);
        return socket != null && socket.isConnected() && !socket.isClosed();
    }
    
    @Override
    public List<String> getConnectedNodes() {
        return new ArrayList<>(connections.keySet());
    }
    
    @Override
    public void shutdown() {
        System.out.println("Shutting down ReplicationTransport...");
        
        // Disconnect from all nodes
        List<String> nodeIds = new ArrayList<>(connections.keySet());
        for (String nodeId : nodeIds) {
            disconnectFromNode(nodeId);
        }
        
        System.out.println("ReplicationTransport shutdown complete");
    }
} 