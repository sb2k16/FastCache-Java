package com.fastcache.server;

import com.fastcache.protocol.CacheCommand;
import com.fastcache.protocol.CacheResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Netty channel handler for processing cache commands from clients.
 * Supports both custom text protocol and Redis RESP protocol.
 */
public class CacheServerHandler extends ChannelInboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(CacheServerHandler.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private final FastCacheServer server;
    
    public CacheServerHandler(FastCacheServer server) {
        this.server = server;
    }
    
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof byte[]) {
            byte[] data = (byte[]) msg;
            String commandStr = new String(data, StandardCharsets.UTF_8);
            
            logger.debug("Received command: {}", commandStr);
            
            try {
                CacheCommand command;
                boolean isRedisProtocol = commandStr.startsWith("*");
                
                // Check if this is Redis RESP format (starts with *)
                if (isRedisProtocol) {
                    command = parseRedisCommand(commandStr);
                } else {
                    // Use existing text-based protocol
                    command = parseCommand(commandStr);
                }
                
                // Process the command
                CompletableFuture<CacheResponse> future = server.processCommand(command);
                future.thenAccept(response -> {
                    try {
                        // Serialize and send response
                        String responseStr;
                        if (isRedisProtocol) {
                            responseStr = serializeRedisResponse(response);
                        } else {
                            responseStr = serializeResponse(response);
                        }
                        ByteBuf responseBuf = Unpooled.copiedBuffer(responseStr, CharsetUtil.UTF_8);
                        ctx.writeAndFlush(responseBuf);
                        
                        logger.debug("Sent response: {}", responseStr);
                    } catch (Exception e) {
                        logger.error("Error sending response", e);
                        if (isRedisProtocol) {
                            sendRedisError(ctx, "Internal server error");
                        } else {
                            sendError(ctx, "Internal server error");
                        }
                    }
                }).exceptionally(throwable -> {
                    logger.error("Error processing command", throwable);
                    if (isRedisProtocol) {
                        sendRedisError(ctx, "Command processing failed");
                    } else {
                        sendError(ctx, "Command processing failed");
                    }
                    return null;
                });
                
            } catch (Exception e) {
                logger.error("Error parsing command: {}", commandStr, e);
                boolean isRedisProtocol = commandStr.startsWith("*");
                if (isRedisProtocol) {
                    sendRedisError(ctx, "Invalid command format");
                } else {
                    sendError(ctx, "Invalid command format");
                }
            }
        }
    }
    
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        logger.info("Client connected: {}", ctx.channel().remoteAddress());
    }
    
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        logger.info("Client disconnected: {}", ctx.channel().remoteAddress());
    }
    
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("Channel exception", cause);
        ctx.close();
    }
    
    /**
     * Parse Redis RESP (Redis Serialization Protocol) format commands.
     * Example: "*1\r\n$4\r\nping\r\n" -> PING command
     */
    private CacheCommand parseRedisCommand(String respCommand) {
        String[] lines = respCommand.split("\r\n");
        if (lines.length < 2) {
            throw new IllegalArgumentException("Invalid RESP format");
        }
        
        // Parse array header (*number)
        String arrayHeader = lines[0];
        if (!arrayHeader.startsWith("*")) {
            throw new IllegalArgumentException("Expected array header, got: " + arrayHeader);
        }
        
        int arraySize = Integer.parseInt(arrayHeader.substring(1));
        List<String> arguments = new ArrayList<>();
        
        int lineIndex = 1;
        for (int i = 0; i < arraySize && lineIndex < lines.length; i++) {
            // Parse bulk string header ($length)
            String bulkHeader = lines[lineIndex++];
            if (!bulkHeader.startsWith("$")) {
                throw new IllegalArgumentException("Expected bulk string header, got: " + bulkHeader);
            }
            
            int stringLength = Integer.parseInt(bulkHeader.substring(1));
            
            // Get the actual string value
            if (lineIndex < lines.length) {
                String value = lines[lineIndex++];
                if (value.length() != stringLength) {
                    throw new IllegalArgumentException("String length mismatch: expected " + stringLength + ", got " + value.length());
                }
                arguments.add(value);
            }
        }
        
        if (arguments.isEmpty()) {
            throw new IllegalArgumentException("No command arguments found");
        }
        
        String commandType = arguments.get(0).toUpperCase();
        
        switch (commandType) {
            case "GET":
                if (arguments.size() < 2) {
                    throw new IllegalArgumentException("GET requires a key");
                }
                return CacheCommand.get(arguments.get(1));
                
            case "SET":
                if (arguments.size() < 3) {
                    throw new IllegalArgumentException("SET requires key and value");
                }
                String value = arguments.get(2);
                // Check if there's a TTL specified (EX option)
                if (arguments.size() >= 5 && arguments.get(3).equalsIgnoreCase("EX")) {
                    long ttl = Long.parseLong(arguments.get(4));
                    return CacheCommand.set(arguments.get(1), value, ttl);
                }
                return CacheCommand.set(arguments.get(1), value);
                
            case "DEL":
                if (arguments.size() < 2) {
                    throw new IllegalArgumentException("DEL requires a key");
                }
                return CacheCommand.delete(arguments.get(1));
                
            case "EXISTS":
                if (arguments.size() < 2) {
                    throw new IllegalArgumentException("EXISTS requires a key");
                }
                return CacheCommand.exists(arguments.get(1));
                
            case "EXPIRE":
                if (arguments.size() < 3) {
                    throw new IllegalArgumentException("EXPIRE requires key and TTL");
                }
                long ttl = Long.parseLong(arguments.get(2));
                return CacheCommand.expire(arguments.get(1), ttl);
                
            case "TTL":
                if (arguments.size() < 2) {
                    throw new IllegalArgumentException("TTL requires a key");
                }
                return CacheCommand.ttl(arguments.get(1));
                
            case "FLUSH":
                return CacheCommand.flush();
                
            case "PING":
                return CacheCommand.ping();
                
            case "INFO":
                return CacheCommand.info();
                
            case "STATS":
                return CacheCommand.stats();
                
            case "CLUSTER":
                if (arguments.size() < 2) {
                    throw new IllegalArgumentException("CLUSTER requires subcommand");
                }
                String subCommand = arguments.get(1).toUpperCase();
                switch (subCommand) {
                    case "INFO":
                        return CacheCommand.clusterInfo();
                    case "NODES":
                        return CacheCommand.clusterNodes();
                    case "STATS":
                        return CacheCommand.clusterStats();
                    default:
                        throw new IllegalArgumentException("Unknown CLUSTER subcommand: " + subCommand);
                }
                
            // Redis sorted set commands
            case "ZADD":
                if (arguments.size() < 4) {
                    throw new IllegalArgumentException("ZADD requires key, score, and member");
                }
                double score = Double.parseDouble(arguments.get(2));
                return CacheCommand.zadd(arguments.get(1), arguments.get(3), score);
                
            case "ZRANGE":
                if (arguments.size() < 4) {
                    throw new IllegalArgumentException("ZRANGE requires key, start, and stop");
                }
                int start = Integer.parseInt(arguments.get(2));
                int stop = Integer.parseInt(arguments.get(3));
                return CacheCommand.zrange(arguments.get(1), start, stop);
                
            case "ZREVRANGE":
                if (arguments.size() < 4) {
                    throw new IllegalArgumentException("ZREVRANGE requires key, start, and stop");
                }
                int revStart = Integer.parseInt(arguments.get(2));
                int revStop = Integer.parseInt(arguments.get(3));
                return CacheCommand.zrevrange(arguments.get(1), revStart, revStop);
                
            case "ZSCORE":
                if (arguments.size() < 3) {
                    throw new IllegalArgumentException("ZSCORE requires key and member");
                }
                return CacheCommand.zscore(arguments.get(1), arguments.get(2));
                
            case "ZCARD":
                if (arguments.size() < 2) {
                    throw new IllegalArgumentException("ZCARD requires key");
                }
                return CacheCommand.zcard(arguments.get(1));
                
            default:
                throw new IllegalArgumentException("Unknown command: " + commandType);
        }
    }
    
    private CacheCommand parseCommand(String commandStr) {
        String[] parts = commandStr.trim().split("\\s+");
        if (parts.length == 0) {
            throw new IllegalArgumentException("Empty command");
        }
        
        String commandType = parts[0].toUpperCase();
        
        switch (commandType) {
            case "GET":
                if (parts.length < 2) {
                    throw new IllegalArgumentException("GET requires a key");
                }
                return CacheCommand.get(parts[1]);
                
            case "SET":
                if (parts.length < 3) {
                    throw new IllegalArgumentException("SET requires key and value");
                }
                String value = parts[2];
                // Check if there's a TTL specified
                if (parts.length >= 4 && parts[3].equals("EX")) {
                    if (parts.length < 5) {
                        throw new IllegalArgumentException("SET EX requires TTL value");
                    }
                    long ttl = Long.parseLong(parts[4]);
                    return CacheCommand.set(parts[1], value, ttl);
                }
                return CacheCommand.set(parts[1], value);
                
            case "DEL":
                if (parts.length < 2) {
                    throw new IllegalArgumentException("DEL requires a key");
                }
                return CacheCommand.delete(parts[1]);
                
            case "EXISTS":
                if (parts.length < 2) {
                    throw new IllegalArgumentException("EXISTS requires a key");
                }
                return CacheCommand.exists(parts[1]);
                
            case "EXPIRE":
                if (parts.length < 3) {
                    throw new IllegalArgumentException("EXPIRE requires key and TTL");
                }
                long ttl = Long.parseLong(parts[2]);
                return CacheCommand.expire(parts[1], ttl);
                
            case "TTL":
                if (parts.length < 2) {
                    throw new IllegalArgumentException("TTL requires a key");
                }
                return CacheCommand.ttl(parts[1]);
                
            case "FLUSH":
                return CacheCommand.flush();
                
            case "PING":
                return CacheCommand.ping();
                
            case "INFO":
                return CacheCommand.info();
                
            case "STATS":
                return CacheCommand.stats();
                
            case "CLUSTER":
                if (parts.length < 2) {
                    throw new IllegalArgumentException("CLUSTER requires subcommand");
                }
                String subCommand = parts[1].toUpperCase();
                switch (subCommand) {
                    case "INFO":
                        return CacheCommand.clusterInfo();
                    case "NODES":
                        return CacheCommand.clusterNodes();
                    case "STATS":
                        return CacheCommand.clusterStats();
                    default:
                        throw new IllegalArgumentException("Unknown CLUSTER subcommand: " + subCommand);
                }
                
            default:
                throw new IllegalArgumentException("Unknown command: " + commandType);
        }
    }
    
    private String serializeResponse(CacheResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            logger.error("Error serializing response", e);
            return "{\"status\":\"ERROR\",\"errorMessage\":\"Serialization failed\"}";
        }
    }
    
    private void sendError(ChannelHandlerContext ctx, String errorMessage) {
        try {
            CacheResponse errorResponse = CacheResponse.error(errorMessage);
            String responseStr = serializeResponse(errorResponse);
            ByteBuf responseBuf = Unpooled.copiedBuffer(responseStr, CharsetUtil.UTF_8);
            ctx.writeAndFlush(responseBuf);
        } catch (Exception e) {
            logger.error("Error sending error response", e);
        }
    }
    
    /**
     * Serialize response in Redis RESP format.
     * See: https://redis.io/docs/reference/protocol-spec/
     */
    private String serializeRedisResponse(CacheResponse response) {
        if (response.isError()) {
            // Error response: -Error message\r\n
            return "-" + response.getErrorMessage() + "\r\n";
        }
        
        if (response.getData() == null) {
            // Null response: $-1\r\n
            return "$-1\r\n";
        }
        
        if (response.getData() instanceof String) {
            String data = (String) response.getData();
            // Bulk string: $length\r\ndata\r\n
            return "$" + data.length() + "\r\n" + data + "\r\n";
        }
        
        if (response.getData() instanceof Integer) {
            // Integer response: :value\r\n
            return ":" + response.getData() + "\r\n";
        }
        
        if (response.getData() instanceof Long) {
            // Integer response: :value\r\n
            return ":" + response.getData() + "\r\n";
        }
        
        if (response.getData() instanceof Boolean) {
            // Boolean response: :1\r\n for true, :0\r\n for false
            return ":" + ((Boolean) response.getData() ? "1" : "0") + "\r\n";
        }
        
        if (response.getData() instanceof List) {
            List<?> list = (List<?>) response.getData();
            // Array response: *count\r\n followed by each element
            StringBuilder sb = new StringBuilder();
            sb.append("*").append(list.size()).append("\r\n");
            for (Object item : list) {
                if (item == null) {
                    sb.append("$-1\r\n");
                } else {
                    String itemStr = item.toString();
                    sb.append("$").append(itemStr.length()).append("\r\n").append(itemStr).append("\r\n");
                }
            }
            return sb.toString();
        }
        
        // Default: convert to string
        String data = response.getData().toString();
        return "$" + data.length() + "\r\n" + data + "\r\n";
    }
    
    private void sendRedisError(ChannelHandlerContext ctx, String errorMessage) {
        try {
            String responseStr = "-" + errorMessage + "\r\n";
            ByteBuf responseBuf = Unpooled.copiedBuffer(responseStr, CharsetUtil.UTF_8);
            ctx.writeAndFlush(responseBuf);
        } catch (Exception e) {
            logger.error("Error sending Redis error response", e);
        }
    }
} 