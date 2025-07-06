# Multi-Proxy Architecture Plan for FastCache

## Overview

This document outlines the plan for implementing multiple proxy instances with centralized health checking for a large-scale FastCache cluster (50 nodes, 5 proxies).

## Architecture Components

### 1. Load Balancer Layer
- **HAProxy** as the primary load balancer
- **Port**: 6380 (main entry point)
- **Stats**: http://localhost:8404/stats
- **Health Checks**: TCP-based with PING/PONG protocol
- **Load Balancing**: Round-robin across healthy proxies

### 2. Proxy Layer (5 Instances)
- **Proxy 1**: localhost:6382
- **Proxy 2**: localhost:6383
- **Proxy 3**: localhost:6384
- **Proxy 4**: localhost:6385
- **Proxy 5**: localhost:6386

Each proxy:
- Routes requests using consistent hashing
- Queries centralized health service for node status
- Makes health-aware routing decisions
- Handles failover scenarios

### 3. Health Monitoring Layer
- **Centralized Health Checker Service**: localhost:8080
- **Purpose**: Monitor all 50 cache nodes
- **Health Checks**: Single check per node (50 total)
- **API**: REST endpoints for health status
- **Frequency**: Every 30 seconds

### 4. Node Layer (50 Instances)
- **Nodes 1-50**: localhost:7001-7050
- Each node runs FastCache server
- Participates in consistent hashing ring
- Receives health checks from centralized service

## Centralized Health Check Architecture

### Complete Architecture Diagram

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
│  └─────────────────────────────────────────────────────────────────────────────────────────────┘ │
└─────────────────────┬───────────────────────────────────────────────────────────────────────────┘
                      │
                      │ Route to Healthy Proxies
                      ▼
┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                  PROXY LAYER                                                    │
│                                                                                                 │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐          │
│  │   Proxy 1   │  │   Proxy 2   │  │   Proxy 3   │  │   Proxy 4   │  │   Proxy 5   │          │
│  │  (Port 6382)│  │  (Port 6383)│  │  (Port 6384)│  │  (Port 6385)│  │  (Port 6386)│          │
│  │             │  │             │  │             │  │             │  │             │          │
│  │ • Route     │  │ • Route     │  │ • Route     │  │ • Route     │  │ • Route     │          │
│  │ • Query     │  │ • Query     │  │ • Query     │  │ • Query     │  │ • Query     │          │
│  │   Health    │  │   Health    │  │   Health    │  │   Health    │  │   Health    │          │
│  │   Service   │  │   Service   │  │   Service   │  │   Service   │  │   Service   │          │
│  │ • Consistent│  │ • Consistent│  │ • Consistent│  │ • Consistent│  │ • Consistent│          │
│  │   Hashing   │  │   Hashing   │  │   Hashing   │  │   Hashing   │  │   Hashing   │          │
│  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘          │
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
│  │  • Monitors all 50 cache nodes                                                             │ │
│  │  • Maintains HealthRegistry                                                                │ │
│  │  • Provides REST API                                                                        │ │
│  │  • Single health check per node                                                             │ │
│  │  • 30-second check intervals                                                                │ │
│  └─────────────────────────────────────────────────────────────────────────────────────────────┘ │
└─────────────────────┬───────────────────────────────────────────────────────────────────────────┘
                      │
                      │ Health Checks (TCP)
                      ▼
┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                  CACHE LAYER                                                    │
│                                                                                                 │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐          │
│  │   Node 1    │  │   Node 2    │  │   Node 3    │  │   Node 4    │  │   Node 5    │          │
│  │  (Port 7001)│  │  (Port 7002)│  │  (Port 7003)│  │  (Port 7004)│  │  (Port 7005)│          │
│  │             │  │             │  │             │  │             │  │             │          │
│  │ • Cache     │  │ • Cache     │  │ • Cache     │  │ • Cache     │  │ • Cache     │          │
│  │ • Store     │  │ • Store     │  │ • Store     │  │ • Store     │  │ • Store     │          │
│  │ • Retrieve  │  │ • Retrieve  │  │ • Retrieve  │  │ • Retrieve  │  │ • Retrieve  │          │
│  │ • Sorted    │  │ • Sorted    │  │ • Sorted    │  │ • Sorted    │  │ • Sorted    │          │
│  │   Sets      │  │   Sets      │  │   Sets      │  │   Sets      │  │   Sets      │          │
│  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘          │
│                                                                                                 │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐          │
│  │   Node 6    │  │   Node 7    │  │   Node 8    │  │   Node 9    │  │  Node 10    │          │
│  │  (Port 7006)│  │  (Port 7007)│  │  (Port 7008)│  │  (Port 7009)│  │  (Port 7010)│          │
│  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘          │
│                                                                                                 │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐          │
│  │  Node 46    │  │  Node 47    │  │  Node 48    │  │  Node 49    │  │  Node 50    │          │
│  │  (Port 7046)│  │  (Port 7047)│  │  (Port 7048)│  │  (Port 7049)│  │  (Port 7050)│          │
│  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘          │
└─────────────────────────────────────────────────────────────────────────────────────────────────┘
```

### Data Flow Architecture

#### Request Flow (Client → Cache)
```
Client Request
      │
      ▼
HAProxy Load Balancer
      │ (Route to healthy proxy)
      ▼
FastCache Proxy
      │ (Query health service)
      ▼
Health Checker Service
      │ (Return health status)
      ▼
FastCache Proxy
      │ (Route to healthy node)
      ▼
Cache Node
      │ (Process request)
      ▼
Response to Client
```

#### Health Monitoring Flow
```
Health Checker Service
      │ (Every 30 seconds)
      ▼
TCP Health Check
      │ (To each node)
      ▼
Cache Node
      │ (Health response)
      ▼
Health Checker Service
      │ (Update registry)
      ▼
Health Registry
      │ (Available to proxies)
      ▼
FastCache Proxy
      │ (Query when needed)
      ▼
Routing Decision
```

## Health Check Components

### 1. CentralizedHealthCheckerService
- **Purpose**: Monitor all cache nodes and provide health status
- **Port**: 8080 (REST API)
- **Health Checks**: 50 nodes, every 30 seconds
- **Location**: `src/main/java/com/fastcache/health/CentralizedHealthCheckerService.java`

### 2. HealthServiceClient
- **Purpose**: Client for proxies to query health service
- **Features**: HTTP client, caching, error handling
- **Location**: `src/main/java/com/fastcache/proxy/HealthServiceClient.java`

### 3. HealthCheck Class
- **Status**: HEALTHY, UNHEALTHY, UNKNOWN
- **Metrics**: Response time, timestamp, error messages
- **Node Type**: "node" or "proxy"
- **Location**: `src/main/java/com/fastcache/core/HealthCheck.java`

### 4. HealthChecker Class
- **Scheduled Checks**: Every 30 seconds by default
- **Timeout**: 5 seconds per check
- **Async Operations**: Non-blocking health checks
- **Location**: `src/main/java/com/fastcache/core/HealthChecker.java`

### 5. HealthRegistry Class
- **Central Registry**: Stores health status of all components
- **Filtering**: Get healthy/unhealthy nodes and proxies
- **Summary**: Cluster health overview
- **Location**: `src/main/java/com/fastcache/core/HealthRegistry.java`

## Implementation Details

### Health Check Protocol

#### Node Health Check
```bash
# TCP connection test
nc -z localhost 7001

# Redis-like PING/PONG test
echo -e "PING\r\n" | nc localhost 7001
# Expected: +PONG
```

#### Proxy Health Check
```bash
# TCP connection test
nc -z localhost 6382

# Redis-like PING/PONG test
echo -e "PING\r\n" | nc localhost 6382
# Expected: +PONG
```

#### Load Balancer Health Check
```bash
# TCP connection test
nc -z localhost 6380

# HTTP stats page test
curl http://localhost:8404/stats
```

### Health Service API Endpoints

#### REST API
```http
GET /health/nodes                    # Get all node health status
GET /health/nodes/{nodeId}           # Get specific node health
GET /health/summary                  # Get cluster health summary
GET /health/healthy                  # Get only healthy nodes
GET /health/unhealthy                # Get only unhealthy nodes
```

#### Example Responses
```json
{
  "nodeId": "node1",
  "nodeType": "node",
  "host": "localhost",
  "port": 7001,
  "status": "HEALTHY",
  "lastCheck": "2024-01-15T10:30:00Z",
  "responseTimeMs": 45,
  "errorMessage": null
}
```

### Health Check Configuration

#### HAProxy Configuration
```haproxy
backend fastcache_backend
    mode tcp
    balance roundrobin
    option tcp-check
    tcp-check connect
    tcp-check send-binary PING\r\n
    tcp-check expect string +PONG
    
    server proxy1 fastcache-proxy1:6379 check inter 30s rise 2 fall 3
    server proxy2 fastcache-proxy2:6379 check inter 30s rise 2 fall 3
    server proxy3 fastcache-proxy3:6379 check inter 30s rise 2 fall 3
    server proxy4 fastcache-proxy4:6379 check inter 30s rise 2 fall 3
    server proxy5 fastcache-proxy5:6379 check inter 30s rise 2 fall 3
```

#### Health Check Intervals
- **Node Checks**: Every 30 seconds (centralized)
- **Proxy Checks**: Every 30 seconds (HAProxy)
- **Load Balancer Checks**: Every 30 seconds
- **Timeout**: 5 seconds per check
- **Failure Threshold**: 3 consecutive failures
- **Recovery Threshold**: 2 consecutive successes

## Deployment Architecture

### Docker Compose Structure

```yaml
services:
  # Load Balancer
  load-balancer:
    image: haproxy:2.8
    ports: ["6380:6380", "8404:8404"]
    
  # Centralized Health Checker Service
  health-checker:
    build: .
    ports: ["8080:8080"]
    environment:
      - HEALTH_CHECK_INTERVAL=30s
      - HEALTH_CHECK_TIMEOUT=5s
    
  # Proxy Instances
  fastcache-proxy1:
    ports: ["6382:6379"]
    environment:
      - HEALTH_SERVICE_URL=http://health-checker:8080
  fastcache-proxy2:
    ports: ["6383:6379"]
    environment:
      - HEALTH_SERVICE_URL=http://health-checker:8080
  fastcache-proxy3:
    ports: ["6384:6379"]
    environment:
      - HEALTH_SERVICE_URL=http://health-checker:8080
  fastcache-proxy4:
    ports: ["6385:6379"]
    environment:
      - HEALTH_SERVICE_URL=http://health-checker:8080
  fastcache-proxy5:
    ports: ["6386:6379"]
    environment:
      - HEALTH_SERVICE_URL=http://health-checker:8080
    
  # Node Instances (50 total)
  fastcache-node1:
    ports: ["7001:6379"]
  # ... nodes 2-49
  fastcache-node50:
    ports: ["7050:6379"]
```

### Network Topology

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        FastCache Multi-Proxy Cluster                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐        │
│  │   Proxy 1   │  │   Proxy 2   │  │   Proxy 3   │  │   Proxy 4   │        │
│  │ localhost   │  │ localhost   │  │ localhost   │  │ localhost   │        │
│  │    :6382    │  │    :6383    │  │    :6384    │  │    :6385    │        │
│  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘        │
│         │                 │                 │                 │            │
│         └─────────────────┼─────────────────┼─────────────────┘            │
│                           │                 │                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                    Load Balancer (HAProxy)                         │   │
│  │                        localhost:6380                              │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                Centralized Health Checker Service                   │   │
│  │                        localhost:8080                              │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐        │
│  │   Node 1    │  │   Node 2    │  │   Node 3    │  │   Node 4    │        │
│  │ localhost   │  │ localhost   │  │ localhost   │  │ localhost   │        │
│  │    :7001    │  │    :7002    │  │    :7003    │  │    :7004    │        │
│  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘        │
│         │                 │                 │                 │            │
│         └─────────────────┼─────────────────┼─────────────────┘            │
│                           │                 │                              │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐        │
│  │   Node 47   │  │   Node 48   │  │   Node 49   │  │   Node 50   │        │
│  │ localhost   │  │ localhost   │  │ localhost   │  │ localhost   │        │
│  │   :7047     │  │   :7048     │  │   :7049     │  │   :7050     │        │
│  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘        │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Health Check Traffic Analysis

### Before (Distributed Health Checking)
```
Health Check Traffic = 50 nodes × 5 proxies × 1 check/30s = 250 checks/30s
```

### After (Centralized Health Checking)
```
Health Check Traffic = 50 nodes × 1 health service × 1 check/30s = 50 checks/30s
```

### Improvement
- **80% reduction** in health check traffic
- **Single health checker** instead of 5
- **Better resource utilization** on nodes
- **Reduced network overhead**

## Benefits of Centralized Architecture

### 1. **Efficiency**
- Single health check per node (50 total)
- Reduced network traffic and resource usage
- Better performance and scalability

### 2. **Consistency**
- Single source of truth for health status
- All proxies see the same health information
- No discrepancies between proxy health views

### 3. **Scalability**
- Adding more proxies doesn't increase health check load
- Health checking scales independently of proxy count
- Better performance as cluster grows

### 4. **Fault Isolation**
- Health checker failure doesn't affect proxy routing
- Proxy failure doesn't affect health monitoring
- Each service can be restarted independently

### 5. **Observability**
- Centralized health information
- Easy to build dashboards and alerts
- Clear health status visibility

## Layer Responsibilities

### 1. **Client Layer**
- Application clients making cache requests
- Redis-compatible protocol
- Commands: SET, GET, ZADD, DEL, PING

### 2. **Load Balancer Layer**
- Distribute requests across healthy proxies
- HAProxy with TCP load balancing
- Health checks and automatic failover

### 3. **Proxy Layer**
- Route requests to appropriate cache nodes
- Query health service for node status
- Consistent hashing with health awareness
- Protocol handling and connection management

### 4. **Health Monitoring Layer**
- Monitor health of all cache nodes
- Centralized health checker service
- REST API for health status
- Health registry maintenance

### 5. **Cache Layer**
- Store and retrieve data
- 50 cache nodes with key-value storage
- Sorted sets, TTL, eviction policies

## Network Ports

| Component | Port | Purpose |
|-----------|------|---------|
| HAProxy | 6380 | Client entry point |
| HAProxy Stats | 8404 | Monitoring dashboard |
| Health Service | 8080 | Health API |
| Proxy 1 | 6382 | Request routing |
| Proxy 2 | 6383 | Request routing |
| Proxy 3 | 6384 | Request routing |
| Proxy 4 | 6385 | Request routing |
| Proxy 5 | 6386 | Request routing |
| Node 1-50 | 7001-7050 | Cache storage |

## Implementation Steps

### Phase 1: Centralized Health Service
1. Create `CentralizedHealthCheckerService`
2. Implement REST API endpoints
3. Add health monitoring for all nodes
4. Create `HealthServiceClient` for proxies

### Phase 2: Proxy Integration
1. Modify `FastCacheProxy` to use health service
2. Implement health-aware routing
3. Add failover logic
4. Update proxy configuration

### Phase 3: Deployment
1. Update Docker Compose configuration
2. Add health service container
3. Configure proxy health service URLs
4. Test end-to-end functionality

### Phase 4: Monitoring
1. Add health service monitoring
2. Create health dashboards
3. Implement alerting
4. Performance optimization

## Conclusion

This centralized health checking architecture provides:

- ✅ **Efficient resource usage** (80% reduction in health checks)
- ✅ **Better scalability** (independent of proxy count)
- ✅ **Improved reliability** (single source of truth)
- ✅ **Enhanced observability** (centralized monitoring)
- ✅ **Health-aware routing** (prevent requests to unhealthy nodes)

The architecture maintains the benefits of health-aware routing while eliminating the network overhead of redundant health checking. 