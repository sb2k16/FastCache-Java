package com.fastcache.protocol;

import com.fastcache.core.CacheEntry;

import java.util.List;
import java.util.Objects;

/**
 * Represents a cache command sent from client to server.
 */
public class CacheCommand {
    private final CommandType type;
    private final String key;
    private final Object value;
    private final long ttlSeconds;
    private final CacheEntry.EntryType dataType;
    private final List<String> arguments;
    
    public enum CommandType {
        // Basic operations
        GET, SET, DEL, EXISTS, FLUSH,
        
        // Expiration operations
        EXPIRE, TTL, PERSIST,
        
        // String operations
        INCR, DECR, APPEND, STRLEN,
        
        // List operations
        LPUSH, RPUSH, LPOP, RPOP, LLEN, LRANGE, LINDEX,
        
        // Set operations
        SADD, SREM, SMEMBERS, SISMEMBER, SCARD, SINTER,
        
        // Sorted Set operations
        ZADD, ZRANGE, ZREVRANGE, ZSCORE, ZCARD, ZREM, ZRANK, ZREVRANK,
        
        // Hash operations
        HSET, HGET, HDEL, HGETALL, HKEYS, HVALS, HLEN,
        
        // Cluster operations
        CLUSTER_INFO, CLUSTER_NODES, CLUSTER_STATS,
        
        // Server operations
        PING, INFO, STATS, SHUTDOWN
    }
    
    public CacheCommand(CommandType type, String key) {
        this(type, key, null, -1, CacheEntry.EntryType.STRING, List.of());
    }
    
    public CacheCommand(CommandType type, String key, Object value) {
        this(type, key, value, -1, CacheEntry.EntryType.STRING, List.of());
    }
    
    public CacheCommand(CommandType type, String key, Object value, long ttlSeconds) {
        this(type, key, value, ttlSeconds, CacheEntry.EntryType.STRING, List.of());
    }
    
    public CacheCommand(CommandType type, String key, Object value, long ttlSeconds, CacheEntry.EntryType dataType) {
        this(type, key, value, ttlSeconds, dataType, List.of());
    }
    
    public CacheCommand(CommandType type, String key, Object value, long ttlSeconds, 
                       CacheEntry.EntryType dataType, List<String> arguments) {
        this.type = Objects.requireNonNull(type, "Command type cannot be null");
        this.key = key;
        this.value = value;
        this.ttlSeconds = ttlSeconds;
        this.dataType = dataType != null ? dataType : CacheEntry.EntryType.STRING;
        this.arguments = arguments != null ? arguments : List.of();
    }
    
    public static CacheCommand get(String key) {
        return new CacheCommand(CommandType.GET, key);
    }
    
    public static CacheCommand set(String key, Object value) {
        return new CacheCommand(CommandType.SET, key, value);
    }
    
    public static CacheCommand set(String key, Object value, long ttlSeconds) {
        return new CacheCommand(CommandType.SET, key, value, ttlSeconds);
    }
    
    public static CacheCommand set(String key, Object value, long ttlSeconds, CacheEntry.EntryType dataType) {
        return new CacheCommand(CommandType.SET, key, value, ttlSeconds, dataType);
    }
    
    public static CacheCommand delete(String key) {
        return new CacheCommand(CommandType.DEL, key);
    }
    
    public static CacheCommand exists(String key) {
        return new CacheCommand(CommandType.EXISTS, key);
    }
    
    public static CacheCommand expire(String key, long ttlSeconds) {
        return new CacheCommand(CommandType.EXPIRE, key, null, ttlSeconds);
    }
    
    public static CacheCommand ttl(String key) {
        return new CacheCommand(CommandType.TTL, key);
    }
    
    public static CacheCommand flush() {
        return new CacheCommand(CommandType.FLUSH, null);
    }
    
    public static CacheCommand ping() {
        return new CacheCommand(CommandType.PING, null);
    }
    
    public static CacheCommand info() {
        return new CacheCommand(CommandType.INFO, null);
    }
    
    public static CacheCommand stats() {
        return new CacheCommand(CommandType.STATS, null);
    }
    
    public static CacheCommand clusterInfo() {
        return new CacheCommand(CommandType.CLUSTER_INFO, null);
    }
    
    public static CacheCommand clusterNodes() {
        return new CacheCommand(CommandType.CLUSTER_NODES, null);
    }
    
    public static CacheCommand clusterStats() {
        return new CacheCommand(CommandType.CLUSTER_STATS, null);
    }
    
    public static CacheCommand lpush(String key, Object value) {
        return new CacheCommand(CommandType.LPUSH, key, value, -1, CacheEntry.EntryType.LIST);
    }
    
    public static CacheCommand rpush(String key, Object value) {
        return new CacheCommand(CommandType.RPUSH, key, value, -1, CacheEntry.EntryType.LIST);
    }
    
    public static CacheCommand lpop(String key) {
        return new CacheCommand(CommandType.LPOP, key);
    }
    
    public static CacheCommand rpop(String key) {
        return new CacheCommand(CommandType.RPOP, key);
    }
    
    public static CacheCommand sadd(String key, Object value) {
        return new CacheCommand(CommandType.SADD, key, value, -1, CacheEntry.EntryType.SET);
    }
    
    public static CacheCommand srem(String key, Object value) {
        return new CacheCommand(CommandType.SREM, key, value, -1, CacheEntry.EntryType.SET);
    }
    
    // Sorted Set operations
    public static CacheCommand zadd(String key, String member, double score) {
        return new CacheCommand(CommandType.ZADD, key, member, -1, CacheEntry.EntryType.SORTED_SET, List.of(String.valueOf(score)));
    }
    
    public static CacheCommand zrange(String key, int start, int stop) {
        return new CacheCommand(CommandType.ZRANGE, key, null, -1, CacheEntry.EntryType.SORTED_SET, List.of(String.valueOf(start), String.valueOf(stop)));
    }
    
    public static CacheCommand zrevrange(String key, int start, int stop) {
        return new CacheCommand(CommandType.ZREVRANGE, key, null, -1, CacheEntry.EntryType.SORTED_SET, List.of(String.valueOf(start), String.valueOf(stop)));
    }
    
    public static CacheCommand zscore(String key, String member) {
        return new CacheCommand(CommandType.ZSCORE, key, null, -1, CacheEntry.EntryType.SORTED_SET, List.of(member));
    }
    
    public static CacheCommand zcard(String key) {
        return new CacheCommand(CommandType.ZCARD, key, null, -1, CacheEntry.EntryType.SORTED_SET);
    }
    
    public static CacheCommand zrem(String key, String member) {
        return new CacheCommand(CommandType.ZREM, key, member, -1, CacheEntry.EntryType.SORTED_SET);
    }
    
    public static CacheCommand zrank(String key, String member) {
        return new CacheCommand(CommandType.ZRANK, key, null, -1, CacheEntry.EntryType.SORTED_SET, List.of(member));
    }
    
    public static CacheCommand zrevrank(String key, String member) {
        return new CacheCommand(CommandType.ZREVRANK, key, null, -1, CacheEntry.EntryType.SORTED_SET, List.of(member));
    }
    
    public static CacheCommand hset(String key, String field, Object value) {
        return new CacheCommand(CommandType.HSET, key, value, -1, CacheEntry.EntryType.HASH, List.of(field));
    }
    
    public static CacheCommand hget(String key, String field) {
        return new CacheCommand(CommandType.HGET, key, null, -1, CacheEntry.EntryType.HASH, List.of(field));
    }
    
    public CommandType getType() {
        return type;
    }
    
    public String getKey() {
        return key;
    }
    
    public Object getValue() {
        return value;
    }
    
    public long getTtlSeconds() {
        return ttlSeconds;
    }
    
    public CacheEntry.EntryType getDataType() {
        return dataType;
    }
    
    public List<String> getArguments() {
        return arguments;
    }
    
    public boolean hasKey() {
        return key != null;
    }
    
    public boolean hasValue() {
        return value != null;
    }
    
    public boolean hasTtl() {
        return ttlSeconds > 0;
    }
    
    public boolean hasArguments() {
        return !arguments.isEmpty();
    }
    
    public String getFirstArgument() {
        return arguments.isEmpty() ? null : arguments.get(0);
    }
    
    public String getSecondArgument() {
        return arguments.size() < 2 ? null : arguments.get(1);
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CacheCommand that = (CacheCommand) o;
        return ttlSeconds == that.ttlSeconds &&
                type == that.type &&
                Objects.equals(key, that.key) &&
                Objects.equals(value, that.value) &&
                dataType == that.dataType &&
                Objects.equals(arguments, that.arguments);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(type, key, value, ttlSeconds, dataType, arguments);
    }
    
    @Override
    public String toString() {
        return "CacheCommand{" +
                "type=" + type +
                ", key='" + key + '\'' +
                ", value=" + value +
                ", ttlSeconds=" + ttlSeconds +
                ", dataType=" + dataType +
                ", arguments=" + arguments +
                '}';
    }
} 