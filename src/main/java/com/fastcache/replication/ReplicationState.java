package com.fastcache.replication;

/**
 * Redis-inspired replication states.
 * Tracks the various states during replication setup and operation.
 */
public enum ReplicationState {
    REPL_NONE,              // No replication configured
    REPL_CONNECT,           // Attempting to connect to primary
    REPL_CONNECTING,        // Connection in progress
    REPL_RECEIVE_PONG,      // Waiting for PONG response from primary
    REPL_RECEIVE_AUTH,      // Waiting for AUTH response
    REPL_RECEIVE_PORT,      // Waiting for PORT response
    REPL_RECEIVE_IP,        // Waiting for IP response
    REPL_RECEIVE_CAPA,      // Waiting for CAPA response
    REPL_RECEIVE_PSYNC,     // Waiting for PSYNC response
    REPL_TRANSFER,          // Receiving RDB file from primary
    REPL_CONNECTED          // Connected and actively replicating
} 