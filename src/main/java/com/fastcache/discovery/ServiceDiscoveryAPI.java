package com.fastcache.discovery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SpringBootApplication
@RestController
@RequestMapping("/discovery")
public class ServiceDiscoveryAPI {
    
    private final ServiceDiscovery serviceDiscovery;
    
    public ServiceDiscoveryAPI() {
        ServiceDiscovery sd;
        try {
            // Use persistent service discovery with data directory
            String dataDir = System.getProperty("service.discovery.data.dir", "/app/data/service-discovery");
            String serviceId = System.getProperty("service.discovery.id", "service-discovery-1");
            
            sd = new PersistentServiceDiscovery(dataDir, serviceId);
            System.out.println("PersistentServiceDiscoveryAPI initialized with dataDir=" + dataDir + 
                              ", serviceId=" + serviceId);
        } catch (Exception e) {
            System.err.println("Failed to initialize persistent service discovery, falling back to in-memory: " + e.getMessage());
            sd = new ServiceDiscovery();
            System.out.println("ServiceDiscoveryAPI initialized with in-memory storage");
        }
        this.serviceDiscovery = sd;
    }
    
    @Bean
    public ServletWebServerFactory servletContainer() {
        System.out.println("Creating Tomcat web server factory for service discovery on port 8081");
        TomcatServletWebServerFactory tomcat = new TomcatServletWebServerFactory();
        tomcat.setPort(8081);
        try {
            tomcat.setAddress(InetAddress.getByName("0.0.0.0"));
        } catch (Exception e) {
            System.out.println("Error setting address: " + e.getMessage());
        }
        return tomcat;
    }
    
    public static void main(String[] args) {
        System.out.println("Starting ServiceDiscoveryAPI...");
        SpringApplication app = new SpringApplication(ServiceDiscoveryAPI.class);
        app.setWebApplicationType(WebApplicationType.SERVLET);
        app.run(args);
        System.out.println("ServiceDiscoveryAPI started successfully on port 8081");
    }
    
    // --- Node Registration Endpoints ---
    
    @PostMapping("/nodes")
    public ResponseEntity<Map<String, Object>> registerNode(@RequestBody NodeRegistrationRequest request) {
        try {
            boolean success = serviceDiscovery.registerNode(
                request.getNodeId(), 
                request.getHost(), 
                request.getPort(), 
                request.getNodeType()
            );
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", success);
            response.put("message", success ? "Node registered successfully" : "Failed to register node");
            response.put("nodeId", request.getNodeId());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }
    
    @DeleteMapping("/nodes/{nodeId}")
    public ResponseEntity<Map<String, Object>> deregisterNode(@PathVariable String nodeId) {
        boolean success = serviceDiscovery.deregisterNode(nodeId);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", success);
        response.put("message", success ? "Node deregistered successfully" : "Node not found");
        response.put("nodeId", nodeId);
        
        return ResponseEntity.ok(response);
    }
    
    // --- Health Management Endpoints ---
    
    @PostMapping("/nodes/{nodeId}/health")
    public ResponseEntity<Map<String, Object>> updateNodeHealth(
            @PathVariable String nodeId, 
            @RequestBody HealthUpdateRequest request) {
        
        boolean success = serviceDiscovery.updateNodeHealth(nodeId, request.isHealthy());
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", success);
        response.put("message", success ? "Health updated successfully" : "Node not found");
        response.put("nodeId", nodeId);
        response.put("healthy", request.isHealthy());
        
        return ResponseEntity.ok(response);
    }
    
    @PostMapping("/nodes/{nodeId}/heartbeat")
    public ResponseEntity<Map<String, Object>> heartbeat(@PathVariable String nodeId) {
        boolean success = serviceDiscovery.heartbeat(nodeId);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", success);
        response.put("message", success ? "Heartbeat received" : "Node not found");
        response.put("nodeId", nodeId);
        
        return ResponseEntity.ok(response);
    }
    
    // --- Discovery Endpoints ---
    
    @GetMapping("/nodes")
    public ResponseEntity<List<ServiceDiscovery.ServiceNode>> getAllNodes() {
        return ResponseEntity.ok(serviceDiscovery.getAllNodes());
    }
    
    @GetMapping("/nodes/{nodeId}")
    public ResponseEntity<ServiceDiscovery.ServiceNode> getNode(@PathVariable String nodeId) {
        ServiceDiscovery.ServiceNode node = serviceDiscovery.getNode(nodeId);
        if (node != null) {
            return ResponseEntity.ok(node);
        } else {
            return ResponseEntity.notFound().build();
        }
    }
    
    @GetMapping("/nodes/healthy")
    public ResponseEntity<List<ServiceDiscovery.ServiceNode>> getHealthyNodes() {
        return ResponseEntity.ok(serviceDiscovery.getHealthyNodes());
    }
    
    @GetMapping("/nodes/type/{nodeType}")
    public ResponseEntity<List<ServiceDiscovery.ServiceNode>> getNodesByType(@PathVariable String nodeType) {
        return ResponseEntity.ok(serviceDiscovery.getHealthyNodesByType(nodeType));
    }
    
    @GetMapping("/nodes/type/{nodeType}/cache")
    public ResponseEntity<List<Map<String, Object>>> getCacheNodes(@PathVariable String nodeType) {
        List<ServiceDiscovery.ServiceNode> nodes = serviceDiscovery.getHealthyNodesByType(nodeType);
        List<Map<String, Object>> cacheNodes = nodes.stream()
            .map(node -> {
                Map<String, Object> cacheNode = new HashMap<>();
                cacheNode.put("id", node.getNodeId());
                cacheNode.put("host", node.getHost());
                cacheNode.put("port", node.getPort());
                cacheNode.put("type", node.getNodeType());
                cacheNode.put("healthy", node.isHealthy());
                return cacheNode;
            })
            .collect(java.util.stream.Collectors.toList());
        
        return ResponseEntity.ok(cacheNodes);
    }
    
    // --- Statistics Endpoints ---
    
    @GetMapping("/stats")
    public ResponseEntity<ServiceDiscovery.ServiceDiscoveryStats> getStats() {
        return ResponseEntity.ok(serviceDiscovery.getStats());
    }
    
    @GetMapping("/ping")
    public ResponseEntity<Map<String, Object>> ping() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "ok");
        response.put("service", "ServiceDiscoveryAPI");
        response.put("timestamp", java.time.Instant.now().toString());
        return ResponseEntity.ok(response);
    }
    
    // --- Request/Response Classes ---
    
    public static class NodeRegistrationRequest {
        private String nodeId;
        private String host;
        private int port;
        private String nodeType;
        
        // Default constructor for JSON deserialization
        public NodeRegistrationRequest() {}
        
        public NodeRegistrationRequest(String nodeId, String host, int port, String nodeType) {
            this.nodeId = nodeId;
            this.host = host;
            this.port = port;
            this.nodeType = nodeType;
        }
        
        // Getters and setters
        public String getNodeId() { return nodeId; }
        public void setNodeId(String nodeId) { this.nodeId = nodeId; }
        
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        
        public String getNodeType() { return nodeType; }
        public void setNodeType(String nodeType) { this.nodeType = nodeType; }
    }
    
    public static class HealthUpdateRequest {
        private boolean healthy;
        
        // Default constructor for JSON deserialization
        public HealthUpdateRequest() {}
        
        public HealthUpdateRequest(boolean healthy) {
            this.healthy = healthy;
        }
        
        // Getters and setters
        public boolean isHealthy() { return healthy; }
        public void setHealthy(boolean healthy) { this.healthy = healthy; }
    }
} 