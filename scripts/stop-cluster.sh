#!/bin/bash

# FastCache Cluster Stop Script

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

print_status() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

print_status "Stopping FastCache cluster..."

# Stop all services
if docker-compose down; then
    print_success "Cluster stopped successfully"
else
    print_error "Failed to stop cluster"
    exit 1
fi

print_status "Cluster cleanup complete" 