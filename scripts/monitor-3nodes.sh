#!/bin/bash

# FastCache 3-Node Cluster Monitoring Script
# This script monitors the health and performance of the 3-node cluster

set -e

echo "=== FastCache 3-Node Cluster Monitor ==="
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
    echo -e "${GREEN}[OK]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Monitor cluster status
monitor_cluster() {
    print_status "Cluster Status:"
    docker-compose -f docker-compose-3nodes.yml ps --format "table {{.Name}}\t{{.Status}}\t{{.Ports}}"
    echo
}

# Monitor health checker
monitor_health() {
    print_status "Health Checker Status:"
    if curl -f http://localhost:8080/health/summary > /dev/null 2>&1; then
        print_success "Health checker is responding"
        curl -s http://localhost:8080/health/summary | jq . 2>/dev/null || curl -s http://localhost:8080/health/summary
    else
        print_error "Health checker is not responding"
    fi
    echo
}

# Monitor HAProxy stats
monitor_haproxy() {
    print_status "HAProxy Stats:"
    if curl -f http://localhost:8404/stats > /dev/null 2>&1; then
        print_success "HAProxy is responding"
        echo "Load Balancer Stats:"
        curl -s http://localhost:8404/stats | grep -E "(proxy|FRONTEND|BACKEND)" | head -10
    else
        print_error "HAProxy is not responding"
    fi
    echo
}

# Monitor individual nodes
monitor_nodes() {
    print_status "Node Connectivity:"
    for port in 7001 7002 7003; do
        if redis-cli -p $port ping > /dev/null 2>&1; then
            print_success "Node $port: OK"
        else
            print_error "Node $port: FAILED"
        fi
    done
    echo
}

# Monitor proxies
monitor_proxies() {
    print_status "Proxy Connectivity:"
    for port in 6382 6383 6384; do
        if redis-cli -p $port ping > /dev/null 2>&1; then
            print_success "Proxy $port: OK"
        else
            print_error "Proxy $port: FAILED"
        fi
    done
    echo
}

# Monitor load balancer
monitor_load_balancer() {
    print_status "Load Balancer Connectivity:"
    if redis-cli -p 6380 ping > /dev/null 2>&1; then
        print_success "Load balancer: OK"
    else
        print_error "Load balancer: FAILED"
    fi
    echo
}

# Main monitoring loop
main() {
    while true; do
        clear
        echo "=== FastCache 3-Node Cluster Monitor ==="
        echo "Press Ctrl+C to exit"
        echo
        
        monitor_cluster
        monitor_health
        monitor_haproxy
        monitor_nodes
        monitor_proxies
        monitor_load_balancer
        
        echo "Monitoring... (refresh every 30 seconds)"
        sleep 30
    done
}

# Run main function
main "$@" 