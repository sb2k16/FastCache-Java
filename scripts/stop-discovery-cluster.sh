#!/bin/bash

# FastCache Cluster with Service Discovery Stop Script

set -e

echo "=== Stopping FastCache Cluster with Service Discovery ==="

# Stop the cluster
echo "Stopping FastCache cluster..."
docker-compose -f docker/docker-compose-with-discovery.yml down

# Remove volumes if requested
if [ "$1" = "--clean" ]; then
    echo "Removing volumes..."
    docker-compose -f docker/docker-compose-with-discovery.yml down -v
    echo "Cleaning up data directories..."
    rm -rf data/node1 data/node2 data/node3
fi

echo "✅ FastCache cluster with service discovery stopped successfully" 