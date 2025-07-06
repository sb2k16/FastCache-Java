package com.fastcache.proxy;

import com.fastcache.cluster.CacheNode;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.CharsetUtil;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages a connection to a cache node.
 */
public class CacheNodeConnection {
    private final CacheNode node;
    private final EventLoopGroup clientGroup;
    private Channel channel;
    private volatile boolean connected = false;
    private final AtomicReference<CompletableFuture<String>> currentRequest = new AtomicReference<>();
    
    public CacheNodeConnection(CacheNode node, EventLoopGroup clientGroup) {
        this.node = node;
        this.clientGroup = clientGroup;
    }
    
    /**
     * Connects to the cache node.
     * @return CompletableFuture that completes when connection is established
     */
    public CompletableFuture<Void> connect() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(clientGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new StringDecoder(CharsetUtil.UTF_8))
                                .addLast(new StringEncoder(CharsetUtil.UTF_8))
                                .addLast(new NodeConnectionHandler(future, CacheNodeConnection.this));
                    }
                });
        
        bootstrap.connect(node.getHost(), node.getPort())
                .addListener((ChannelFutureListener) channelFuture -> {
                    if (channelFuture.isSuccess()) {
                        channel = channelFuture.channel();
                        connected = true;
                        System.out.println("Connected to node: " + node.getId());
                    } else {
                        connected = false;
                        System.out.println("Failed to connect to node: " + node.getId());
                        future.completeExceptionally(channelFuture.cause());
                    }
                });
        
        return future;
    }
    
    /**
     * Sends a command to the cache node.
     * @param command The command to send
     * @return CompletableFuture that completes with the response
     */
    public CompletableFuture<String> sendCommand(String command) {
        CompletableFuture<String> future = new CompletableFuture<>();
        
        if (!connected || channel == null) {
            future.completeExceptionally(new IllegalStateException("Not connected to node: " + node.getId()));
            return future;
        }
        
        // Set the current request
        if (!currentRequest.compareAndSet(null, future)) {
            future.completeExceptionally(new RuntimeException("Another request is in progress"));
            return future;
        }
        
        System.out.println("Sending to node " + node.getId() + ": " + command);
        
        channel.writeAndFlush(command + "\r\n")
                .addListener((ChannelFutureListener) channelFuture -> {
                    if (!channelFuture.isSuccess()) {
                        currentRequest.set(null);
                        future.completeExceptionally(channelFuture.cause());
                    }
                });
        
        // Add timeout
        clientGroup.schedule(() -> {
            CompletableFuture<String> current = currentRequest.get();
            if (current == future && !future.isDone()) {
                currentRequest.set(null);
                future.completeExceptionally(new RuntimeException("Request timeout"));
            }
        }, 5000, TimeUnit.MILLISECONDS);
        
        return future;
    }
    
    /**
     * Handles a response from the node.
     * @param response The response from the node
     */
    void handleResponse(String response) {
        System.out.println("Received from node " + node.getId() + ": " + response);
        
        CompletableFuture<String> current = currentRequest.get();
        if (current != null && !current.isDone()) {
            currentRequest.set(null);
            current.complete(response);
        }
    }
    
    /**
     * Disconnects from the cache node.
     */
    public void disconnect() {
        if (channel != null) {
            channel.close();
            connected = false;
        }
        // Complete current request with error
        CompletableFuture<String> current = currentRequest.get();
        if (current != null && !current.isDone()) {
            current.completeExceptionally(new RuntimeException("Connection closed"));
        }
        currentRequest.set(null);
    }
    
    /**
     * Checks if the connection is active.
     * @return true if connected
     */
    public boolean isConnected() {
        return connected && channel != null && channel.isActive();
    }
    
    /**
     * Gets the cache node.
     * @return The cache node
     */
    public CacheNode getNode() {
        return node;
    }
    
    /**
     * Channel handler for node connections.
     */
    private static class NodeConnectionHandler extends SimpleChannelInboundHandler<String> {
        private final CompletableFuture<Void> connectionFuture;
        private final CacheNodeConnection connection;
        
        public NodeConnectionHandler(CompletableFuture<Void> connectionFuture, CacheNodeConnection connection) {
            this.connectionFuture = connectionFuture;
            this.connection = connection;
        }
        
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, String msg) {
            // Handle responses from the node
            connection.handleResponse(msg);
        }
        
        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            connectionFuture.complete(null);
        }
        
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            connectionFuture.completeExceptionally(cause);
            ctx.close();
        }
    }
} 