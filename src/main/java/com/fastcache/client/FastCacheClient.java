package com.fastcache.client;

import com.fastcache.protocol.CacheCommand;
import com.fastcache.protocol.CacheResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.bytes.ByteArrayDecoder;
import io.netty.handler.codec.bytes.ByteArrayEncoder;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Simple client for connecting to FastCache server.
 */
public class FastCacheClient {
    private static final Logger logger = LoggerFactory.getLogger(FastCacheClient.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private final String host;
    private final int port;
    private final EventLoopGroup group;
    private Channel channel;
    private volatile boolean connected = false;
    
    public FastCacheClient(String host, int port) {
        this.host = host;
        this.port = port;
        this.group = new NioEventLoopGroup();
    }
    
    /**
     * Connects to the FastCache server.
     */
    public CompletableFuture<Void> connect() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new ByteArrayDecoder())
                                .addLast(new ByteArrayEncoder())
                                .addLast(new ClientHandler(future));
                    }
                });
        
        bootstrap.connect(host, port).addListener((ChannelFutureListener) channelFuture -> {
            if (channelFuture.isSuccess()) {
                channel = channelFuture.channel();
                connected = true;
                logger.info("Connected to FastCache server at {}:{}", host, port);
                future.complete(null);
            } else {
                logger.error("Failed to connect to FastCache server", channelFuture.cause());
                future.completeExceptionally(channelFuture.cause());
            }
        });
        
        return future;
    }
    
    /**
     * Disconnects from the FastCache server.
     */
    public void disconnect() {
        if (channel != null) {
            channel.close();
        }
        group.shutdownGracefully();
        connected = false;
        logger.info("Disconnected from FastCache server");
    }
    
    /**
     * Sends a command to the server and returns the response.
     */
    public CompletableFuture<CacheResponse> sendCommand(CacheCommand command) {
        if (!connected) {
            return CompletableFuture.failedFuture(new IllegalStateException("Not connected to server"));
        }
        
        CompletableFuture<CacheResponse> future = new CompletableFuture<>();
        
        try {
            String commandStr = serializeCommand(command);
            ByteBuf commandBuf = Unpooled.copiedBuffer(commandStr, StandardCharsets.UTF_8);
            
            // Store the future to be completed when response is received
            channel.attr(AttributeKey.valueOf("pendingResponse")).set(future);
            
            channel.writeAndFlush(commandBuf).addListener((ChannelFutureListener) channelFuture -> {
                if (!channelFuture.isSuccess()) {
                    future.completeExceptionally(channelFuture.cause());
                }
            });
            
            // Add timeout
            future.orTimeout(10, TimeUnit.SECONDS);
            
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        
        return future;
    }
    
    /**
     * Convenience methods for common operations
     */
    public CompletableFuture<CacheResponse> get(String key) {
        return sendCommand(CacheCommand.get(key));
    }
    
    public CompletableFuture<CacheResponse> set(String key, Object value) {
        return sendCommand(CacheCommand.set(key, value));
    }
    
    public CompletableFuture<CacheResponse> set(String key, Object value, long ttlSeconds) {
        return sendCommand(CacheCommand.set(key, value, ttlSeconds));
    }
    
    public CompletableFuture<CacheResponse> delete(String key) {
        return sendCommand(CacheCommand.delete(key));
    }
    
    public CompletableFuture<CacheResponse> exists(String key) {
        return sendCommand(CacheCommand.exists(key));
    }
    
    public CompletableFuture<CacheResponse> expire(String key, long ttlSeconds) {
        return sendCommand(CacheCommand.expire(key, ttlSeconds));
    }
    
    public CompletableFuture<CacheResponse> ttl(String key) {
        return sendCommand(CacheCommand.ttl(key));
    }
    
    public CompletableFuture<CacheResponse> flush() {
        return sendCommand(CacheCommand.flush());
    }
    
    public CompletableFuture<CacheResponse> ping() {
        return sendCommand(CacheCommand.ping());
    }
    
    public CompletableFuture<CacheResponse> info() {
        return sendCommand(CacheCommand.info());
    }
    
    public CompletableFuture<CacheResponse> stats() {
        return sendCommand(CacheCommand.stats());
    }
    
    public CompletableFuture<CacheResponse> clusterInfo() {
        return sendCommand(CacheCommand.clusterInfo());
    }
    
    public CompletableFuture<CacheResponse> clusterNodes() {
        return sendCommand(CacheCommand.clusterNodes());
    }
    
    public CompletableFuture<CacheResponse> clusterStats() {
        return sendCommand(CacheCommand.clusterStats());
    }
    
    private String serializeCommand(CacheCommand command) {
        // Simple text-based serialization for now
        StringBuilder sb = new StringBuilder();
        sb.append(command.getType().name());
        
        if (command.hasKey()) {
            sb.append(" ").append(command.getKey());
        }
        
        if (command.hasValue()) {
            sb.append(" ").append(command.getValue());
        }
        
        if (command.hasTtl()) {
            sb.append(" EX ").append(command.getTtlSeconds());
        }
        
        if (command.hasArguments()) {
            for (String arg : command.getArguments()) {
                sb.append(" ").append(arg);
            }
        }
        
        return sb.toString();
    }
    
    public boolean isConnected() {
        return connected;
    }
    
    /**
     * Client channel handler for processing responses from the server.
     */
    private static class ClientHandler extends ChannelInboundHandlerAdapter {
        private final CompletableFuture<Void> connectFuture;
        
        public ClientHandler(CompletableFuture<Void> connectFuture) {
            this.connectFuture = connectFuture;
        }
        
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof byte[]) {
                byte[] data = (byte[]) msg;
                String responseStr = new String(data, StandardCharsets.UTF_8);
                
                logger.debug("Received response: {}", responseStr);
                
                try {
                    CacheResponse response = objectMapper.readValue(responseStr, CacheResponse.class);
                    
                    // Get the pending response future and complete it
                    @SuppressWarnings("unchecked")
                    CompletableFuture<CacheResponse> pendingFuture = 
                            (CompletableFuture<CacheResponse>) ctx.channel().attr(AttributeKey.valueOf("pendingResponse")).get();
                    if (pendingFuture != null) {
                        pendingFuture.complete(response);
                        ctx.channel().attr(AttributeKey.valueOf("pendingResponse")).set(null);
                    }
                    
                } catch (Exception e) {
                    logger.error("Error parsing response", e);
                }
            }
        }
        
        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            logger.debug("Client channel active");
        }
        
        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            logger.debug("Client channel inactive");
        }
        
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            logger.error("Client channel exception", cause);
            if (!connectFuture.isDone()) {
                connectFuture.completeExceptionally(cause);
            }
            ctx.close();
        }
    }
} 