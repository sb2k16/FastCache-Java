# FastCache

A high-performance, distributed caching system with Redis-compatible protocol, sorted sets support, and centralized health monitoring.

## Features

- **Redis-Compatible Protocol**: Full support for Redis commands (SET, GET, DEL, PING, etc.)
- **Sorted Sets**: Skip list-based implementation with ZADD, ZREM, ZSCORE, ZRANK operations
- **Distributed Architecture**: Consistent hashing with virtual nodes for load balancing
- **Multi-Proxy Support**: 3 proxy instances with HAProxy load balancing
- **Centralized Health Monitoring**: Single health checker service monitoring all cache nodes
- **Health-Aware Routing**: Proxies prevent requests to unhealthy nodes
- **Docker Support**: Complete containerized deployment
- **High Availability**: Automatic failover and health checks

## Architecture

### Multi-Layer Architecture with Centralized Health Checking

```
┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                    CLIENT LAYER                                                 │
│                                                                                                 │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐          │
│  │   Client 1  │  │   Client 2  │  │   Client 3  │  │   Client 4  │  │   Client N  │          │
│  │             │  │             │  │             │  │             │  │             │          │
│  │ SET key1    │  │ GET key2    │  │ ZADD set1   │  │ DEL key3    │  │ PING        │          │
│  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘          │
└─────────────────────┬───────────────────────────────────────────────────────────────────────────┘
                      │
                      │ Client Requests (Redis Protocol)
                      ▼
┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                LOAD BALANCER LAYER                                              │
│                                                                                                 │
│  ┌─────────────────────────────────────────────────────────────────────────────────────────────┐ │
│  │                              HAProxy Load Balancer                                          │ │
│  │                                    (Port 6380)                                              │ │
│  │                                                                                             │ │
│  │  • TCP-based load balancing                                                                │ │
│  │  • Health checks on proxies                                                                │ │
│  │  • Round-robin distribution                                                                │ │
│  │  • Automatic failover                                                                       │ │
│  │  • Stats dashboard (Port 8404)                                                             │ │
│  └─────────────────────────────────────────────────────────────────────────────────────────────┘ │
└─────────────────────┬───────────────────────────────────────────────────────────────────────────┘
                      │
                      │ Route to Healthy Proxies
                      ▼
┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                  PROXY LAYER                                                    │
│                                                                                                 │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐                                            │
│  │   Proxy 1   │  │   Proxy 2   │  │   Proxy 3   │                                            │
│  │  (Port 6382)│  │  (Port 6383)│  │  (Port 6384)│                                            │
│  │             │  │             │  │             │                                            │
│  │ • Route     │  │ • Route     │  │ • Route     │                                            │
│  │ • Query     │  │ • Query     │  │ • Query     │                                            │
│  │   Health    │  │   Health    │  │   Health    │                                            │
│  │   Service   │  │   Service   │  │   Service   │                                            │
│  │ • Consistent│  │ • Consistent│  │ • Consistent│                                            │
│  │   Hashing   │  │   Hashing   │  │   Hashing   │                                            │
│  └─────────────┘  └─────────────┘  └─────────────┘                                            │
└─────────────────────┬───────────────────────────────────────────────────────────────────────────┘
                      │
                      │ Health Status Queries
                      ▼
┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
│                              HEALTH MONITORING LAYER                                            │
│                                                                                                 │
│  ┌─────────────────────────────────────────────────────────────────────────────────────────────┐ │
│  │                        Centralized Health Checker Service                                   │ │
│  │                                (Port 8080)                                                  │ │
│  │                                                                                             │ │
│  │  • Monitors all 3 cache nodes                                                              │ │
│  │  • Maintains HealthRegistry                                                                │ │
│  │  • Provides REST API                                                                        │ │
│  │  • Single health check per node                                                             │ │
│  │  • 30-second check intervals                                                                │ │
│  │  • Health-aware routing support                                                             │ │
│  └─────────────────────────────────────────────────────────────────────────────────────────────┘ │
└─────────────────────┬───────────────────────────────────────────────────────────────────────────┘
                      │
                      │ Health Checks (TCP)
                      ▼
┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                  CACHE LAYER                                                    │
│                                                                                                 │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐                                            │
│  │   Node 1    │  │   Node 2    │  │   Node 3    │                                            │
│  │  (Port 7001)│  │  (Port 7002)│  │  (Port 7003)│                                            │
│  │             │  │             │  │             │                                            │
│  │ • Cache     │  │ • Cache     │  │ • Cache     │                                            │
│  │ • Store     │  │ • Store     │  │ • Store     │                                            │
│  │ • Retrieve  │  │ • Retrieve  │  │ • Retrieve  │                                            │
│  │ • Sorted    │  │ • Sorted    │  │ • Sorted    │                                            │
│  │   Sets      │  │   Sets      │  │   Sets      │                                            │
│  │ • Persistence│  │ • Persistence│  │ • Persistence│                                          │
│  │ • WAL       │  │ • WAL       │  │ • WAL       │                                            │
│  └─────────────┘  └─────────────┘  └─────────────┘                                            │
└─────────────────────────────────────────────────────────────────────────────────────────────────┘
```

### Key Benefits of Centralized Health Checking

- **Reduced Health Check Traffic**: Single health checker instead of multiple proxies checking all nodes
- **Better Resource Utilization**: Nodes receive health checks from only one source
- **Consistent Health Information**: All proxies see the same health status
- **Scalable Architecture**: Adding more proxies doesn't increase health check load
- **Health-Aware Routing**: Proxies prevent requests to unhealthy nodes
- **Simplified Monitoring**: Centralized health status through REST API

## Quick Start

### Prerequisites

- Docker and Docker Compose
- At least 4GB RAM for the 3-node cluster
- Java 11+ (for development)

### Starting the 3-Node Cluster (Recommended)

This setup is ideal for development, testing, and small to medium workloads. It launches 3 data nodes, 3 proxy nodes, a load balancer (HAProxy), and a centralized health checker.

```bash
# Start the 3-node cluster with centralized health monitoring
./scripts/start-cluster.sh

# Monitor cluster health
./scripts/monitor-cluster.sh

# Test cluster connectivity
./scripts/test-cluster.sh
```

### Access Points (3-Node Setup)

| Component         | Port   | Purpose                       |
|-------------------|--------|-------------------------------|
| Load Balancer     | 6380   | Main entry point for clients  |
| HAProxy Stats     | 8404   | Monitoring dashboard          |
| Health Service    | 8080   | Health checker API            |
| Proxy 1           | 6382   | Direct proxy access           |
| Proxy 2           | 6383   | Direct proxy access           |
| Proxy 3           | 6384   | Direct proxy access           |
| Node 1            | 7001   | Direct node access            |
| Node 2            | 7002   | Direct node access            |
| Node 3            | 7003   | Direct node access            |

### Using the Cluster

```bash
# Connect through load balancer (recommended)
redis-cli -h localhost -p 6380

# Basic operations
SET mykey myvalue
GET mykey
DEL mykey

# Sorted set operations
ZADD myset 1.0 member1
ZADD myset 2.0 member2
ZSCORE myset member1
ZRANK myset member2
ZRANGE myset 0 -1

# Health check
PING
```

## Large Cluster (50 Nodes + 5 Proxies)

For production workloads requiring high capacity and redundancy, you can deploy the full 50-node cluster.

### Starting the Large Cluster

```bash
# Start the complete cluster with centralized health monitoring
./scripts/start-large-cluster.sh

# Monitor cluster health
./scripts/health-monitor.sh all

# Test cluster connectivity
./scripts/test-cluster.sh
```

### Access Points (Large Cluster)

| Component | Port | Purpose |
|-----------|------|---------|
| Load Balancer | 6380 | Main entry point for clients |
| HAProxy Stats | 8404 | Monitoring dashboard |
| Health Service | 8080 | Health checker API |
| Proxy 1-5 | 6382-6386 | Direct proxy access |
| Node 1-50 | 7001-7050 | Direct node access |

## Health Monitoring

### Centralized Health Checker Service

The health checker service runs on port 8080 and provides REST API endpoints:

```bash
# Check health service status
curl http://localhost:8080/health/ping

# Get cluster health summary
curl http://localhost:8080/health/summary

# Get all healthy nodes
curl http://localhost:8080/health/healthy

# Get all unhealthy nodes
curl http://localhost:8080/health/unhealthy

# Get specific node health
curl http://localhost:8080/health/nodes/node1
```

### Health Monitoring Scripts

```bash
# Comprehensive health check
./scripts/health-monitor.sh all

# Specific health checks
./scripts/health-monitor.sh summary
./scripts/health-monitor.sh load-balancer
./scripts/health-monitor.sh proxies
./scripts/health-monitor.sh nodes

# View logs
./scripts/health-monitor.sh logs health-checker
./scripts/health-monitor.sh logs all
```

## Architecture Components

### 1. Centralized Health Checker Service
- **Purpose**: Monitor all cache nodes (3 nodes in default setup)
- **Port**: 8080 (REST API)
- **Health Checks**: 3 nodes, every 30 seconds (configurable)
- **Features**: Single health check per node, REST API, health registry

### 2. Health Service Client
- **Purpose**: Client for proxies to query health service
- **Features**: HTTP client, caching, error handling, async operations

### 3. Load Balancer (HAProxy)
- **Purpose**: Distribute requests across healthy proxies
- **Port**: 6380 (main), 8404 (stats)
- **Features**: TCP load balancing, health checks, automatic failover

### 4. FastCache Proxies
- **Purpose**: Route requests to appropriate cache nodes
- **Ports**: 6382-6384 (3 proxies in default setup)
- **Features**: Consistent hashing, health-aware routing, protocol handling

### 5. Cache Nodes
- **Purpose**: Store and retrieve data
- **Ports**: 7001-7003 (3 nodes in default setup)
- **Features**: Key-value storage, sorted sets, TTL, eviction policies, persistence

## Development

### Building from Source

```bash
# Build the project
./gradlew build

# Run tests
./gradlew test

# Create fat JAR
./gradlew shadowJar
```

### Running Individual Components

```bash
# Start health checker service
java -cp build/libs/FastCache-1.0.0-fat.jar com.fastcache.health.CentralizedHealthCheckerService

# Start proxy with health service
java -cp build/libs/FastCache-1.0.0-fat.jar com.fastcache.proxy.FastCacheProxy \
  --host 0.0.0.0 --port 6379 --proxy-id proxy1 \
  --health-service http://localhost:8080 \
  --cluster-nodes localhost:7001,localhost:7002,localhost:7003

# Start cache node
java -jar build/libs/FastCache-1.0.0-fat.jar \
  --host 0.0.0.0 --port 6379 --node-id node1 \
  --cluster-mode --cluster-nodes localhost:7001,localhost:7002,localhost:7003
```

## Configuration

### Environment Variables

```bash
# Health checker service
HEALTH_CHECK_INTERVAL=30s
HEALTH_CHECK_TIMEOUT=5s
NODE_COUNT=50

# Proxy configuration
HEALTH_SERVICE_URL=http://health-checker:8080
PROXY_ID=proxy1
CLUSTER_NODES=fastcache-node1:6379,fastcache-node2:6379,...

# Node configuration
NODE_ID=node1
CLUSTER_MODE=true
```

### HAProxy Configuration

The load balancer is configured with:
- TCP-based load balancing
- Health checks on proxies
- Round-robin distribution
- Automatic failover

## Monitoring and Troubleshooting

### Health Check Traffic Analysis

**Before (Distributed Health Checking)**:
```
Health Check Traffic = 3 nodes × 3 proxies × 1 check/30s = 9 checks/30s
```

**After (Centralized Health Checking)**:
```
Health Check Traffic = 3 nodes × 1 health service × 1 check/30s = 3 checks/30s
```

**Improvement**: 67% reduction in health check traffic

**For Large Clusters (50 nodes, 5 proxies)**:
```
Before: 50 nodes × 5 proxies × 1 check/30s = 250 checks/30s
After:  50 nodes × 1 health service × 1 check/30s = 50 checks/30s
Improvement: 80% reduction in health check traffic
```

### Common Issues

1. **Health Service Unavailable**
   ```bash
   # Check if health service is running
   curl http://localhost:8080/health/ping
   
   # View health service logs
   ./scripts/health-monitor.sh logs health-checker
   ```

2. **Proxy Health Issues**
   ```bash
   # Check proxy health directly
   ./scripts/health-monitor.sh proxies
   
   # View proxy logs
   ./scripts/health-monitor.sh logs proxies
   ```

3. **Node Health Issues**
   ```bash
   # Check node health through health service
   ./scripts/health-monitor.sh nodes
   
   # Check sample nodes directly
   ./scripts/health-monitor.sh sample-nodes
   ```

## Performance Considerations

### Resource Requirements

- **Memory**: 4GB+ for 3-node cluster, 8GB+ for full cluster
- **CPU**: 2+ cores for 3-node cluster, 4+ cores for full cluster
- **Network**: 1Gbps+ for high throughput
- **Storage**: 5GB+ for 3-node cluster, 10GB+ for full cluster

### Scaling Considerations

- **Horizontal Scaling**: Add more nodes/proxies
- **Vertical Scaling**: Increase container resources
- **Load Distribution**: Consistent hashing ensures even distribution

## Security Considerations

### Network Security

- **Firewall**: Restrict access to necessary ports
- **SSL/TLS**: Encrypt client connections (future enhancement)
- **Authentication**: Implement access controls (future enhancement)

### Container Security

- **Non-root**: Run containers as non-root user
- **Secrets**: Use Docker secrets for sensitive data
- **Updates**: Regular security updates

## Future Enhancements

### Planned Features

1. **Service Discovery**: Dynamic service registration with Consul/etcd
2. **Advanced Monitoring**: Prometheus metrics and Grafana dashboards
3. **Auto-scaling**: Kubernetes-based horizontal pod autoscaler
4. **SSL/TLS Support**: Encrypted client connections
5. **Authentication**: Access control and user management

### Performance Optimizations

1. **Connection Pooling**: Optimize proxy-to-node connections
2. **Caching**: Implement proxy-level caching
3. **Compression**: Data compression for network efficiency
4. **Persistence**: Disk-based persistence for cache data

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests
5. Submit a pull request

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Support

For issues and questions:
1. Check the troubleshooting section
2. Review the logs using `./scripts/health-monitor.sh logs`
3. Open an issue on GitHub

---

**FastCache**: High-performance distributed caching with centralized health monitoring and Redis compatibility.

# FastCache - Quick Start

## Standalone In-Memory or Persistent Cache

```java
import com.fastcache.core.FastCache;
import com.fastcache.core.CacheEntry;

// In-memory cache
FastCache cache = FastCache.builder()
    .maxSize(10000)
    .evictionPolicy(new EvictionPolicy.LRU())
    .build();

// Persistent cache
FastCache persistentCache = FastCache.builder()
    .enablePersistence(true)
    .dataDir("/app/data")
    .maxSize(10000)
    .evictionPolicy(new EvictionPolicy.LRU())
    .build();

cache.set("user:1", "Alice", CacheEntry.EntryType.STRING);
Object value = cache.get("user:1");
```

## Distributed Cluster with Persistence

```java
import com.fastcache.cluster.DistributedCacheManager;
import com.fastcache.cluster.CacheNode;

DistributedCacheManager cluster = DistributedCacheManager.builder()
    .virtualNodes(150)
    .replicationFactor(2)
    .enableReplication(true)
    .enablePersistence(true)
    .dataDir("/app/data/cluster")
    .maxSize(10000)
    .evictionPolicy(new EvictionPolicy.LRU())
    .build();

// Add nodes (each node will use PersistentCacheEngine if enabled)
cluster.addNode(new CacheNode("node1", "localhost", 6379), "node1");
cluster.addNode(new CacheNode("node2", "localhost", 6380), "node2");

// Use the cluster as usual
cluster.set("key", "value", CacheEntry.EntryType.STRING);
```

## Notes
- Use `enablePersistence(true)` and `dataDir(...)` to enable and configure persistence.
- Each node in the cluster will store its data in a subdirectory of `dataDir` named after the node ID.
- The builder pattern is available for both standalone and distributed cache modes. 