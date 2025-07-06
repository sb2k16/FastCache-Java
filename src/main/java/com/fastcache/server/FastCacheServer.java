package com.fastcache.server;

import com.fastcache.cluster.CacheNode;
import com.fastcache.cluster.DistributedCacheManager;
import com.fastcache.core.CacheEngine;
import com.fastcache.core.EvictionPolicy;
import com.fastcache.core.PersistentCacheEngine;
import com.fastcache.protocol.CacheCommand;
import com.fastcache.protocol.CacheResponse;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.bytes.ByteArrayDecoder;
import io.netty.handler.codec.bytes.ByteArrayEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Main FastCache server that provides a distributed caching service.
 */
public class FastCacheServer {
    private static final Logger logger = LoggerFactory.getLogger(FastCacheServer.class);
    
    private final String host;
    private final int port;
    private final String nodeId;
    private final DistributedCacheManager clusterManager;
    private final CacheEngine localEngine;
    private final CacheNode localNode;
    private final boolean persistenceEnabled;
    private final String dataDir;
    
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private volatile boolean running = false;
    
    public FastCacheServer(String host, int port) {
        this(host, port, "node-" + host + "-" + port, false, "/app/data");
    }
    
    public FastCacheServer(String host, int port, String nodeId) {
        this(host, port, nodeId, false, "/app/data");
    }
    
    public FastCacheServer(String host, int port, String nodeId, boolean persistenceEnabled, String dataDir) {
        this.host = host;
        this.port = port;
        this.nodeId = nodeId;
        this.persistenceEnabled = persistenceEnabled;
        this.dataDir = dataDir;
        
        // Initialize local cache engine based on persistence setting
        try {
            if (persistenceEnabled) {
                logger.info("Initializing persistent cache engine with data directory: {}", dataDir);
                this.localEngine = new PersistentCacheEngine(dataDir, nodeId, 10000, new EvictionPolicy.LRU());
                logger.info("Persistent cache engine initialized successfully");
            } else {
                logger.info("Initializing in-memory cache engine");
                this.localEngine = new CacheEngine(10000, new EvictionPolicy.LRU());
            }
        } catch (IOException e) {
            logger.error("Failed to initialize cache engine", e);
            throw new RuntimeException("Cache engine initialization failed", e);
        }
        
        // Initialize local node
        this.localNode = new CacheNode(nodeId, host, port);
        
        // Initialize distributed cache manager
        this.clusterManager = new DistributedCacheManager(150, 2, true, persistenceEnabled, dataDir, 10000, new EvictionPolicy.LRU());
        this.clusterManager.addNode(localNode, localEngine);
        
        logger.info("FastCache server initialized: {}:{} (node: {}, persistence: {})", 
                   host, port, nodeId, persistenceEnabled ? "enabled" : "disabled");
    }
    
    /**
     * Starts the FastCache server.
     */
    public void start() throws InterruptedException {
        if (running) {
            logger.warn("Server is already running");
            return;
        }
        
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline()
                                    .addLast(new ByteArrayDecoder())
                                    .addLast(new ByteArrayEncoder())
                                    .addLast(new CacheServerHandler(FastCacheServer.this));
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);
            
            ChannelFuture future = bootstrap.bind(new InetSocketAddress(host, port)).sync();
            serverChannel = future.channel();
            running = true;
            
            logger.info("FastCache server started on {}:{}", host, port);
            
            // Keep the server running
            serverChannel.closeFuture().sync();
            
        } finally {
            shutdown();
        }
    }
    
    /**
     * Stops the FastCache server.
     */
    public void stop() {
        if (!running) {
            logger.warn("Server is not running");
            return;
        }
        
        running = false;
        
        if (serverChannel != null) {
            serverChannel.close();
        }
        
        shutdown();
        logger.info("FastCache server stopped");
    }
    
    private void shutdown() {
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        
        clusterManager.shutdown();
        localEngine.shutdown();
    }
    
    /**
     * Processes a cache command and returns the response.
     * @param command The cache command to process
     * @return The response to the command
     */
    public CompletableFuture<CacheResponse> processCommand(CacheCommand command) {
        try {
            switch (command.getType()) {
                case GET:
                    return processGet(command);
                case SET:
                    return processSet(command);
                case DEL:
                    return processDelete(command);
                case EXISTS:
                    return processExists(command);
                case EXPIRE:
                    return processExpire(command);
                case TTL:
                    return processTtl(command);
                case FLUSH:
                    return processFlush(command);
                case PING:
                    return CompletableFuture.completedFuture(CacheResponse.ok("PONG"));
                case INFO:
                    return CompletableFuture.completedFuture(CacheResponse.ok(getServerInfo()));
                case STATS:
                    return CompletableFuture.completedFuture(CacheResponse.ok(getStats()));
                case CLUSTER_INFO:
                    return CompletableFuture.completedFuture(CacheResponse.ok(getClusterInfo()));
                case CLUSTER_NODES:
                    return CompletableFuture.completedFuture(CacheResponse.ok(getClusterNodes()));
                case CLUSTER_STATS:
                    return CompletableFuture.completedFuture(CacheResponse.ok(getClusterStats()));
                default:
                    return CompletableFuture.completedFuture(
                            CacheResponse.invalidCommand("Unsupported command: " + command.getType())
                    );
            }
        } catch (Exception e) {
            logger.error("Error processing command: {}", command, e);
            return CompletableFuture.completedFuture(CacheResponse.error(e.getMessage()));
        }
    }
    
    private CompletableFuture<CacheResponse> processGet(CacheCommand command) {
        if (!command.hasKey()) {
            return CompletableFuture.completedFuture(CacheResponse.invalidCommand("GET requires a key"));
        }
        
        return clusterManager.get(command.getKey())
                .thenApply(result -> {
                    if (result != null) {
                        return CacheResponse.ok(result);
                    } else {
                        return CacheResponse.notFound();
                    }
                })
                .orTimeout(5, TimeUnit.SECONDS)
                .exceptionally(throwable -> CacheResponse.timeout());
    }
    
    private CompletableFuture<CacheResponse> processSet(CacheCommand command) {
        if (!command.hasKey() || !command.hasValue()) {
            return CompletableFuture.completedFuture(CacheResponse.invalidCommand("SET requires both key and value"));
        }
        
        return clusterManager.set(command.getKey(), command.getValue(), 
                                 command.getTtlSeconds(), command.getDataType())
                .thenApply(success -> success ? CacheResponse.ok() : CacheResponse.error("Failed to set value"))
                .orTimeout(5, TimeUnit.SECONDS)
                .exceptionally(throwable -> CacheResponse.timeout());
    }
    
    private CompletableFuture<CacheResponse> processDelete(CacheCommand command) {
        if (!command.hasKey()) {
            return CompletableFuture.completedFuture(CacheResponse.invalidCommand("DEL requires a key"));
        }
        
        return clusterManager.delete(command.getKey())
                .thenApply(success -> CacheResponse.ok(success))
                .orTimeout(5, TimeUnit.SECONDS)
                .exceptionally(throwable -> CacheResponse.timeout());
    }
    
    private CompletableFuture<CacheResponse> processExists(CacheCommand command) {
        if (!command.hasKey()) {
            return CompletableFuture.completedFuture(CacheResponse.invalidCommand("EXISTS requires a key"));
        }
        
        return clusterManager.exists(command.getKey())
                .thenApply(exists -> CacheResponse.ok(exists))
                .orTimeout(5, TimeUnit.SECONDS)
                .exceptionally(throwable -> CacheResponse.timeout());
    }
    
    private CompletableFuture<CacheResponse> processExpire(CacheCommand command) {
        if (!command.hasKey() || !command.hasTtl()) {
            return CompletableFuture.completedFuture(CacheResponse.invalidCommand("EXPIRE requires key and TTL"));
        }
        
        return clusterManager.expire(command.getKey(), command.getTtlSeconds())
                .thenApply(success -> CacheResponse.ok(success))
                .orTimeout(5, TimeUnit.SECONDS)
                .exceptionally(throwable -> CacheResponse.timeout());
    }
    
    private CompletableFuture<CacheResponse> processTtl(CacheCommand command) {
        if (!command.hasKey()) {
            return CompletableFuture.completedFuture(CacheResponse.invalidCommand("TTL requires a key"));
        }
        
        return clusterManager.ttl(command.getKey())
                .thenApply(ttl -> CacheResponse.ok(ttl))
                .orTimeout(5, TimeUnit.SECONDS)
                .exceptionally(throwable -> CacheResponse.timeout());
    }
    
    private CompletableFuture<CacheResponse> processFlush(CacheCommand command) {
        return clusterManager.flush()
                .thenApply(v -> CacheResponse.ok())
                .orTimeout(10, TimeUnit.SECONDS)
                .exceptionally(throwable -> CacheResponse.timeout());
    }
    
    private String getServerInfo() {
        return String.format("FastCache Server v1.0.0\n" +
                "Node ID: %s\n" +
                "Host: %s\n" +
                "Port: %d\n" +
                "Status: %s\n" +
                "Cluster Nodes: %d",
                nodeId, host, port, running ? "RUNNING" : "STOPPED", clusterManager.getNodeCount());
    }
    
    private String getStats() {
        return clusterManager.getClusterStats().toString();
    }
    
    private String getClusterInfo() {
        DistributedCacheManager.ClusterStats stats = clusterManager.getClusterStats();
        return String.format("Cluster Info:\n" +
                "Nodes: %d\n" +
                "Virtual Nodes: %d\n" +
                "Total Size: %d\n" +
                "Hit Rate: %.2f%%\n" +
                "Distribution: %s",
                stats.getNodeCount(), stats.getVirtualNodeCount(), 
                stats.getTotalSize(), stats.getOverallHitRate() * 100,
                stats.getDistributionStats());
    }
    
    private String getClusterNodes() {
        StringBuilder sb = new StringBuilder();
        sb.append("Cluster Nodes:\n");
        clusterManager.getAllNodes().forEach(node -> 
                sb.append(String.format("  %s (%s:%d) - %s\n", 
                        node.getId(), node.getHost(), node.getPort(), node.getStatus())));
        return sb.toString();
    }
    
    private String getClusterStats() {
        return clusterManager.getClusterStats().toString();
    }
    
    public boolean isRunning() {
        return running;
    }
    
    public String getNodeId() {
        return nodeId;
    }
    
    public DistributedCacheManager getClusterManager() {
        return clusterManager;
    }
    
    public CacheEngine getLocalEngine() {
        return localEngine;
    }
    
    /**
     * Main method to start the FastCache server.
     */
    public static void main(String[] args) {
        String host = "localhost";
        int port = 6379;
        String nodeId = null;
        boolean persistenceEnabled = false;
        String dataDir = "/app/data";
        boolean clusterMode = false;
        String clusterNodes = "";
        
        // Parse command line arguments
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-h":
                case "--host":
                    if (i + 1 < args.length) {
                        host = args[++i];
                    }
                    break;
                case "-p":
                case "--port":
                    if (i + 1 < args.length) {
                        port = Integer.parseInt(args[++i]);
                    }
                    break;
                case "--node-id":
                    if (i + 1 < args.length) {
                        nodeId = args[++i];
                    }
                    break;
                case "--persistence-enabled":
                    persistenceEnabled = true;
                    break;
                case "--data-dir":
                    if (i + 1 < args.length) {
                        dataDir = args[++i];
                    }
                    break;
                case "--cluster-mode":
                    clusterMode = true;
                    break;
                case "--cluster-nodes":
                    if (i + 1 < args.length) {
                        clusterNodes = args[++i];
                    }
                    break;
                case "--help":
                    System.out.println("Usage: FastCacheServer [options]");
                    System.out.println("Options:");
                    System.out.println("  -h, --host HOST              Server host (default: localhost)");
                    System.out.println("  -p, --port PORT              Server port (default: 6379)");
                    System.out.println("  --node-id ID                 Node ID (default: node-HOST-PORT)");
                    System.out.println("  --persistence-enabled        Enable data persistence");
                    System.out.println("  --data-dir DIR               Data directory for persistence (default: /app/data)");
                    System.out.println("  --cluster-mode               Enable cluster mode");
                    System.out.println("  --cluster-nodes NODES        Comma-separated list of cluster nodes");
                    System.out.println("  --help                       Show this help message");
                    System.exit(0);
                    break;
            }
        }
        
        if (nodeId == null) {
            nodeId = "node-" + host + "-" + port;
        }
        
        // Check environment variables for persistence settings
        String envPersistence = System.getenv("PERSISTENCE_ENABLED");
        if (envPersistence != null && envPersistence.equalsIgnoreCase("true")) {
            persistenceEnabled = true;
        }
        
        String envDataDir = System.getenv("DATA_DIR");
        if (envDataDir != null && !envDataDir.isEmpty()) {
            dataDir = envDataDir;
        }
        
        FastCacheServer server = new FastCacheServer(host, port, nodeId, persistenceEnabled, dataDir);
        
        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down FastCache server...");
            server.stop();
        }));
        
        try {
            server.start();
        } catch (InterruptedException e) {
            logger.error("Server interrupted", e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("Failed to start server", e);
            System.exit(1);
        }
    }
} 