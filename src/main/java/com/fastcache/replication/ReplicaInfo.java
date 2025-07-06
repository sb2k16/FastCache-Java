package com.fastcache.replication;

/**
 * Information about a connected replica node.
 * Similar to Redis replica tracking information.
 */
public class ReplicaInfo {
    
    private final String nodeId;
    private final String host;
    private final int port;
    private final String state; // "online", "offline", "syncing"
    private final long offset;
    private final long lag; // Replication lag in milliseconds
    private final long lastPingTime;
    private final long lastAckTime;
    
    public ReplicaInfo(String nodeId, String host, int port, String state, 
                      long offset, long lag, long lastPingTime, long lastAckTime) {
        this.nodeId = nodeId;
        this.host = host;
        this.port = port;
        this.state = state;
        this.offset = offset;
        this.lag = lag;
        this.lastPingTime = lastPingTime;
        this.lastAckTime = lastAckTime;
    }
    
    // Getters
    public String getNodeId() { return nodeId; }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public String getState() { return state; }
    public long getOffset() { return offset; }
    public long getLag() { return lag; }
    public long getLastPingTime() { return lastPingTime; }
    public long getLastAckTime() { return lastAckTime; }
    
    @Override
    public String toString() {
        return String.format("ReplicaInfo{nodeId=%s, host=%s, port=%d, state=%s, offset=%d, lag=%d}",
                           nodeId, host, port, state, offset, lag);
    }
} 