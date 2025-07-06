# FastCache Docker Cluster Setup

This document describes how to set up and run a FastCache cluster using Docker and Docker Compose.

## Overview

The FastCache cluster consists of:
- **3 FastCache nodes** (Node1, Node2, Node3) for data storage and distribution
- **1 FastCache proxy** for load balancing and request routing
- **Docker network** for inter-node communication
- **Persistent volumes** for data and log storage

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    FastCache Cluster                        │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐     │
│  │   Node 1    │    │   Node 2    │    │   Node 3    │     │
│  │ localhost   │    │ localhost   │    │ localhost   │     │
│  │    :6379    │    │    :6380    │    │    :6381    │     │
│  └─────────────┘    └─────────────┘    └─────────────┘     │
│         │                   │                   │          │
│         └───────────────────┼───────────────────┘          │
│                             │                              │
│                    ┌─────────┴─────────┐                   │
│                    │      Proxy        │                   │
│                    │   localhost:6382  │                   │
│                    └───────────────────┘                   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

## Prerequisites

- Docker (version 20.10 or later)
- Docker Compose (version 2.0 or later)
- At least 4GB of available RAM
- At least 2GB of available disk space

## Quick Start

### 1. Start the Cluster

```bash
# Start the entire cluster
./scripts/start-cluster.sh

# Or use docker-compose directly
docker-compose up -d
```

### 2. Check Cluster Status

```bash
# Check if all services are running
./scripts/monitor-cluster.sh health

# Or use docker-compose
docker-compose ps
```

### 3. Test the Cluster

```bash
# Run basic connectivity tests
./scripts/test-cluster.sh connectivity

# Run all tests
./scripts/test-cluster.sh all
```

### 4. Stop the Cluster

```bash
# Stop all services
./scripts/stop-cluster.sh

# Or use docker-compose
docker-compose down
```

## Service Endpoints

| Service | Host | Port | Description |
|---------|------|------|-------------|
| Node 1 | localhost | 6379 | Primary cache node |
| Node 2 | localhost | 6380 | Secondary cache node |
| Node 3 | localhost | 6381 | Tertiary cache node |
| Proxy | localhost | 6382 | Load balancer and router |

## Scripts Reference

### Cluster Management Scripts

#### `scripts/start-cluster.sh`
Main script to start the FastCache cluster.

```bash
./scripts/start-cluster.sh [COMMAND]
```

Commands:
- `start` (default) - Start the cluster
- `stop` - Stop the cluster
- `restart` - Restart the cluster
- `status` - Show cluster status
- `logs` - Show recent logs
- `test` - Test cluster connectivity
- `clean` - Stop and remove all containers, networks, and volumes
- `help` - Show help message

#### `scripts/stop-cluster.sh`
Simple script to stop the cluster.

```bash
./scripts/stop-cluster.sh
```

### Monitoring Scripts

#### `scripts/monitor-cluster.sh`
Monitor cluster health and performance.

```bash
./scripts/monitor-cluster.sh [COMMAND]
```

Commands:
- `health` (default) - Check cluster health and connectivity
- `resources` - Show resource usage
- `logs` - Show recent logs from all services
- `logs-node <node>` - Show logs from a specific node
- `test` - Test basic operations
- `topology` - Show cluster topology
- `all` - Run all monitoring checks
- `help` - Show help message

### Testing Scripts

#### `scripts/test-cluster.sh`
Test cluster functionality and performance.

```bash
./scripts/test-cluster.sh [COMMAND]
```

Commands:
- `connectivity` - Test cluster connectivity
- `basic` - Test basic operations (SET/GET)
- `sorted-set` - Test sorted set operations
- `load-balance` - Test load balancing through proxy
- `failover` - Show failover testing instructions
- `performance` - Run basic performance test
- `all` (default) - Run all tests
- `help` - Show help message

## Docker Compose Configuration

### Services

#### FastCache Nodes
Each node runs the FastCache server with:
- Unique node ID and hostname
- Cluster mode enabled
- Consistent hashing for data distribution
- Health checks for monitoring

#### FastCache Proxy
The proxy provides:
- Load balancing across all nodes
- Request routing using consistent hashing
- Failover handling
- Connection pooling

### Volumes

- `fastcache_data1/2/3` - Persistent data storage for each node
- `fastcache_logs1/2/3` - Log storage for each node
- `fastcache_proxy_logs` - Log storage for the proxy

### Network

- `fastcache_network` - Bridge network for inter-node communication
- Subnet: `172.20.0.0/16`

## Environment Variables

### Node Configuration
- `NODE_ID` - Unique identifier for the node
- `NODE_HOST` - Hostname of the node
- `NODE_PORT` - Port the node listens on
- `CLUSTER_MODE` - Enable cluster mode
- `CLUSTER_NODES` - Comma-separated list of cluster nodes

### Proxy Configuration
- `PROXY_MODE` - Enable proxy mode
- `CLUSTER_NODES` - Comma-separated list of cluster nodes

## Usage Examples

### Basic Operations

1. **Start the cluster:**
   ```bash
   ./scripts/start-cluster.sh
   ```

2. **Check cluster health:**
   ```bash
   ./scripts/monitor-cluster.sh health
   ```

3. **View logs:**
   ```bash
   ./scripts/monitor-cluster.sh logs
   ```

4. **Test connectivity:**
   ```bash
   ./scripts/test-cluster.sh connectivity
   ```

### Advanced Operations

1. **Monitor resource usage:**
   ```bash
   ./scripts/monitor-cluster.sh resources
   ```

2. **View specific node logs:**
   ```bash
   ./scripts/monitor-cluster.sh logs-node fastcache-node1
   ```

3. **Test performance:**
   ```bash
   ./scripts/test-cluster.sh performance
   ```

4. **Restart the cluster:**
   ```bash
   ./scripts/start-cluster.sh restart
   ```

### Manual Docker Operations

1. **Build the image:**
   ```bash
   docker-compose build
   ```

2. **Start specific services:**
   ```bash
   docker-compose up -d fastcache-node1 fastcache-node2
   ```

3. **View service logs:**
   ```bash
   docker-compose logs -f fastcache-node1
   ```

4. **Execute commands in containers:**
   ```bash
   docker-compose exec fastcache-node1 java -jar build/libs/FastCache-1.0.0-fat.jar --help
   ```

## Troubleshooting

### Common Issues

1. **Port conflicts:**
   - Ensure ports 6379, 6380, 6381, and 6382 are not in use
   - Modify the port mappings in `docker-compose.yml` if needed

2. **Insufficient resources:**
   - Increase Docker memory limit (recommended: 4GB+)
   - Check available disk space

3. **Network issues:**
   - Ensure Docker network is not conflicting
   - Check firewall settings

4. **Container startup failures:**
   - Check logs: `docker-compose logs <service-name>`
   - Verify Java heap settings in Dockerfile

### Debugging Commands

```bash
# Check container status
docker-compose ps

# View all logs
docker-compose logs

# View specific service logs
docker-compose logs fastcache-node1

# Check network connectivity
docker network ls
docker network inspect fastcache_fastcache_network

# Check resource usage
docker stats

# Execute shell in container
docker-compose exec fastcache-node1 /bin/bash
```

### Log Locations

- **Container logs:** `docker-compose logs <service>`
- **Application logs:** Mounted in `/app/logs` inside containers
- **Host logs:** Available in `logs/` directory (if mounted)

## Performance Tuning

### Memory Settings
- Default JVM heap: 1GB max, 512MB min
- Adjust `JAVA_OPTS` in Dockerfile for different requirements

### Network Settings
- Default buffer sizes optimized for high throughput
- Consider adjusting Netty settings for specific workloads

### Storage Settings
- Data persistence through Docker volumes
- Consider using named volumes for production

## Production Considerations

### Security
- Change default ports in production
- Use Docker secrets for sensitive configuration
- Implement proper network segmentation

### Monitoring
- Set up external monitoring (Prometheus, Grafana)
- Configure log aggregation
- Implement health check endpoints

### Scaling
- Add more nodes by extending docker-compose.yml
- Consider using Docker Swarm or Kubernetes for orchestration
- Implement proper service discovery

### Backup
- Regular volume backups
- Point-in-time recovery capabilities
- Cross-region replication

## Development

### Building from Source
```bash
# Build the project
./gradlew build

# Build Docker image
docker-compose build

# Run tests
./gradlew test
```

### Custom Configuration
- Modify `docker-compose.yml` for custom settings
- Update environment variables as needed
- Extend Dockerfile for additional dependencies

## Support

For issues and questions:
1. Check the troubleshooting section
2. Review container logs
3. Verify system requirements
4. Check Docker and Docker Compose versions 