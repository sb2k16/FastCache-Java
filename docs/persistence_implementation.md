# FastCache Persistence Implementation

## Overview

FastCache now supports data persistence, enabling cache nodes to recover their data after crashes or restarts. The persistence system uses a combination of Write-Ahead Logging (WAL) and snapshots to ensure data durability and fast recovery.

## Architecture

### Components

1. **WriteAheadLog (WAL)**: Logs all cache operations before they are applied to memory
2. **PersistentCacheEngine**: Extends CacheEngine with persistence capabilities
3. **Snapshot System**: Creates periodic snapshots of the cache state
4. **Recovery Manager**: Handles the recovery process on node startup

### Data Flow

```
Client Request → Cache Engine → WAL Log → Memory Cache
                                    ↓
                              Snapshot (periodic)
```

## Implementation Details

### Write-Ahead Log (WAL)

The WAL ensures that all operations are logged to disk before being applied to memory, providing durability guarantees.

**Features:**
- Sequential logging of all cache operations
- JSON-based log format for easy parsing
- Automatic log truncation after successful snapshots
- Crash-safe with fsync operations

**Log Entry Format:**
```
{
  "sequenceNumber": 123,
  "timestamp": "2024-01-01T12:00:00Z",
  "type": "SET|DELETE|ZADD|ZREM|EXPIRE",
  "key": "user:123",
  "value": "John Doe",
  "ttlSeconds": 3600,
  "dataType": "STRING|SORTED_SET",
  "member": "player1",  // For sorted set operations
  "score": 100.0        // For sorted set operations
}
```

### Snapshot System

Snapshots provide fast recovery by creating periodic point-in-time copies of the cache state.

**Features:**
- Periodic snapshots (configurable interval)
- Automatic cleanup of old snapshots
- Binary serialization for efficiency
- Atomic snapshot creation

**Snapshot Structure:**
```java
class SnapshotData {
    Instant timestamp;
    Map<String, CacheEntry> cacheEntries;
    Map<String, SortedSet> sortedSets;
}
```

### Recovery Process

When a node starts up, it performs the following recovery steps:

1. **Detect Persistence Data**: Check for WAL files and snapshots
2. **Load Latest Snapshot**: Restore the most recent snapshot
3. **Replay WAL Operations**: Apply all operations since the snapshot
4. **Validate Data**: Ensure data integrity
5. **Resume Operations**: Start accepting new requests

## Usage

### Basic Usage

```java
// Create a persistent cache engine
PersistentCacheEngine cache = new PersistentCacheEngine("data/node1", "node1");

// Operations are automatically logged
cache.set("user:123", "John Doe", 3600, CacheEntry.EntryType.STRING);
cache.zadd("leaderboard", "player1", 100.0);

// Create a snapshot
cache.createSnapshot();

// Shutdown (creates final snapshot)
cache.shutdown();
```

### Recovery Example

```java
// Node restarts after crash
PersistentCacheEngine recoveredCache = new PersistentCacheEngine("data/node1", "node1");

// Data is automatically recovered
String user = (String) recoveredCache.get("user:123");
List<String> topPlayers = recoveredCache.zrevrange("leaderboard", 0, 2);
```

### Configuration

```java
// Custom configuration
PersistentCacheEngine cache = new PersistentCacheEngine(
    "data/node1",           // Data directory
    "node1",                // Node ID
    10000,                  // Max cache size
    new EvictionPolicy.LRU() // Eviction policy
);
```

## File Structure

```
data/
├── node1/
│   ├── wal/
│   │   └── node1.wal          # Write-ahead log
│   └── snapshots/
│       ├── node1_1234567890.snapshot
│       ├── node1_1234567900.snapshot
│       └── node1_1234567910.snapshot
└── node2/
    ├── wal/
    │   └── node2.wal
    └── snapshots/
        └── node2_1234567890.snapshot
```

## Performance Considerations

### WAL Performance
- Sequential writes for optimal disk performance
- Buffered I/O with periodic fsync
- Configurable flush intervals

### Snapshot Performance
- Background snapshot creation
- Incremental snapshots (planned)
- Compression support (planned)

### Recovery Performance
- Fast snapshot loading
- Parallel WAL replay (planned)
- Memory-mapped files (planned)

## Monitoring and Statistics

```java
PersistentCacheStats stats = cache.getPersistentStats();
System.out.println("WAL Sequence Number: " + stats.getWalSequenceNumber());
System.out.println("Cache Stats: " + stats.getCacheStats());
System.out.println("Data Directory: " + stats.getDataDir());
```

## Error Handling

### WAL Errors
- Automatic retry on write failures
- Graceful degradation if WAL is unavailable
- Data loss prevention through fsync

### Snapshot Errors
- Rollback to previous snapshot on failure
- Automatic retry mechanisms
- Fallback to WAL-only recovery

### Recovery Errors
- Partial recovery with error reporting
- Manual recovery options
- Data validation and repair tools

## Best Practices

### Configuration
- Use dedicated storage for persistence data
- Configure appropriate snapshot intervals
- Monitor disk space usage

### Operations
- Regular snapshot creation
- Monitor WAL file sizes
- Backup persistence data

### Monitoring
- Track recovery times
- Monitor WAL replay performance
- Alert on persistence failures

## Future Enhancements

### Planned Features
1. **Incremental Snapshots**: Only save changed data
2. **Compression**: Reduce storage requirements
3. **Encryption**: Secure sensitive data
4. **Replication**: Cross-node data replication
5. **Backup/Restore**: Automated backup systems

### Performance Optimizations
1. **Memory-mapped Files**: Faster I/O operations
2. **Parallel Recovery**: Multi-threaded WAL replay
3. **Delta Snapshots**: Efficient storage usage
4. **SSD Optimization**: Optimize for SSD characteristics

## Testing

### Unit Tests
- WAL operation logging and replay
- Snapshot creation and loading
- Recovery process validation
- Error handling scenarios

### Integration Tests
- End-to-end persistence workflows
- Crash recovery scenarios
- Performance benchmarks
- Stress testing

### Example Test
```java
// Test persistence and recovery
PersistentCacheEngine cache = new PersistentCacheEngine("test-data", "test-node");
cache.set("key1", "value1", 3600, CacheEntry.EntryType.STRING);
cache.createSnapshot();
cache.shutdown();

// Simulate crash and recovery
PersistentCacheEngine recovered = new PersistentCacheEngine("test-data", "test-node");
assertEquals("value1", recovered.get("key1"));
```

## Troubleshooting

### Common Issues

1. **WAL Corruption**
   - Check WAL file integrity
   - Use last known good snapshot
   - Rebuild from backup

2. **Snapshot Failures**
   - Check disk space
   - Verify file permissions
   - Review error logs

3. **Slow Recovery**
   - Optimize snapshot frequency
   - Use faster storage
   - Consider incremental snapshots

### Debug Commands
```bash
# Check WAL file
cat data/node1/wal/node1.wal

# List snapshots
ls -la data/node1/snapshots/

# Monitor recovery
tail -f logs/fastcache.log
```

## Conclusion

The persistence implementation provides FastCache with enterprise-grade durability features while maintaining high performance. The combination of WAL and snapshots ensures data safety and fast recovery times, making FastCache suitable for production environments where data loss is not acceptable. 