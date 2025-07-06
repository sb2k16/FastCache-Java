#!/bin/bash

# FastCache 3-Node Cluster Stop Script
# This script stops and removes the 3-node cluster and associated resources

set -e

echo "=== FastCache 3-Node Cluster Stop Script ==="
echo

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

print_status() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Stop and remove containers and volumes
stop_cluster() {
    print_status "Stopping and removing cluster containers and volumes..."
    docker-compose -f docker-compose-3nodes.yml down --volumes --remove-orphans
    print_success "Cluster stopped and resources cleaned up"
}

main() {
    stop_cluster
    echo
    print_success "FastCache 3-node cluster has been stopped."
}

main "$@" 