#!/bin/bash

# FastCache Health Monitoring Script
# Monitors the centralized health checker service and cluster components

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Configuration
HEALTH_SERVICE_URL="http://localhost:8080"
LOAD_BALANCER_PORT=6380
LOAD_BALANCER_STATS_PORT=8404
NODE_COUNT=50
PROXY_COUNT=5

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

# Function to check if health service is available
check_health_service() {
    if curl -s "${HEALTH_SERVICE_URL}/health/ping" > /dev/null 2>&1; then
        return 0
    else
        return 1
    fi
}

# Function to get health summary from service
get_health_summary() {
    if check_health_service; then
        curl -s "${HEALTH_SERVICE_URL}/health/summary" 2>/dev/null || echo "{}"
    else
        echo "{}"
    fi
}

# Function to get healthy nodes
get_healthy_nodes() {
    if check_health_service; then
        curl -s "${HEALTH_SERVICE_URL}/health/healthy" 2>/dev/null || echo "[]"
    else
        echo "[]"
    fi
}

# Function to get unhealthy nodes
get_unhealthy_nodes() {
    if check_health_service; then
        curl -s "${HEALTH_SERVICE_URL}/health/unhealthy" 2>/dev/null || echo "[]"
    else
        echo "[]"
    fi
}

# Function to check load balancer health
check_load_balancer() {
    print_status "Checking HAProxy load balancer..."
    
    # Check if load balancer is listening
    if nc -z localhost ${LOAD_BALANCER_PORT} 2>/dev/null; then
        print_success "✓ Load balancer is listening on port ${LOAD_BALANCER_PORT}"
    else
        print_error "✗ Load balancer is not listening on port ${LOAD_BALANCER_PORT}"
        return 1
    fi
    
    # Check HAProxy stats page
    if curl -s "http://localhost:${LOAD_BALANCER_STATS_PORT}/stats" > /dev/null 2>&1; then
        print_success "✓ HAProxy stats page is accessible"
    else
        print_warning "⚠ HAProxy stats page is not accessible"
    fi
    
    # Check proxy health through load balancer
    print_status "Checking proxy health through load balancer..."
    for i in {1..5}; do
        if echo -e "PING\r\n" | nc localhost ${LOAD_BALANCER_PORT} 2>/dev/null | grep -q "PONG"; then
            print_success "✓ Proxy health check through load balancer successful"
            break
        fi
        if [ $i -eq 5 ]; then
            print_warning "⚠ Proxy health check through load balancer failed"
        fi
        sleep 1
    done
}

# Function to check proxy health directly
check_proxies() {
    print_status "Checking FastCache proxies directly..."
    
    local healthy_proxies=0
    local total_proxies=${PROXY_COUNT}
    
    for proxy in $(seq 1 ${PROXY_COUNT}); do
        local port=$((6381 + proxy))
        
        if nc -z localhost ${port} 2>/dev/null; then
            print_success "✓ Proxy ${proxy} (port ${port}) is listening"
            
            # Test Redis protocol
            if echo -e "PING\r\n" | nc localhost ${port} 2>/dev/null | grep -q "PONG"; then
                print_success "  ✓ Proxy ${proxy} responds to PING"
                ((healthy_proxies++))
            else
                print_warning "  ⚠ Proxy ${proxy} does not respond to PING"
            fi
        else
            print_error "✗ Proxy ${proxy} (port ${port}) is not listening"
        fi
    done
    
    echo ""
    print_status "Proxy Health Summary: ${healthy_proxies}/${total_proxies} proxies healthy"
    
    if [ ${healthy_proxies} -eq ${total_proxies} ]; then
        print_success "All proxies are healthy!"
    elif [ ${healthy_proxies} -gt 0 ]; then
        print_warning "Some proxies are unhealthy"
    else
        print_error "No proxies are healthy!"
    fi
}

# Function to check node health through health service
check_nodes_health_service() {
    print_status "Checking node health through centralized health service..."
    
    if ! check_health_service; then
        print_error "Health service is not available"
        return 1
    fi
    
    # Get health summary
    local summary=$(get_health_summary)
    local healthy_nodes=$(echo "$summary" | grep -o '"healthyNodes":[0-9]*' | cut -d':' -f2 || echo "0")
    local total_nodes=$(echo "$summary" | grep -o '"totalNodes":[0-9]*' | cut -d':' -f2 || echo "0")
    
    if [ -z "$healthy_nodes" ]; then
        healthy_nodes=0
    fi
    if [ -z "$total_nodes" ]; then
        total_nodes=0
    fi
    
    print_status "Node Health Summary: ${healthy_nodes}/${total_nodes} nodes healthy"
    
    if [ ${healthy_nodes} -eq ${total_nodes} ] && [ ${total_nodes} -gt 0 ]; then
        print_success "All nodes are healthy!"
    elif [ ${healthy_nodes} -gt 0 ]; then
        print_warning "Some nodes are unhealthy"
        
        # Show unhealthy nodes
        local unhealthy_nodes=$(get_unhealthy_nodes)
        if [ "$unhealthy_nodes" != "[]" ]; then
            print_status "Unhealthy nodes:"
            echo "$unhealthy_nodes" | grep -o '"nodeId":"[^"]*"' | cut -d'"' -f4 | while read node; do
                print_warning "  • ${node}"
            done
        fi
    else
        print_error "No nodes are healthy!"
    fi
}

# Function to check sample nodes directly
check_sample_nodes() {
    print_status "Checking sample nodes directly..."
    
    local sample_nodes=(1 10 25 50)
    local healthy_count=0
    
    for node in "${sample_nodes[@]}"; do
        local port=$((7000 + node))
        
        if nc -z localhost ${port} 2>/dev/null; then
            print_success "✓ Node ${node} (port ${port}) is listening"
            
            # Test Redis protocol
            if echo -e "PING\r\n" | nc localhost ${port} 2>/dev/null | grep -q "PONG"; then
                print_success "  ✓ Node ${node} responds to PING"
                ((healthy_count++))
            else
                print_warning "  ⚠ Node ${node} does not respond to PING"
            fi
        else
            print_error "✗ Node ${node} (port ${port}) is not listening"
        fi
    done
    
    echo ""
    print_status "Sample Node Health: ${healthy_count}/${#sample_nodes[@]} nodes healthy"
}

# Function to show cluster statistics
show_cluster_stats() {
    print_status "Cluster Statistics..."
    
    if ! check_health_service; then
        print_error "Cannot get cluster stats - health service unavailable"
        return 1
    fi
    
    local stats=$(curl -s "${HEALTH_SERVICE_URL}/health/stats" 2>/dev/null || echo "{}")
    
    echo ""
    echo -e "${CYAN}Cluster Overview:${NC}"
    echo "  • Total Nodes: ${NODE_COUNT}"
    echo "  • Total Proxies: ${PROXY_COUNT}"
    echo "  • Load Balancer: HAProxy"
    echo "  • Health Service: Centralized"
    echo ""
    
    echo -e "${CYAN}Health Service Status:${NC}"
    if check_health_service; then
        print_success "✓ Health service is available"
        
        # Parse and display stats
        local healthy_nodes=$(echo "$stats" | grep -o '"healthyNodes":[0-9]*' | cut -d':' -f2 || echo "0")
        local unhealthy_nodes=$(echo "$stats" | grep -o '"unhealthyNodes":[0-9]*' | cut -d':' -f2 || echo "0")
        local health_percentage=$(echo "$stats" | grep -o '"healthPercentage":[0-9.]*' | cut -d':' -f2 || echo "0")
        
        echo "  • Healthy Nodes: ${healthy_nodes}"
        echo "  • Unhealthy Nodes: ${unhealthy_nodes}"
        echo "  • Health Percentage: ${health_percentage}%"
        echo "  • Check Interval: 30 seconds"
        echo "  • Timeout: 5 seconds"
    else
        print_error "✗ Health service is not available"
    fi
}

# Function to show detailed health information
show_detailed_health() {
    print_status "Detailed Health Information..."
    
    if ! check_health_service; then
        print_error "Cannot get detailed health - health service unavailable"
        return 1
    fi
    
    echo ""
    echo -e "${CYAN}All Node Health Status:${NC}"
    
    # Get all node health
    local all_nodes=$(curl -s "${HEALTH_SERVICE_URL}/health/nodes" 2>/dev/null || echo "[]")
    
    if [ "$all_nodes" != "[]" ]; then
        # Parse and display node health (simplified)
        echo "$all_nodes" | grep -o '"nodeId":"[^"]*"' | cut -d'"' -f4 | sort | while read node; do
            local node_health=$(curl -s "${HEALTH_SERVICE_URL}/health/nodes/${node}" 2>/dev/null || echo "{}")
            local status=$(echo "$node_health" | grep -o '"status":"[^"]*"' | cut -d'"' -f4 || echo "UNKNOWN")
            
            if [ "$status" = "HEALTHY" ]; then
                print_success "  • ${node}: ${status}"
            else
                print_error "  • ${node}: ${status}"
            fi
        done
    else
        print_warning "No node health information available"
    fi
}

# Function to show logs
show_logs() {
    local service=${1:-"all"}
    
    print_status "Showing logs for: ${service}"
    
    case $service in
        "health-checker")
            docker-compose -f docker-compose-large.yml logs --tail=20 health-checker
            ;;
        "load-balancer")
            docker-compose -f docker-compose-large.yml logs --tail=20 load-balancer
            ;;
        "proxies")
            for proxy in 1 2 3 4 5; do
                echo -e "${CYAN}=== Proxy ${proxy} Logs ===${NC}"
                docker-compose -f docker-compose-large.yml logs --tail=10 fastcache-proxy${proxy}
                echo ""
            done
            ;;
        "nodes")
            for node in 1 10 25 50; do
                echo -e "${CYAN}=== Node ${node} Logs ===${NC}"
                docker-compose -f docker-compose-large.yml logs --tail=5 fastcache-node${node}
                echo ""
            done
            ;;
        "all")
            docker-compose -f docker-compose-large.yml logs --tail=10
            ;;
        *)
            print_error "Unknown service: ${service}"
            print_status "Available services: health-checker, load-balancer, proxies, nodes, all"
            ;;
    esac
}

# Function to show usage
show_usage() {
    echo "Usage: $0 [COMMAND] [OPTIONS]"
    echo ""
    echo "Commands:"
    echo "  all                    Show all health checks"
    echo "  summary                Show cluster health summary"
    echo "  load-balancer          Check load balancer health"
    echo "  proxies                Check proxy health"
    echo "  nodes                  Check node health through health service"
    echo "  sample-nodes           Check sample nodes directly"
    echo "  stats                  Show cluster statistics"
    echo "  detailed               Show detailed health information"
    echo "  logs [SERVICE]         Show logs for specific service"
    echo "  help                   Show this help message"
    echo ""
    echo "Options for logs:"
    echo "  SERVICE can be: health-checker, load-balancer, proxies, nodes, all"
    echo ""
    echo "Examples:"
    echo "  $0 all                 # Run all health checks"
    echo "  $0 summary             # Show health summary"
    echo "  $0 logs health-checker # Show health checker logs"
    echo "  $0 logs all            # Show all logs"
    echo ""
    echo "Health Service URL: ${HEALTH_SERVICE_URL}"
}

# Main script logic
case "${1:-all}" in
    "all")
        echo -e "${BLUE}========================================${NC}"
        echo -e "${BLUE}    FastCache Health Monitoring       ${NC}"
        echo -e "${BLUE}========================================${NC}"
        echo ""
        
        check_load_balancer
        echo ""
        
        check_proxies
        echo ""
        
        check_nodes_health_service
        echo ""
        
        check_sample_nodes
        echo ""
        
        show_cluster_stats
        ;;
    "summary")
        show_cluster_stats
        ;;
    "load-balancer")
        check_load_balancer
        ;;
    "proxies")
        check_proxies
        ;;
    "nodes")
        check_nodes_health_service
        ;;
    "sample-nodes")
        check_sample_nodes
        ;;
    "stats")
        show_cluster_stats
        ;;
    "detailed")
        show_detailed_health
        ;;
    "logs")
        show_logs "$2"
        ;;
    "help"|"--help"|"-h")
        show_usage
        ;;
    *)
        print_error "Unknown command: $1"
        show_usage
        exit 1
        ;;
esac 