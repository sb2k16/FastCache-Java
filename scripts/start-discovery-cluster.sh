#!/bin/bash

# FastCache Cluster with Service Discovery Startup Script
# This script starts the complete FastCache cluster with dynamic service discovery

set -e

echo "=== FastCache Cluster with Service Discovery ==="
echo "Starting FastCache cluster with dynamic node discovery..."

# Check if Docker is running
if ! docker info > /dev/null 2>&1; then
    echo "Error: Docker is not running. Please start Docker and try again."
    exit 1
fi

# Check if Docker Compose is available
if ! command -v docker-compose > /dev/null 2>&1; then
    echo "Error: Docker Compose is not installed. Please install Docker Compose and try again."
    exit 1
fi

# Build the project first
echo "Building FastCache project..."
./gradlew clean build fatJar

# Create data directories
echo "Creating data directories..."
mkdir -p data/node1 data/node2 data/node3

# Start the cluster with service discovery
echo "Starting FastCache cluster with service discovery..."
docker-compose -f docker/docker-compose-with-discovery.yml up -d

# Wait for services to start
echo "Waiting for services to start..."
sleep 10

# Check service discovery status
echo "Checking service discovery status..."
if curl -f http://localhost:8081/discovery/ping > /dev/null 2>&1; then
    echo "✅ Service Discovery API is running on http://localhost:8081"
else
    echo "❌ Service Discovery API is not responding"
fi

# Check health checker status
echo "Checking health checker status..."
if curl -f http://localhost:8080/health/ping > /dev/null 2>&1; then
    echo "✅ Health Checker is running on http://localhost:8080"
else
    echo "❌ Health Checker is not responding"
fi

# Check load balancer status
echo "Checking load balancer status..."
if curl -f http://localhost:8404 > /dev/null 2>&1; then
    echo "✅ Load Balancer (HAProxy) is running on http://localhost:8404"
else
    echo "❌ Load Balancer is not responding"
fi

# Show cluster status
echo ""
echo "=== Cluster Status ==="
echo "Service Discovery API: http://localhost:8081"
echo "Health Checker:        http://localhost:8080"
echo "Load Balancer:         http://localhost:6380"
echo "HAProxy Stats:         http://localhost:8404"
echo ""
echo "Proxy Ports:"
echo "  - Proxy 1: localhost:6382"
echo "  - Proxy 2: localhost:6383"
echo "  - Proxy 3: localhost:6384"
echo ""
echo "Cache Node Ports:"
echo "  - Node 1: localhost:7001"
echo "  - Node 2: localhost:7002"
echo "  - Node 3: localhost:7003"
echo ""

# Show service discovery endpoints
echo "=== Service Discovery Endpoints ==="
echo "Register Node:     POST http://localhost:8081/discovery/nodes"
echo "Get All Nodes:     GET  http://localhost:8081/discovery/nodes"
echo "Get Healthy Nodes: GET  http://localhost:8081/discovery/nodes/healthy"
echo "Get Cache Nodes:   GET  http://localhost:8081/discovery/nodes/type/CACHE/cache"
echo "Get Stats:         GET  http://localhost:8081/discovery/stats"
echo ""

# Test the cluster
echo "=== Testing Cluster ==="
echo "Testing Redis connectivity..."
if command -v redis-cli > /dev/null 2>&1; then
    echo "Testing SET/GET operations..."
    redis-cli -h localhost -p 6380 SET test_key "Hello FastCache with Service Discovery!"
    result=$(redis-cli -h localhost -p 6380 GET test_key)
    if [ "$result" = "Hello FastCache with Service Discovery!" ]; then
        echo "✅ Redis operations working correctly"
    else
        echo "❌ Redis operations failed"
    fi
    redis-cli -h localhost -p 6380 DEL test_key > /dev/null 2>&1
else
    echo "⚠️  redis-cli not found. Skipping Redis test."
fi

echo ""
echo "=== Cluster Started Successfully ==="
echo "You can now:"
echo "1. Connect to the cluster: redis-cli -h localhost -p 6380"
echo "2. Monitor health: curl http://localhost:8080/health/summary"
echo "3. View service discovery: curl http://localhost:8081/discovery/stats"
echo "4. Check HAProxy stats: http://localhost:8404"
echo ""
echo "To stop the cluster: ./scripts/stop-discovery-cluster.sh"
echo "To view logs: docker-compose -f docker/docker-compose-with-discovery.yml logs -f" 