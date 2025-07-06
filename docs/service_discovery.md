# FastCache Service Discovery

## Overview

The Service Discovery component in FastCache provides dynamic node registration and discovery capabilities, eliminating the need for hardcoded node lists in proxies. This enables automatic scaling, self-healing, and simplified operations.

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    SERVICE DISCOVERY LAYER                      │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │                Service Discovery API                        │ │
│  │                    (Port 8081)                             │ │
│  │                                                             │ │
│  │  • Node Registration/Deregistration                        │ │
│  │  • Health Status Management                                │ │
│  │  • Heartbeat Processing                                    │ │
│  │  • Dynamic Node Discovery                                  │ │
│  │  • REST API Endpoints                                      │ │
│  └─────────────────────────────────────────────────────────────┘ │
└─────────────────────┬───────────────────────────────────────────┘
                      │
                      │ Registration & Discovery
                      ▼
┌─────────────────────────────────────────────────────────────────┐
│                        PROXY LAYER                              │
│                                                                 │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐            │
│  │   Proxy 1   │  │   Proxy 2   │  │   Proxy 3   │            │
│  │             │  │             │  │             │            │
│  │ • Dynamic   │  │ • Dynamic   │  │ • Dynamic   │            │
│  │   Node      │  │   Node      │  │   Node      │            │
│  │   Discovery │  │   Discovery │  │   Discovery │            │
│  │ • Auto-     │  │ • Auto-     │  │ • Auto-     │            │
│  │   Scaling   │  │   Scaling   │  │   Scaling   │            │
│  └─────────────┘  └─────────────┘  └─────────────┘            │
└─────────────────────┬───────────────────────────────────────────┘
                      │
                      │ Auto-Registration
                      ▼
┌─────────────────────────────────────────────────────────────────┐
│                        CACHE LAYER                              │
│                                                                 │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐            │
│  │   Node 1    │  │   Node 2    │  │   Node 3    │            │
│  │             │  │             │  │             │            │
│  │ • Auto-     │  │ • Auto-     │  │ • Auto-     │            │
│  │   Register  │  │   Register  │  │   Register  │            │
│  │ • Heartbeat │  │ • Heartbeat │  │ • Heartbeat │            │
│  │ • Health    │  │ • Health    │  │ • Health    │            │
│  │   Updates   │  │   Updates   │  │   Updates   │            │
│  └─────────────┘  └─────────────┘  └─────────────┘            │
└─────────────────────────────────────────────────────────────────┘
```

## Components

### 1. Service Discovery API

A REST API service that provides:
- **Node Registration**: Nodes can register themselves on startup
- **Node Deregistration**: Nodes can deregister on shutdown
- **Health Management**: Update node health status
- **Heartbeat Processing**: Track node liveness
- **Dynamic Discovery**: Proxies can discover available nodes

**Port**: 8081

### 2. Service Discovery Client

A client library that provides:
- **Automatic Registration**: Register nodes on startup
- **Periodic Heartbeats**: Send heartbeats to maintain registration
- **Dynamic Discovery**: Discover and cache available nodes
- **Health Monitoring**: Track node health status

### 3. Integration with Proxies

Proxies use the Service Discovery Client to:
- **Discover Nodes**: Automatically find available cache nodes
- **Update Hash Ring**: Dynamically update consistent hash ring
- **Health-Aware Routing**: Route only to healthy nodes
- **Auto-Scaling**: Handle node additions/removals automatically

## API Endpoints

### Node Management

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/discovery/nodes` | Register a new node |
| DELETE | `/discovery/nodes/{nodeId}` | Deregister a node |
| GET | `/discovery/nodes` | Get all registered nodes |
| GET | `/discovery/nodes/{nodeId}` | Get specific node details |

### Health Management

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/discovery/nodes/{nodeId}/health` | Update node health status |
| POST | `/discovery/nodes/{nodeId}/heartbeat` | Send heartbeat for node |

### Discovery

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/discovery/nodes/healthy` | Get all healthy nodes |
| GET | `/discovery/nodes/type/{nodeType}` | Get nodes by type |
| GET | `/discovery/nodes/type/{nodeType}/cache` | Get cache nodes for proxies |

### Statistics

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/discovery/stats` | Get service discovery statistics |
| GET | `/discovery/ping` | Health check endpoint |

## Usage Examples

### 1. Register a Cache Node

```bash
curl -X POST http://localhost:8081/discovery/nodes \
  -H "Content-Type: application/json" \
  -d '{
    "nodeId": "node1",
    "host": "localhost",
    "port": 7001,
    "nodeType": "CACHE"
  }'
```

### 2. Get All Healthy Cache Nodes

```bash
curl http://localhost:8081/discovery/nodes/type/CACHE/cache
```

### 3. Send Heartbeat

```bash
curl -X POST http://localhost:8081/discovery/nodes/node1/heartbeat
```

### 4. Get Statistics

```bash
curl http://localhost:8081/discovery/stats
```

## Java Client Usage

### Registering a Node

```java
ServiceDiscoveryClient client = new ServiceDiscoveryClient("http://localhost:8081");

// Register a cache node
CompletableFuture<Boolean> registration = client.registerNode(
    "node1", "localhost", 7001, "CACHE"
);

boolean success = registration.get();
System.out.println("Registration: " + (success ? "SUCCESS" : "FAILED"));
```

### Discovering Nodes (for Proxies)

```java
ServiceDiscoveryClient client = new ServiceDiscoveryClient("http://localhost:8081");

// Start periodic discovery
client.startPeriodicRefresh();

// Get discovered nodes
List<CacheNode> nodes = client.getCachedNodes();
for (CacheNode node : nodes) {
    System.out.println("Discovered: " + node.getId() + " at " + node.getHost() + ":" + node.getPort());
}
```

### Sending Heartbeats

```java
ServiceDiscoveryClient client = new ServiceDiscoveryClient("http://localhost:8081");

// Send periodic heartbeats
ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
scheduler.scheduleAtFixedRate(() -> {
    client.heartbeat("node1");
}, 0, 30, TimeUnit.SECONDS);
```

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVICE_DISCOVERY_URL` | `http://localhost:8081` | Service discovery API URL |
| `HEARTBEAT_INTERVAL` | `30000` | Heartbeat interval in milliseconds |
| `NODE_TIMEOUT` | `60000` | Node timeout in milliseconds |

### Docker Compose

```yaml
services:
  service-discovery:
    build:
      context: ..
      dockerfile: Dockerfile
    ports:
      - "8081:8081"
    command: ["java", "-cp", "/app/FastCache-1.0.0-fat.jar", "com.fastcache.discovery.ServiceDiscoveryAPI"]
```

## Benefits

### 1. **Dynamic Scaling**
- Add/remove nodes without restarting proxies
- Automatic discovery of new nodes
- Seamless integration with container orchestration

### 2. **Self-Healing**
- Automatic detection of failed nodes
- Removal of unhealthy nodes from routing
- Re-addition of recovered nodes

### 3. **Operational Simplicity**
- No manual configuration of node lists
- Automatic node registration on startup
- Centralized node management

### 4. **High Availability**
- Multiple proxies can discover nodes independently
- No single point of failure in discovery
- Graceful handling of discovery service failures

## Deployment

### Quick Start

1. **Start the Service Discovery Cluster**:
   ```bash
   ./scripts/start-discovery-cluster.sh
   ```

2. **Verify Service Discovery**:
   ```bash
   curl http://localhost:8081/discovery/ping
   ```

3. **Check Registered Nodes**:
   ```bash
   curl http://localhost:8081/discovery/nodes
   ```

4. **Test the Cluster**:
   ```bash
   redis-cli -h localhost -p 6380 SET test "Hello Service Discovery!"
   redis-cli -h localhost -p 6380 GET test
   ```

### Stop the Cluster

```bash
./scripts/stop-discovery-cluster.sh
```

## Monitoring

### Health Checks

- **Service Discovery API**: `http://localhost:8081/discovery/ping`
- **Statistics**: `http://localhost:8081/discovery/stats`
- **Node Status**: `http://localhost:8081/discovery/nodes/healthy`

### Logs

```bash
# View service discovery logs
docker-compose -f docker/docker-compose-with-discovery.yml logs service-discovery

# View all logs
docker-compose -f docker/docker-compose-with-discovery.yml logs -f
```

## Troubleshooting

### Common Issues

1. **Service Discovery Not Available**
   - Check if the service discovery container is running
   - Verify port 8081 is accessible
   - Check container logs for errors

2. **Nodes Not Registering**
   - Verify network connectivity between nodes and service discovery
   - Check node startup logs for registration errors
   - Ensure correct service discovery URL configuration

3. **Proxies Not Discovering Nodes**
   - Verify service discovery client configuration
   - Check proxy logs for discovery errors
   - Ensure nodes are registered and healthy

### Debug Commands

```bash
# Check service discovery status
curl http://localhost:8081/discovery/ping

# List all registered nodes
curl http://localhost:8081/discovery/nodes

# Get service discovery statistics
curl http://localhost:8081/discovery/stats

# Check specific node
curl http://localhost:8081/discovery/nodes/node1
```

## Future Enhancements

1. **Persistent Storage**: Store node registrations in a database
2. **Authentication**: Add authentication for node registration
3. **Load Balancing**: Implement load balancing for service discovery
4. **Metrics**: Add Prometheus metrics for monitoring
5. **Kubernetes Integration**: Native Kubernetes service discovery 