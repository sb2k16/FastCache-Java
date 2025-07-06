#!/bin/bash

# FastCache Large Cluster Startup Script
# Starts 50 nodes, 5 proxies, 1 load balancer, and 1 centralized health checker

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
CLUSTER_NAME="fastcache-large-cluster"
NODE_COUNT=50
PROXY_COUNT=5
HEALTH_CHECKER_PORT=8080
LOAD_BALANCER_PORT=6380
LOAD_BALANCER_STATS_PORT=8404

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}    FastCache Large Cluster Startup    ${NC}"
echo -e "${BLUE}========================================${NC}"
echo -e "${YELLOW}Configuration:${NC}"
echo -e "  • Nodes: ${NODE_COUNT}"
echo -e "  • Proxies: ${PROXY_COUNT}"
echo -e "  • Load Balancer: HAProxy"
echo -e "  • Health Checker: Centralized Service"
echo -e "  • Health Service Port: ${HEALTH_CHECKER_PORT}"
echo -e "  • Load Balancer Port: ${LOAD_BALANCER_PORT}"
echo -e "  • Load Balancer Stats: ${LOAD_BALANCER_STATS_PORT}"
echo ""

# Check if Docker is running
if ! docker info > /dev/null 2>&1; then
    echo -e "${RED}Error: Docker is not running${NC}"
    exit 1
fi

# Check if docker-compose is available
if ! command -v docker-compose &> /dev/null; then
    echo -e "${RED}Error: docker-compose is not installed${NC}"
    exit 1
fi

# Build the FastCache image if it doesn't exist
echo -e "${YELLOW}Building FastCache Docker image...${NC}"
if ! docker image inspect fastcache:latest > /dev/null 2>&1; then
    echo -e "${BLUE}Building FastCache image...${NC}"
    docker build -t fastcache:latest .
else
    echo -e "${GREEN}FastCache image already exists${NC}"
fi

# Stop any existing cluster
echo -e "${YELLOW}Stopping any existing cluster...${NC}"
docker-compose -f docker-compose-large.yml down --remove-orphans 2>/dev/null || true

# Create network if it doesn't exist
echo -e "${YELLOW}Creating Docker network...${NC}"
docker network create fastcache_network 2>/dev/null || echo -e "${GREEN}Network already exists${NC}"

# Start the cluster
echo -e "${YELLOW}Starting FastCache large cluster...${NC}"
echo -e "${BLUE}This will start:${NC}"
echo -e "  • 1 Centralized Health Checker Service"
echo -e "  • 1 HAProxy Load Balancer"
echo -e "  • ${PROXY_COUNT} FastCache Proxies"
echo -e "  • ${NODE_COUNT} FastCache Nodes"
echo ""

# Start with health checker first
echo -e "${YELLOW}Starting centralized health checker service...${NC}"
docker-compose -f docker-compose-large.yml up -d health-checker

# Wait for health checker to be ready
echo -e "${YELLOW}Waiting for health checker service to be ready...${NC}"
for i in {1..30}; do
    if curl -s http://localhost:${HEALTH_CHECKER_PORT}/health/ping > /dev/null 2>&1; then
        echo -e "${GREEN}Health checker service is ready${NC}"
        break
    fi
    if [ $i -eq 30 ]; then
        echo -e "${RED}Error: Health checker service failed to start${NC}"
        docker-compose -f docker-compose-large.yml logs health-checker
        exit 1
    fi
    echo -n "."
    sleep 2
done
echo ""

# Start load balancer
echo -e "${YELLOW}Starting HAProxy load balancer...${NC}"
docker-compose -f docker-compose-large.yml up -d load-balancer

# Wait for load balancer to be ready
echo -e "${YELLOW}Waiting for load balancer to be ready...${NC}"
for i in {1..15}; do
    if nc -z localhost ${LOAD_BALANCER_PORT} 2>/dev/null; then
        echo -e "${GREEN}Load balancer is ready${NC}"
        break
    fi
    if [ $i -eq 15 ]; then
        echo -e "${RED}Error: Load balancer failed to start${NC}"
        docker-compose -f docker-compose-large.yml logs load-balancer
        exit 1
    fi
    echo -n "."
    sleep 2
done
echo ""

# Start proxies
echo -e "${YELLOW}Starting FastCache proxies...${NC}"
docker-compose -f docker-compose-large.yml up -d fastcache-proxy1 fastcache-proxy2 fastcache-proxy3 fastcache-proxy4 fastcache-proxy5

# Wait for proxies to be ready
echo -e "${YELLOW}Waiting for proxies to be ready...${NC}"
for proxy in 1 2 3 4 5; do
    port=$((6381 + proxy))
    echo -e "${BLUE}Checking proxy ${proxy} (port ${port})...${NC}"
    for i in {1..20}; do
        if nc -z localhost ${port} 2>/dev/null; then
            echo -e "${GREEN}Proxy ${proxy} is ready${NC}"
            break
        fi
        if [ $i -eq 20 ]; then
            echo -e "${RED}Error: Proxy ${proxy} failed to start${NC}"
            docker-compose -f docker-compose-large.yml logs fastcache-proxy${proxy}
            exit 1
        fi
        echo -n "."
        sleep 2
    done
    echo ""
done

# Start nodes in batches
echo -e "${YELLOW}Starting FastCache nodes in batches...${NC}"
BATCH_SIZE=10

for batch in $(seq 1 $((NODE_COUNT / BATCH_SIZE))); do
    start_node=$((1 + (batch - 1) * BATCH_SIZE))
    end_node=$((batch * BATCH_SIZE))
    
    if [ $end_node -gt $NODE_COUNT ]; then
        end_node=$NODE_COUNT
    fi
    
    echo -e "${BLUE}Starting nodes ${start_node}-${end_node}...${NC}"
    
    # Build service names for this batch
    services=""
    for node in $(seq $start_node $end_node); do
        services="$services fastcache-node${node}"
    done
    
    # Start the batch
    docker-compose -f docker-compose-large.yml up -d $services
    
    # Wait for this batch to be ready
    echo -e "${YELLOW}Waiting for nodes ${start_node}-${end_node} to be ready...${NC}"
    for node in $(seq $start_node $end_node); do
        port=$((7000 + node))
        echo -e "${BLUE}Checking node ${node} (port ${port})...${NC}"
        for i in {1..15}; do
            if nc -z localhost ${port} 2>/dev/null; then
                echo -e "${GREEN}Node ${node} is ready${NC}"
                break
            fi
            if [ $i -eq 15 ]; then
                echo -e "${RED}Error: Node ${node} failed to start${NC}"
                docker-compose -f docker-compose-large.yml logs fastcache-node${node}
                exit 1
            fi
            echo -n "."
            sleep 2
        done
        echo ""
    done
    
    # Small delay between batches
    if [ $batch -lt $((NODE_COUNT / BATCH_SIZE)) ]; then
        echo -e "${YELLOW}Waiting 5 seconds before next batch...${NC}"
        sleep 5
    fi
done

# Final health check
echo -e "${YELLOW}Performing final health checks...${NC}"

# Check health checker service
if curl -s http://localhost:${HEALTH_CHECKER_PORT}/health/ping > /dev/null 2>&1; then
    echo -e "${GREEN}✓ Health checker service is healthy${NC}"
else
    echo -e "${RED}✗ Health checker service is not responding${NC}"
fi

# Check load balancer
if nc -z localhost ${LOAD_BALANCER_PORT} 2>/dev/null; then
    echo -e "${GREEN}✓ Load balancer is healthy${NC}"
else
    echo -e "${RED}✗ Load balancer is not responding${NC}"
fi

# Check proxies
for proxy in 1 2 3 4 5; do
    port=$((6381 + proxy))
    if nc -z localhost ${port} 2>/dev/null; then
        echo -e "${GREEN}✓ Proxy ${proxy} is healthy${NC}"
    else
        echo -e "${RED}✗ Proxy ${proxy} is not responding${NC}"
    fi
done

# Check a sample of nodes
for node in 1 10 25 50; do
    port=$((7000 + node))
    if nc -z localhost ${port} 2>/dev/null; then
        echo -e "${GREEN}✓ Node ${node} is healthy${NC}"
    else
        echo -e "${RED}✗ Node ${node} is not responding${NC}"
    fi
done

echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}    FastCache Large Cluster Started    ${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo -e "${BLUE}Access Points:${NC}"
echo -e "  • Load Balancer: localhost:${LOAD_BALANCER_PORT}"
echo -e "  • Load Balancer Stats: http://localhost:${LOAD_BALANCER_STATS_PORT}/stats"
echo -e "  • Health Checker API: http://localhost:${HEALTH_CHECKER_PORT}/health"
echo -e "  • Proxy 1: localhost:6382"
echo -e "  • Proxy 2: localhost:6383"
echo -e "  • Proxy 3: localhost:6384"
echo -e "  • Proxy 4: localhost:6385"
echo -e "  • Proxy 5: localhost:6386"
echo -e "  • Node 1: localhost:7001"
echo -e "  • Node 50: localhost:7050"
echo ""
echo -e "${BLUE}Useful Commands:${NC}"
echo -e "  • Monitor cluster: ./scripts/monitor-cluster.sh"
echo -e "  • Health monitoring: ./scripts/health-monitor.sh"
echo -e "  • Test cluster: ./scripts/test-cluster.sh"
echo -e "  • Stop cluster: ./scripts/stop-cluster.sh"
echo ""
echo -e "${BLUE}Example Redis Commands:${NC}"
echo -e "  • Connect: redis-cli -h localhost -p ${LOAD_BALANCER_PORT}"
echo -e "  • Set key: SET mykey myvalue"
echo -e "  • Get key: GET mykey"
echo -e "  • Sorted set: ZADD myset 1.0 member1"
echo -e "  • Health check: PING"
echo ""
echo -e "${YELLOW}Cluster is ready for use!${NC}" 