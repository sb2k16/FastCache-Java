package com.fastcache.proxy;

import com.fastcache.cluster.CacheNode;
import com.fastcache.cluster.ConsistentHash;
import com.fastcache.discovery.ServiceDiscoveryClient;
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
 * Integrates with service discovery for dynamic node discovery and health checking.
 */
public class FastCacheProxy {
    private final ConsistentHash consistentHash;
    private final Map<String, CacheNodeConnection> nodeConnections;
    private final HealthServiceClient healthServiceClient;
    private final ServiceDiscoveryClient serviceDiscoveryClient;
    private final String host;
    private final int port;
    private final String proxyId;
    private Channel serverChannel;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    public FastCacheProxy(String host, int port, String proxyId, 
                         String serviceDiscoveryUrl, String healthServiceUrl) {
        this.host = host;
        this.port = port;
        this.proxyId = proxyId;
        this.consistentHash = new ConsistentHash(150);
        this.nodeConnections = new ConcurrentHashMap<>();
        this.healthServiceClient = new HealthServiceClient(healthServiceUrl);
        this.serviceDiscoveryClient = new ServiceDiscoveryClient(serviceDiscoveryUrl);
        
        System.out.println("FastCacheProxy " + proxyId + " initialized");
        System.out.println("Service Discovery URL: " + serviceDiscoveryUrl);
        System.out.println("Health Service URL: " + healthServiceUrl);
    }

    /**
     * Starts the proxy server with dynamic node discovery.
     */
    public void start() throws Exception {
        // Start service discovery client for dynamic node discovery
        serviceDiscoveryClient.startPeriodicRefresh();
        
        // Initial node discovery
        refreshNodes();
        
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
     * Refreshes the node list from service discovery and updates connections.
     */
    private void refreshNodes() {
        try {
            List<CacheNode> healthyNodes = serviceDiscoveryClient.getCachedNodes();
            System.out.println("Discovered " + healthyNodes.size() + " healthy nodes");
            
            // Update consistent hash with new node list
            consistentHash.clear();
            for (CacheNode node : healthyNodes) {
                consistentHash.addNode(node);
                
                // Create connection if it doesn't exist
                if (!nodeConnections.containsKey(node.getId())) {
                    CacheNodeConnection connection = new CacheNodeConnection(node, new NioEventLoopGroup(1));
                    nodeConnections.put(node.getId(), connection);
                    connectWithRetry(connection, node);
                }
            }
            
            // Remove connections for nodes that are no longer healthy
            nodeConnections.entrySet().removeIf(entry -> {
                String nodeId = entry.getKey();
                boolean stillHealthy = healthyNodes.stream()
                    .anyMatch(node -> node.getId().equals(nodeId));
                
                if (!stillHealthy) {
                    System.out.println("Removing connection to unhealthy node: " + nodeId);
                    entry.getValue().disconnect();
                    return true;
                }
                return false;
            });
            
        } catch (Exception e) {
            System.err.println("Failed to refresh nodes: " + e.getMessage());
        }
    }

    /**
     * Connects to a node with retry logic for failed connections.
     */
    private void connectWithRetry(CacheNodeConnection connection, CacheNode node) {
        System.out.println("Attempting to connect to node " + node.getId() + " at " + node.getHost() + ":" + node.getPort());
        connection.connect().exceptionally(throwable -> {
            System.err.println("Failed to connect to node " + node.getId() + ": " + throwable.getMessage());
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
     * Routes a command to a healthy node with health verification.
     */
    public CompletableFuture<CacheResponse> routeCommand(CacheCommand command) {
        String key = command.getKey();
        CacheNode node = consistentHash.getNode(key);
        
        if (node == null) {
            return CompletableFuture.completedFuture(CacheResponse.error("No available nodes"));
        }
        
        // Additional health check before routing
        return healthServiceClient.isNodeHealthy(node.getId())
            .thenCompose(isHealthy -> {
                if (!isHealthy) {
                    System.err.println("Node " + node.getId() + " failed health check, skipping");
                    // Try to find another node or return error
                    return CompletableFuture.completedFuture(CacheResponse.error("Node unhealthy"));
                }
                
                CacheNodeConnection connection = nodeConnections.get(node.getId());
                if (connection != null) {
                    String commandStr = buildCommandString(command);
                    return connection.sendCommand(commandStr)
                            .thenApply(response -> {
                                try {
                                    return parseJsonResponse(response);
                                } catch (Exception e) {
                                    return CacheResponse.error("Failed to parse response: " + e.getMessage());
                                }
                            })
                            .exceptionally(ex -> CacheResponse.error("Node communication failed: " + ex.getMessage()));
                }
                
                return CompletableFuture.completedFuture(CacheResponse.error("Node unavailable"));
            });
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
        if (serviceDiscoveryClient != null) {
            serviceDiscoveryClient.stopPeriodicRefresh();
        }
        System.out.println("FastCacheProxy " + proxyId + " shutdown complete");
    }

    public static void main(String[] args) {
        // Parse command line arguments
        String host = "0.0.0.0";
        int port = 6379;
        String proxyId = "proxy1";
        String serviceDiscoveryUrl = "http://localhost:8080";
        String healthServiceUrl = "http://localhost:8080";

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
                case "--service-discovery":
                    if (i + 1 < args.length) serviceDiscoveryUrl = args[++i];
                    break;
                case "--health-service":
                    if (i + 1 < args.length) healthServiceUrl = args[++i];
                    break;
            }
        }

        FastCacheProxy proxy = new FastCacheProxy(host, port, proxyId, serviceDiscoveryUrl, healthServiceUrl);
        
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
} 