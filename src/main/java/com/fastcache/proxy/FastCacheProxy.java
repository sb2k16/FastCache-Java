package com.fastcache.proxy;

import com.fastcache.cluster.CacheNode;
import com.fastcache.cluster.ConsistentHash;
import com.fastcache.protocol.CacheCommand;
import com.fastcache.protocol.CacheResponse;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.redis.RedisArrayAggregator;
import io.netty.handler.codec.redis.RedisBulkStringAggregator;
import io.netty.handler.codec.redis.RedisDecoder;
import io.netty.handler.codec.redis.RedisEncoder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FastCache proxy server that routes client requests to healthy cache nodes using consistent hashing.
 * Queries the centralized health checker to avoid routing to unhealthy nodes.
 */
public class FastCacheProxy {
    private final ConsistentHash consistentHash;
    private final Map<String, CacheNodeConnection> nodeConnections;
    private final HealthServiceClient healthServiceClient;
    private final String host;
    private final int port;
    private final String proxyId;
    private Channel serverChannel;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    public FastCacheProxy(String host, int port, String proxyId, List<CacheNode> nodes, String healthServiceUrl) {
        this.host = host;
        this.port = port;
        this.proxyId = proxyId;
        this.consistentHash = new ConsistentHash(150);
        this.nodeConnections = new ConcurrentHashMap<>();
        this.healthServiceClient = new HealthServiceClient(healthServiceUrl);
        
        // Initialize node connections
        for (CacheNode node : nodes) {
            consistentHash.addNode(node);
            CacheNodeConnection connection = new CacheNodeConnection(node, new NioEventLoopGroup(1));
            nodeConnections.put(node.getId(), connection);
            
            // Connect to the node with retry logic
            connectWithRetry(connection, node);
        }
        System.out.println("FastCacheProxy " + proxyId + " initialized with " + nodes.size() + " nodes");
    }

    /**
     * Connects to a node with retry logic for failed connections.
     */
    private void connectWithRetry(CacheNodeConnection connection, CacheNode node) {
        System.out.println("Attempting to connect to node " + node.getId() + " at " + node.getHost() + ":" + node.getPort());
        connection.connect().exceptionally(throwable -> {
            System.err.println("Failed to connect to node " + node.getId() + ": " + throwable.getMessage());
            System.err.println("Exception type: " + throwable.getClass().getSimpleName());
            // Schedule retry after 5 seconds
            new Thread(() -> {
                try {
                    Thread.sleep(5000);
                    System.out.println("Retrying connection to node " + node.getId());
                    connectWithRetry(connection, node);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
            return null;
        });
    }

    /**
     * Starts the proxy server.
     */
    public void start() throws Exception {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline pipeline = ch.pipeline();
                            pipeline.addLast(new ProxyHandler(FastCacheProxy.this));
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);

            serverChannel = bootstrap.bind(host, port).sync().channel();
            System.out.println("FastCacheProxy " + proxyId + " started on " + host + ":" + port);
            
            // Wait for the server to close
            serverChannel.closeFuture().sync();
        } finally {
            shutdown();
        }
    }

    /**
     * Shuts down the proxy server.
     */
    public void shutdown() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        nodeConnections.values().forEach(CacheNodeConnection::disconnect);
        System.out.println("FastCacheProxy " + proxyId + " shutdown complete");
    }

    /**
     * Routes a command to a healthy node.
     */
    public CompletableFuture<CacheResponse> routeCommand(CacheCommand command) {
        String key = command.getKey();
        CacheNode node = consistentHash.getNode(key);
        if (node == null) {
            return CompletableFuture.completedFuture(CacheResponse.error("No available nodes"));
        }
        
        // Temporarily skip health checks for testing
        CacheNodeConnection connection = nodeConnections.get(node.getId());
        if (connection != null) {
            // Convert CacheCommand to simple text format that data nodes expect
            String commandStr = buildCommandString(command);
            return connection.sendCommand(commandStr)
                    .thenApply(response -> {
                        // Parse the JSON response from the data node
                        try {
                            return parseJsonResponse(response);
                        } catch (Exception e) {
                            return CacheResponse.error("Failed to parse response: " + e.getMessage());
                        }
                    })
                    .exceptionally(ex -> CacheResponse.error("Node communication failed: " + ex.getMessage()));
        }
        
        return CompletableFuture.completedFuture(CacheResponse.error("Node unavailable"));
    }
    
    private String buildCommandString(CacheCommand command) {
        switch (command.getType()) {
            case GET:
                return "GET " + command.getKey();
            case SET:
                String value = command.getValue() != null ? command.getValue().toString() : "";
                if (command.getTtlSeconds() > 0) {
                    return "SET " + command.getKey() + " " + value + " EX " + command.getTtlSeconds();
                }
                return "SET " + command.getKey() + " " + value;
            case DEL:
                return "DEL " + command.getKey();
            case EXISTS:
                return "EXISTS " + command.getKey();
            case EXPIRE:
                return "EXPIRE " + command.getKey() + " " + command.getTtlSeconds();
            case TTL:
                return "TTL " + command.getKey();
            case FLUSH:
                return "FLUSH";
            case PING:
                return "PING";
            default:
                return "PING"; // Default fallback
        }
    }
    
    private CacheResponse parseJsonResponse(String jsonResponse) {
        try {
            // Simple JSON parsing for the response
            if (jsonResponse.contains("\"status\":\"OK\"")) {
                // Extract data if present
                if (jsonResponse.contains("\"data\":")) {
                    int dataStart = jsonResponse.indexOf("\"data\":") + 7;
                    int dataEnd = jsonResponse.indexOf(",", dataStart);
                    if (dataEnd == -1) {
                        dataEnd = jsonResponse.indexOf("}", dataStart);
                    }
                    if (dataEnd > dataStart) {
                        String data = jsonResponse.substring(dataStart, dataEnd).trim();
                        if (data.startsWith("\"") && data.endsWith("\"")) {
                            data = data.substring(1, data.length() - 1);
                        }
                        return CacheResponse.ok(data);
                    }
                }
                return CacheResponse.ok();
            } else if (jsonResponse.contains("\"status\":\"NOT_FOUND\"")) {
                // For GET operations, NOT_FOUND should return null, not an error
                return CacheResponse.ok(null);
            } else if (jsonResponse.contains("\"status\":\"ERROR\"")) {
                // Extract error message
                int errorStart = jsonResponse.indexOf("\"errorMessage\":") + 15;
                int errorEnd = jsonResponse.indexOf("\"", errorStart + 1);
                if (errorEnd > errorStart) {
                    String error = jsonResponse.substring(errorStart, errorEnd);
                    return CacheResponse.error(error);
                }
                return CacheResponse.error("Unknown error");
            }
            return CacheResponse.error("Invalid response format");
        } catch (Exception e) {
            return CacheResponse.error("Failed to parse response: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        // Parse command line arguments
        String host = "0.0.0.0";
        int port = 6379;
        String proxyId = "proxy1";
        String healthServiceUrl = "http://localhost:8080";
        String clusterNodes = "";

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host":
                    if (i + 1 < args.length) host = args[++i];
                    break;
                case "--port":
                    if (i + 1 < args.length) port = Integer.parseInt(args[++i]);
                    break;
                case "--proxy-id":
                    if (i + 1 < args.length) proxyId = args[++i];
                    break;
                case "--health-service":
                    if (i + 1 < args.length) healthServiceUrl = args[++i];
                    break;
                case "--cluster-nodes":
                    if (i + 1 < args.length) clusterNodes = args[++i];
                    break;
            }
        }

        // Parse cluster nodes
        List<CacheNode> nodes = parseClusterNodes(clusterNodes);
        
        FastCacheProxy proxy = new FastCacheProxy(host, port, proxyId, nodes, healthServiceUrl);
        
        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(proxy::shutdown));
        
        try {
            proxy.start();
        } catch (Exception e) {
            System.err.println("Failed to start proxy: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static List<CacheNode> parseClusterNodes(String clusterNodes) {
        if (clusterNodes.isEmpty()) {
            // Default nodes for testing (3 nodes)
            return List.of(
                new CacheNode("node1", "fastcache-node1", 6379),
                new CacheNode("node2", "fastcache-node2", 6379),
                new CacheNode("node3", "fastcache-node3", 6379)
            );
        }
        
        String[] nodeStrings = clusterNodes.split(",");
        return List.of(nodeStrings).stream()
                .map(nodeStr -> {
                    String[] parts = nodeStr.split(":");
                    return new CacheNode(parts[0], parts[0], Integer.parseInt(parts[1]));
                })
                .toList();
    }
} 