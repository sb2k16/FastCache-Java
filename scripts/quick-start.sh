#!/bin/bash

# FastCache Quick Start Script
# This script provides a simple way to get the FastCache cluster running quickly

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

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

echo "┌─────────────────────────────────────────────────────────────┐"
echo "│                    FastCache Quick Start                    │"
echo "└─────────────────────────────────────────────────────────────┘"
echo ""

# Check if Docker is running
if ! docker info &> /dev/null; then
    print_error "Docker is not running. Please start Docker first."
    exit 1
fi

# Check if Docker Compose is available
if ! command -v docker-compose &> /dev/null; then
    print_error "Docker Compose is not installed. Please install Docker Compose first."
    exit 1
fi

print_status "Starting FastCache cluster..."

# Start the cluster
if ./scripts/start-cluster.sh start; then
    print_success "Cluster started successfully!"
    echo ""
    echo "┌─────────────────────────────────────────────────────────────┐"
    echo "│                    Cluster Information                      │"
    echo "├─────────────────────────────────────────────────────────────┤"
    echo "│                                                             │"
    echo "│  Service Endpoints:                                         │"
    echo "│  • Node 1: localhost:6379                                   │"
    echo "│  • Node 2: localhost:6380                                   │"
    echo "│  • Node 3: localhost:6381                                   │"
    echo "│  • Proxy:  localhost:6382                                   │"
    echo "│                                                             │"
    echo "│  Useful Commands:                                           │"
    echo "│  • Check status: ./scripts/monitor-cluster.sh health        │"
    echo "│  • View logs: ./scripts/monitor-cluster.sh logs             │"
    echo "│  • Test cluster: ./scripts/test-cluster.sh all              │"
    echo "│  • Stop cluster: ./scripts/stop-cluster.sh                  │"
    echo "│                                                             │"
    echo "└─────────────────────────────────────────────────────────────┘"
    echo ""
    print_success "FastCache cluster is ready to use!"
else
    print_error "Failed to start cluster. Check the logs above for details."
    exit 1
fi 