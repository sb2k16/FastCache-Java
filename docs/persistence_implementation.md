# FastCache Persistence Implementation

## Overview

FastCache provides enterprise-grade persistence capabilities through a combination of Write-Ahead Logging (WAL) and periodic snapshots. This ensures data durability and enables crash recovery, making FastCache suitable for production environments where data loss is not acceptable.

## Architecture

### Persistence Layers

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           PERSISTENCE LAYERS                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐             │
│  │   Memory Cache  │  │   Write Buffer  │  │   Disk Storage  │             │
│  │   (Fast)        │  │   (Async)       │  │   (Persistent)  │             │
│  │                 │  │                 │  │                 │             │
│  │ • LRU Cache     │  │ • WAL Buffer    │  │ • Snapshots     │             │
│  │ • Eviction      │  │ • Async Flush   │  │ • WAL Files     │             │
│  │ • TTL Support   │  │ • Batch Writes  │  │ • Recovery      │             │
│  └─────────────────┘  └─────────────────┘  └─────────────────┘             │
│           │                     │                     │                    │
│           └─────────────────────┼─────────────────────┘                    │
│                                 │                                          │
│                    ┌─────────────▼─────────────┐                          │
│                    │     Crash Recovery        │                          │
│                    │                           │                          │
│                    │ • Load Latest Snapshot    │                          │
│                    │ • Replay WAL Operations   │                          │
│                    │ • Validate Data Integrity │                          │
│                    │ • Resume Operations       │                          │
│                    └───────────────────────────┘                          │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Components

1. **Write-Ahead Log (WAL)**: Logs all operations sequentially for crash recovery
2. **Snapshots**: Periodic full state dumps for fast recovery
3. **Recovery Engine**: Automatically recovers data on startup
4. **Configuration Management**: Flexible persistence settings

## Usage

### Basic Usage

#### Command Line

```bash
# Start server with persistence enabled
java -jar FastCache-1.0.0-fat.jar \
  --host 0.0.0.0 \
  --port 6379 \
  --node-id node1 \
  --persistence-enabled \
  --data-dir /app/data

# Using environment variables
export PERSISTENCE_ENABLED=true
export DATA_DIR=/app/data
java -jar FastCache-1.0.0-fat.jar --host 0.0.0.0 --port 6379
```

#### Docker

```yaml
version: '3.8'
services:
  fastcache-node:
    image: fastcache:latest
    environment:
      - PERSISTENCE_ENABLED=true
      - DATA_DIR=/app/data
    volumes:
      - fastcache_data:/app/data
    command: ["java", "-jar", "FastCache-1.0.0-fat.jar", "--persistence-enabled"]
```

### Programmatic Usage

#### Direct Usage

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

#### Configuration-Based Usage

```java
// Configure persistence
PersistenceConfig config = PersistenceConfig.builder()
    .enablePersistence(true)
    .enableWriteAheadLog(true)
    .enableSnapshots(true)
    .snapshotInterval(Duration.ofMinutes(5))
    .persistenceDir("/app/data")
    .enableCompression(false)
    .build();

// Use with FastCache server
FastCacheServer server = new FastCacheServer("localhost", 6379, "node1", true, "/app/data");
```

### Recovery Example

```java
// Node restarts after crash
PersistentCacheEngine recoveredCache = new PersistentCacheEngine("data/node1", "node1");

// Data is automatically recovered
String user = (String) recoveredCache.get("user:123");
List<String> topPlayers = recoveredCache.zrevrange("leaderboard", 0, 2);
```

## Configuration

### Environment Variables

```bash
# Basic persistence
PERSISTENCE_ENABLED=true
DATA_DIR=/app/data

# Advanced settings
SNAPSHOT_INTERVAL=PT5M          # ISO-8601 duration
WAL_FLUSH_INTERVAL=PT1S         # ISO-8601 duration
MAX_SNAPSHOT_SIZE=1073741824    # 1GB in bytes

# Optional features
ENABLE_COMPRESSION=true
ENABLE_ENCRYPTION=true
ENCRYPTION_KEY=your-secret-key
```

### Configuration Class

```java
PersistenceConfig config = PersistenceConfig.builder()
    .enablePersistence(true)
    .enableWriteAheadLog(true)
    .enableSnapshots(true)
    .snapshotInterval(Duration.ofMinutes(5))
    .persistenceDir("/app/data")
    .maxSnapshotSize(1024 * 1024 * 1024) // 1GB
    .enableCompression(false)
    .enableEncryption(false)
    .build();
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

## Recovery Process

When a node starts up, it performs the following recovery steps:

1. **Detect Persistence Data**: Check for WAL files and snapshots
2. **Load Latest Snapshot**: Restore the most recent snapshot
3. **Replay WAL Operations**: Apply all operations since the snapshot
4. **Validate Data**: Ensure data integrity
5. **Resume Operations**: Start accepting new requests

## Performance Considerations

### WAL Performance
- Sequential writes for optimal disk performance
- Buffered I/O with periodic fsync
- Configurable flush intervals

### Snapshot Performance
- Asynchronous snapshot creation
- Configurable snapshot intervals
- Automatic cleanup of old snapshots

### Memory Usage
- WAL buffer size: ~1MB per node
- Snapshot memory: Temporary spike during creation
- Recovery memory: Depends on data size

## Monitoring

### Statistics

```java
PersistentCacheStats stats = cache.getPersistentStats();
System.out.println("Cache stats: " + stats.getCacheStats());
System.out.println("WAL sequence number: " + stats.getWalSequenceNumber());
System.out.println("Data directory: " + stats.getDataDir());
System.out.println("Node ID: " + stats.getNodeId());
```

### Health Checks

```java
// Check if persistence is working
boolean isHealthy = cache.getPersistentStats().getCacheStats().getSize() > 0;
```

## Examples

### Running the Persistence Example

```bash
# Using Gradle
./gradlew runPersistenceExample

# Using Java directly
java -cp build/libs/FastCache-1.0.0-fat.jar com.fastcache.examples.PersistenceExample
```

### Docker Compose with Persistence

```yaml
version: '3.8'
services:
  fastcache-node1:
    build: .
    environment:
      - NODE_ID=node1
      - PERSISTENCE_ENABLED=true
      - DATA_DIR=/app/data
    volumes:
      - fastcache_data1:/app/data
    command: ["java", "-jar", "FastCache-1.0.0-fat.jar", "--persistence-enabled"]
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

## Best Practices

1. **Regular Snapshots**: Create snapshots every 5-10 minutes
2. **Adequate Disk Space**: Ensure sufficient space for WAL and snapshots
3. **Fast Storage**: Use SSD storage for better performance
4. **Monitoring**: Monitor WAL size and snapshot creation
5. **Backup Strategy**: Implement regular backups of persistence data

## Conclusion

The persistence implementation provides FastCache with enterprise-grade durability features while maintaining high performance. The combination of WAL and snapshots ensures data safety and fast recovery times, making FastCache suitable for production environments where data loss is not acceptable. 