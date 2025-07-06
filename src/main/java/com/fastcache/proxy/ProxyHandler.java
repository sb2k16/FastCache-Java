package com.fastcache.proxy;

import com.fastcache.protocol.CacheResponse;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * Enhanced handler that parses Redis protocol and routes commands to data nodes.
 */
public class ProxyHandler extends ChannelInboundHandlerAdapter {
    private final FastCacheProxy proxy;
    private final StringBuilder buffer = new StringBuilder();

    public ProxyHandler(FastCacheProxy proxy) {
        this.proxy = proxy;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        try {
            if (msg instanceof ByteBuf) {
                ByteBuf buf = (ByteBuf) msg;
                String data = buf.toString(StandardCharsets.UTF_8);
                buf.release();
                
                System.out.println("Proxy received data: [" + data + "]");
                buffer.append(data);
                processBuffer(ctx);
            }
        } catch (Exception e) {
            System.err.println("Error handling message: " + e.getMessage());
            sendError(ctx, "ERR " + e.getMessage());
        }
    }

    private void processBuffer(ChannelHandlerContext ctx) {
        String data = buffer.toString();
        System.out.println("Processing buffer: [" + data + "]");
        
        // Simple Redis protocol parser for basic commands
        if (data.startsWith("*")) {
            // Array format: *2\r\n$3\r\nSET\r\n$4\r\nkey1\r\n$9\r\nvalue123\r\n
            String[] lines = data.split("\r\n");
            
            // Check if we have a complete command
            if (lines.length >= 2) {
                try {
                    int arraySize = Integer.parseInt(lines[0].substring(1)); // Remove '*' and parse
                    System.out.println("Array size: " + arraySize);
                    
                    // For a complete command, we need: *N\r\n + N*2 lines (each bulk string has 2 lines: $LEN\r\nVALUE\r\n)
                    int expectedLines = 1 + (arraySize * 2);
                    System.out.println("Expected lines: " + expectedLines + ", actual lines: " + lines.length);
                    
                    if (lines.length >= expectedLines) {
                        // We have a complete command, process it
                        String command = lines[2]; // Skip *N and $LEN
                        if (command.startsWith("$")) {
                            command = lines[3]; // Get actual command
                        }
                        
                        System.out.println("Processing command: " + command);
                        
                        if (command.equalsIgnoreCase("PING")) {
                            sendResponse(ctx, "+PONG\r\n");
                        } else if (command.equalsIgnoreCase("SET") && lines.length >= 6) {
                            String key = lines[4];
                            if (key.startsWith("$")) {
                                key = lines[5];
                            }
                            String value = lines[6];
                            if (value.startsWith("$")) {
                                value = lines[7];
                            }
                            handleSet(ctx, key, value);
                        } else if (command.equalsIgnoreCase("GET") && lines.length >= 4) {
                            String key = lines[4];
                            if (key.startsWith("$")) {
                                key = lines[5];
                            }
                            handleGet(ctx, key);
                        } else if (command.equalsIgnoreCase("DEL") && lines.length >= 4) {
                            String key = lines[4];
                            if (key.startsWith("$")) {
                                key = lines[5];
                            }
                            handleDelete(ctx, key);
                        } else {
                            sendError(ctx, "ERR Unknown command: " + command);
                        }
                        
                        buffer.setLength(0); // Clear buffer after processing
                    } else {
                        // Incomplete command - this might be a health check from HAProxy
                        // Respond with OK to keep HAProxy happy
                        System.out.println("Incomplete command, responding with OK for health check");
                        sendResponse(ctx, "+OK\r\n");
                        buffer.setLength(0);
                    }
                } catch (NumberFormatException e) {
                    System.err.println("Error parsing array size: " + e.getMessage());
                    sendError(ctx, "ERR Invalid protocol");
                    buffer.setLength(0);
                }
            } else {
                // Incomplete command - this might be a health check from HAProxy
                // Respond with OK to keep HAProxy happy
                System.out.println("Incomplete command, responding with OK for health check");
                sendResponse(ctx, "+OK\r\n");
                buffer.setLength(0);
            }
        }
    }

    private void handleSet(ChannelHandlerContext ctx, String key, String value) {
        try {
            CompletableFuture<CacheResponse> future = proxy.routeCommand(
                new com.fastcache.protocol.CacheCommand(
                    com.fastcache.protocol.CacheCommand.CommandType.SET,
                    key,
                    value,
                    -1,
                    com.fastcache.core.CacheEntry.EntryType.STRING
                )
            );
            
            future.thenAccept(response -> {
                if (response.isSuccess()) {
                    sendResponse(ctx, "+OK\r\n");
                } else {
                    sendError(ctx, "ERR " + response.getErrorMessage());
                }
            }).exceptionally(throwable -> {
                sendError(ctx, "ERR " + throwable.getMessage());
                return null;
            });
        } catch (Exception e) {
            sendError(ctx, "ERR " + e.getMessage());
        }
    }

    private void handleGet(ChannelHandlerContext ctx, String key) {
        try {
            CompletableFuture<CacheResponse> future = proxy.routeCommand(
                new com.fastcache.protocol.CacheCommand(
                    com.fastcache.protocol.CacheCommand.CommandType.GET,
                    key,
                    null,
                    -1,
                    com.fastcache.core.CacheEntry.EntryType.STRING
                )
            );
            
            future.thenAccept(response -> {
                if (response.isSuccess()) {
                    Object result = response.getData();
                    if (result != null) {
                        String responseStr = "$" + result.toString().length() + "\r\n" + result + "\r\n";
                        sendResponse(ctx, responseStr);
                    } else {
                        sendResponse(ctx, "$-1\r\n"); // Null response
                    }
                } else {
                    sendError(ctx, "ERR " + response.getErrorMessage());
                }
            }).exceptionally(throwable -> {
                sendError(ctx, "ERR " + throwable.getMessage());
                return null;
            });
        } catch (Exception e) {
            sendError(ctx, "ERR " + e.getMessage());
        }
    }

    private void handleDelete(ChannelHandlerContext ctx, String key) {
        try {
            CompletableFuture<CacheResponse> future = proxy.routeCommand(
                new com.fastcache.protocol.CacheCommand(
                    com.fastcache.protocol.CacheCommand.CommandType.DEL,
                    key,
                    null,
                    -1,
                    com.fastcache.core.CacheEntry.EntryType.STRING
                )
            );
            
            future.thenAccept(response -> {
                if (response.isSuccess()) {
                    sendResponse(ctx, "+OK\r\n");
                } else {
                    sendError(ctx, "ERR " + response.getErrorMessage());
                }
            }).exceptionally(throwable -> {
                sendError(ctx, "ERR " + throwable.getMessage());
                return null;
            });
        } catch (Exception e) {
            sendError(ctx, "ERR " + e.getMessage());
        }
    }

    private void sendResponse(ChannelHandlerContext ctx, String response) {
        ByteBuf buf = Unpooled.wrappedBuffer(response.getBytes(StandardCharsets.UTF_8));
        ctx.writeAndFlush(buf);
    }

    private void sendError(ChannelHandlerContext ctx, String error) {
        String response = "-" + error + "\r\n";
        ByteBuf buf = Unpooled.wrappedBuffer(response.getBytes(StandardCharsets.UTF_8));
        ctx.writeAndFlush(buf);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.err.println("Proxy handler exception: " + cause.getMessage());
        ctx.close();
    }
} 