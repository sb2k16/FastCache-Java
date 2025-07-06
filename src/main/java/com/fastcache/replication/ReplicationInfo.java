package com.fastcache.replication;

import java.util.List;

/**
 * Replication information similar to Redis INFO replication output.
 * Provides detailed status about replication state and connected replicas.
 */
public class ReplicationInfo {
    
    // Role information
    private String role; // "primary" or "replica"
    private String primaryNodeId; // Only for replicas
    private int connectedReplicas; // Only for primary
    
    // Replication stream information
    private String replicationId;
    private long replicationOffset;
    private ReplicationState state;
    
    // Replica details (only for primary)
    private List<ReplicaInfo> replicaInfos;
    
    // Backlog information
    private long backlogFirstByteOffset;
    private long backlogLen;
    private boolean backlogActive;
    
    // Performance metrics
    private long replicationLag;
    private long lastSyncTime;
    private int syncInProgress;
    
    public ReplicationInfo() {}
    
    // Getters and setters
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    
    public String getPrimaryNodeId() { return primaryNodeId; }
    public void setPrimaryNodeId(String primaryNodeId) { this.primaryNodeId = primaryNodeId; }
    
    public int getConnectedReplicas() { return connectedReplicas; }
    public void setConnectedReplicas(int connectedReplicas) { this.connectedReplicas = connectedReplicas; }
    
    public String getReplicationId() { return replicationId; }
    public void setReplicationId(String replicationId) { this.replicationId = replicationId; }
    
    public long getReplicationOffset() { return replicationOffset; }
    public void setReplicationOffset(long replicationOffset) { this.replicationOffset = replicationOffset; }
    
    public ReplicationState getState() { return state; }
    public void setState(ReplicationState state) { this.state = state; }
    
    public List<ReplicaInfo> getReplicaInfos() { return replicaInfos; }
    public void setReplicaInfos(List<ReplicaInfo> replicaInfos) { this.replicaInfos = replicaInfos; }
    
    public long getBacklogFirstByteOffset() { return backlogFirstByteOffset; }
    public void setBacklogFirstByteOffset(long backlogFirstByteOffset) { this.backlogFirstByteOffset = backlogFirstByteOffset; }
    
    public long getBacklogLen() { return backlogLen; }
    public void setBacklogLen(long backlogLen) { this.backlogLen = backlogLen; }
    
    public boolean isBacklogActive() { return backlogActive; }
    public void setBacklogActive(boolean backlogActive) { this.backlogActive = backlogActive; }
    
    public long getReplicationLag() { return replicationLag; }
    public void setReplicationLag(long replicationLag) { this.replicationLag = replicationLag; }
    
    public long getLastSyncTime() { return lastSyncTime; }
    public void setLastSyncTime(long lastSyncTime) { this.lastSyncTime = lastSyncTime; }
    
    public int getSyncInProgress() { return syncInProgress; }
    public void setSyncInProgress(int syncInProgress) { this.syncInProgress = syncInProgress; }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("ReplicationInfo{");
        sb.append("role=").append(role);
        
        if ("replica".equals(role)) {
            sb.append(", primaryNodeId=").append(primaryNodeId);
        } else {
            sb.append(", connectedReplicas=").append(connectedReplicas);
        }
        
        sb.append(", replicationId=").append(replicationId);
        sb.append(", replicationOffset=").append(replicationOffset);
        sb.append(", state=").append(state);
        sb.append(", backlogActive=").append(backlogActive);
        sb.append(", backlogLen=").append(backlogLen);
        sb.append(", replicationLag=").append(replicationLag);
        sb.append("}");
        
        return sb.toString();
    }
} 