#!/bin/bash

# FastCache Cluster Startup Script
# This script starts a 3-node FastCache cluster using Docker Compose

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
CLUSTER_NAME="fastcache-cluster"
COMPOSE_FILE="docker-compose.yml"
LOG_DIR="logs"

# Function to print colored output
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

# Function to check prerequisites
check_prerequisites() {
    print_status "Checking prerequisites..."
    
    # Check if Docker is installed
    if ! command -v docker &> /dev/null; then
        print_error "Docker is not installed. Please install Docker first."
        exit 1
    fi
    
    # Check if Docker Compose is installed
    if ! command -v docker-compose &> /dev/null; then
        print_error "Docker Compose is not installed. Please install Docker Compose first."
        exit 1
    fi
    
    # Check if Docker daemon is running
    if ! docker info &> /dev/null; then
        print_error "Docker daemon is not running. Please start Docker first."
        exit 1
    fi
    
    print_success "Prerequisites check passed"
}

# Function to create necessary directories
setup_directories() {
    print_status "Setting up directories..."
    
    # Create logs directory
    mkdir -p $LOG_DIR
    
    print_success "Directories setup complete"
}

# Function to build the Docker image
build_image() {
    print_status "Building FastCache Docker image..."
    
    if docker-compose build; then
        print_success "Docker image built successfully"
    else
        print_error "Failed to build Docker image"
        exit 1
    fi
}

# Function to start the cluster
start_cluster() {
    print_status "Starting FastCache cluster..."
    
    # Start the services
    if docker-compose up -d; then
        print_success "Cluster started successfully"
    else
        print_error "Failed to start cluster"
        exit 1
    fi
}

# Function to wait for services to be ready
wait_for_services() {
    print_status "Waiting for services to be ready..."
    
    local max_attempts=30
    local attempt=1
    
    while [ $attempt -le $max_attempts ]; do
        local ready_count=0
        local total_services=4  # 3 nodes + 1 proxy
        
        # Check each service
        if docker-compose ps | grep -q "fastcache-node1.*Up"; then
            ((ready_count++))
        fi
        
        if docker-compose ps | grep -q "fastcache-node2.*Up"; then
            ((ready_count++))
        fi
        
        if docker-compose ps | grep -q "fastcache-node3.*Up"; then
            ((ready_count++))
        fi
        
        if docker-compose ps | grep -q "fastcache-proxy.*Up"; then
            ((ready_count++))
        fi
        
        if [ $ready_count -eq $total_services ]; then
            print_success "All services are ready!"
            break
        fi
        
        print_status "Waiting for services... ($ready_count/$total_services ready) - Attempt $attempt/$max_attempts"
        sleep 5
        ((attempt++))
    done
    
    if [ $attempt -gt $max_attempts ]; then
        print_warning "Some services may not be fully ready. Check logs for details."
    fi
}

# Function to display cluster status
show_status() {
    print_status "Cluster status:"
    echo ""
    docker-compose ps
    echo ""
    
    print_status "Service endpoints:"
    echo "  Node 1: localhost:6379"
    echo "  Node 2: localhost:6380"
    echo "  Node 3: localhost:6381"
    echo "  Proxy:  localhost:6382"
    echo ""
    
    print_status "Network information:"
    docker network ls | grep fastcache_network || echo "  Network not found"
    echo ""
}

# Function to show logs
show_logs() {
    print_status "Recent logs from all services:"
    echo ""
    docker-compose logs --tail=20
}

# Function to test cluster connectivity
test_cluster() {
    print_status "Testing cluster connectivity..."
    
    # Test each node
    local nodes=("localhost:6379" "localhost:6380" "localhost:6381" "localhost:6382")
    
    for node in "${nodes[@]}"; do
        local host=$(echo $node | cut -d: -f1)
        local port=$(echo $node | cut -d: -f2)
        
        if nc -z $host $port 2>/dev/null; then
            print_success "Node $node is reachable"
        else
            print_warning "Node $node is not reachable"
        fi
    done
}

# Function to display usage
show_usage() {
    echo "Usage: $0 [COMMAND]"
    echo ""
    echo "Commands:"
    echo "  start     Start the FastCache cluster"
    echo "  stop      Stop the FastCache cluster"
    echo "  restart   Restart the FastCache cluster"
    echo "  status    Show cluster status"
    echo "  logs      Show recent logs"
    echo "  test      Test cluster connectivity"
    echo "  clean     Stop and remove all containers, networks, and volumes"
    echo "  help      Show this help message"
    echo ""
    echo "Examples:"
    echo "  $0 start    # Start the cluster"
    echo "  $0 status   # Check cluster status"
    echo "  $0 logs     # View logs"
}

# Function to stop the cluster
stop_cluster() {
    print_status "Stopping FastCache cluster..."
    
    if docker-compose down; then
        print_success "Cluster stopped successfully"
    else
        print_error "Failed to stop cluster"
        exit 1
    fi
}

# Function to clean up everything
clean_cluster() {
    print_status "Cleaning up FastCache cluster..."
    
    if docker-compose down -v --remove-orphans; then
        print_success "Cluster cleaned up successfully"
    else
        print_error "Failed to clean up cluster"
        exit 1
    fi
}

# Main script logic
case "${1:-start}" in
    start)
        check_prerequisites
        setup_directories
        build_image
        start_cluster
        wait_for_services
        show_status
        test_cluster
        print_success "FastCache cluster is ready!"
        echo ""
        echo "You can now connect to:"
        echo "  - Individual nodes: localhost:6379, localhost:6380, localhost:6381"
        echo "  - Load balancer: localhost:6382"
        echo ""
        echo "Use '$0 status' to check cluster status"
        echo "Use '$0 logs' to view logs"
        ;;
    stop)
        stop_cluster
        ;;
    restart)
        stop_cluster
        sleep 2
        check_prerequisites
        setup_directories
        build_image
        start_cluster
        wait_for_services
        show_status
        test_cluster
        ;;
    status)
        show_status
        ;;
    logs)
        show_logs
        ;;
    test)
        test_cluster
        ;;
    clean)
        clean_cluster
        ;;
    help|--help|-h)
        show_usage
        ;;
    *)
        print_error "Unknown command: $1"
        show_usage
        exit 1
        ;;
esac 