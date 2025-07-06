#!/bin/bash

# FastCache Cluster Monitoring Script

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
NODES=("localhost:6379" "localhost:6380" "localhost:6381")
PROXY="localhost:6382"

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

# Function to check if a port is open
check_port() {
    local host=$1
    local port=$2
    nc -z $host $port 2>/dev/null
}

# Function to get container status
get_container_status() {
    local container_name=$1
    docker ps --filter "name=$container_name" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
}

# Function to get container logs
get_container_logs() {
    local container_name=$1
    local lines=${2:-10}
    docker logs --tail=$lines $container_name 2>/dev/null || echo "No logs available"
}

# Function to check cluster health
check_cluster_health() {
    print_status "Checking cluster health..."
    echo ""
    
    # Check container status
    print_status "Container Status:"
    docker-compose ps
    echo ""
    
    # Check network connectivity
    print_status "Network Connectivity:"
    for node in "${NODES[@]}"; do
        local host=$(echo $node | cut -d: -f1)
        local port=$(echo $node | cut -d: -f2)
        
        if check_port $host $port; then
            print_success "✓ $node is reachable"
        else
            print_error "✗ $node is not reachable"
        fi
    done
    
    if check_port $(echo $PROXY | cut -d: -f1) $(echo $PROXY | cut -d: -f2); then
        print_success "✓ $PROXY (proxy) is reachable"
    else
        print_error "✗ $PROXY (proxy) is not reachable"
    fi
    echo ""
}

# Function to show resource usage
show_resource_usage() {
    print_status "Resource Usage:"
    echo ""
    
    # Show container resource usage
    docker stats --no-stream --format "table {{.Container}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}\t{{.NetIO}}\t{{.BlockIO}}"
    echo ""
}

# Function to show recent logs
show_recent_logs() {
    local lines=${1:-20}
    print_status "Recent logs (last $lines lines):"
    echo ""
    docker-compose logs --tail=$lines
    echo ""
}

# Function to show individual node logs
show_node_logs() {
    local node_name=$1
    local lines=${2:-10}
    
    print_status "Logs for $node_name (last $lines lines):"
    echo ""
    get_container_logs $node_name $lines
    echo ""
}

# Function to test basic operations
test_basic_operations() {
    print_status "Testing basic operations..."
    echo ""
    
    # Test SET and GET operations
    local test_key="test_key_$(date +%s)"
    local test_value="test_value_$(date +%s)"
    
    # Test through proxy
    print_status "Testing through proxy ($PROXY):"
    
    # Note: This is a placeholder for actual Redis protocol testing
    # In a real implementation, you would use redis-cli or a custom client
    echo "  Testing SET operation..."
    echo "  Testing GET operation..."
    echo "  Testing SORTED SET operations..."
    
    print_warning "Note: Actual protocol testing requires redis-cli or custom client implementation"
    echo ""
}

# Function to show cluster topology
show_cluster_topology() {
    print_status "Cluster Topology:"
    echo ""
    echo "┌─────────────────────────────────────────────────────────────┐"
    echo "│                    FastCache Cluster                        │"
    echo "├─────────────────────────────────────────────────────────────┤"
    echo "│                                                             │"
    echo "│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐     │"
    echo "│  │   Node 1    │    │   Node 2    │    │   Node 3    │     │"
    echo "│  │ localhost   │    │ localhost   │    │ localhost   │     │"
    echo "│  │    :6379    │    │    :6380    │    │    :6381    │     │"
    echo "│  └─────────────┘    └─────────────┘    └─────────────┘     │"
    echo "│         │                   │                   │          │"
    echo "│         └───────────────────┼───────────────────┘          │"
    echo "│                             │                              │"
    echo "│                    ┌─────────┴─────────┐                   │"
    echo "│                    │      Proxy        │                   │"
    echo "│                    │   localhost:6382  │                   │"
    echo "│                    └───────────────────┘                   │"
    echo "│                                                             │"
    echo "└─────────────────────────────────────────────────────────────┘"
    echo ""
}

# Function to show usage
show_usage() {
    echo "Usage: $0 [COMMAND]"
    echo ""
    echo "Commands:"
    echo "  health     Check cluster health and connectivity"
    echo "  resources  Show resource usage"
    echo "  logs       Show recent logs from all services"
    echo "  logs-node  Show logs from a specific node (e.g., logs-node fastcache-node1)"
    echo "  test       Test basic operations"
    echo "  topology   Show cluster topology"
    echo "  all        Run all monitoring checks"
    echo "  help       Show this help message"
    echo ""
    echo "Examples:"
    echo "  $0 health              # Check cluster health"
    echo "  $0 logs-node node1     # Show logs for node1"
    echo "  $0 all                 # Run all checks"
}

# Main script logic
case "${1:-health}" in
    health)
        check_cluster_health
        ;;
    resources)
        show_resource_usage
        ;;
    logs)
        show_recent_logs
        ;;
    logs-node)
        if [ -z "$2" ]; then
            print_error "Please specify a node name"
            echo "Available nodes: fastcache-node1, fastcache-node2, fastcache-node3, fastcache-proxy"
            exit 1
        fi
        show_node_logs $2
        ;;
    test)
        test_basic_operations
        ;;
    topology)
        show_cluster_topology
        ;;
    all)
        check_cluster_health
        show_resource_usage
        show_recent_logs
        test_basic_operations
        show_cluster_topology
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