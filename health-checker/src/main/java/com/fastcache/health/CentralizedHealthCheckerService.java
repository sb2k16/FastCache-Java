package com.fastcache.health;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@SpringBootApplication
@RestController
@RequestMapping("/health")
@EnableScheduling
public class CentralizedHealthCheckerService {
    private static final int DEFAULT_PORT = 6379;
    private static final int CHECK_TIMEOUT_MS = 2000;
    private static final int CHECK_INTERVAL_MS = 30000;

    // NodeId -> HealthCheck
    private final Map<String, HealthCheck> nodeHealth = new ConcurrentHashMap<>();
    // NodeId -> host:port
    private final Map<String, String> nodeAddresses = new ConcurrentHashMap<>();

    // Initialize with configurable number of nodes
    public CentralizedHealthCheckerService() {
        System.out.println("Initializing CentralizedHealthCheckerService...");
        String nodeCountStr = System.getenv("NODE_COUNT");
        int nodeCount = nodeCountStr != null ? Integer.parseInt(nodeCountStr) : 3; // Default to 3 nodes
        
        for (int i = 1; i <= nodeCount; i++) {
            String nodeId = "node" + i;
            String host = "fastcache-node" + i;
            int port = 6379;
            nodeAddresses.put(nodeId, host + ":" + port);
            nodeHealth.put(nodeId, new HealthCheck(nodeId, host, port, false, Instant.now(), "INIT"));
        }
        System.out.println("Initialized " + nodeHealth.size() + " nodes for health checking");
    }

    @Bean
    public ServletWebServerFactory servletContainer() {
        System.out.println("Creating Tomcat web server factory on port 8080");
        TomcatServletWebServerFactory tomcat = new TomcatServletWebServerFactory();
        tomcat.setPort(8080);
        try {
            tomcat.setAddress(InetAddress.getByName("0.0.0.0"));
        } catch (Exception e) {
            System.out.println("Error setting address: " + e.getMessage());
        }
        return tomcat;
    }

    public static void main(String[] args) {
        System.out.println("Starting CentralizedHealthCheckerService...");
        SpringApplication app = new SpringApplication(CentralizedHealthCheckerService.class);
        app.setWebApplicationType(WebApplicationType.SERVLET);
        System.out.println("Web application type set to SERVLET");
        
        // Add explicit logging for web server startup
        app.addListeners(event -> {
            if (event instanceof org.springframework.boot.context.event.ApplicationStartedEvent) {
                System.out.println("Application started event received");
            } else if (event instanceof org.springframework.boot.context.event.ApplicationReadyEvent) {
                System.out.println("Application ready event received - web server should be running");
            }
        });
        
        app.run(args);
        System.out.println("Health checker service started successfully on port 8080");
        
        // Test if web server is actually running
        new Thread(() -> {
            try {
                Thread.sleep(5000); // Wait 5 seconds for web server to start
                Socket socket = new Socket("localhost", 8080);
                System.out.println("SUCCESS: Web server is listening on port 8080");
                socket.close();
            } catch (Exception e) {
                System.out.println("ERROR: Web server is NOT listening on port 8080: " + e.getMessage());
            }
        }).start();
    }

    @GetMapping("/test")
    public ResponseEntity<String> test() {
        System.out.println("Test endpoint called");
        return ResponseEntity.ok("Health checker is working!");
    }

    // Scheduled health check for all nodes
    @Scheduled(fixedRate = CHECK_INTERVAL_MS)
    public void checkAllNodes() {
        for (Map.Entry<String, String> entry : nodeAddresses.entrySet()) {
            String nodeId = entry.getKey();
            String[] parts = entry.getValue().split(":");
            String host = parts[0];
            int port = Integer.parseInt(parts[1]);
            boolean healthy = isNodeUp(host, port);
            String status = healthy ? "HEALTHY" : "UNHEALTHY";
            nodeHealth.put(nodeId, new HealthCheck(nodeId, host, port, healthy, Instant.now(), status));
        }
    }

    private boolean isNodeUp(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CHECK_TIMEOUT_MS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // --- REST API ---

    @GetMapping("/ping")
    public ResponseEntity<Map<String, Object>> ping() {
        Map<String, Object> resp = new HashMap<>();
        resp.put("status", "ok");
        resp.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/nodes")
    public ResponseEntity<List<HealthCheck>> getAllNodeHealth() {
        return ResponseEntity.ok(new ArrayList<>(nodeHealth.values()));
    }

    @GetMapping("/nodes/{nodeId}")
    public ResponseEntity<HealthCheck> getNodeHealth(@PathVariable String nodeId) {
        HealthCheck health = nodeHealth.get(nodeId);
        if (health != null) {
            return ResponseEntity.ok(health);
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/healthy")
    public ResponseEntity<List<HealthCheck>> getHealthyNodes() {
        List<HealthCheck> healthy = new ArrayList<>();
        for (HealthCheck h : nodeHealth.values()) {
            if (h.healthy) healthy.add(h);
        }
        return ResponseEntity.ok(healthy);
    }

    @GetMapping("/unhealthy")
    public ResponseEntity<List<HealthCheck>> getUnhealthyNodes() {
        List<HealthCheck> unhealthy = new ArrayList<>();
        for (HealthCheck h : nodeHealth.values()) {
            if (!h.healthy) unhealthy.add(h);
        }
        return ResponseEntity.ok(unhealthy);
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getClusterHealthSummary() {
        int total = nodeHealth.size();
        int healthy = 0;
        int unhealthy = 0;
        for (HealthCheck h : nodeHealth.values()) {
            if (h.healthy) healthy++; else unhealthy++;
        }
        Map<String, Object> summary = new HashMap<>();
        summary.put("total", total);
        summary.put("healthy", healthy);
        summary.put("unhealthy", unhealthy);
        summary.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(summary);
    }

    @PostMapping("/nodes/{nodeId}/check")
    public ResponseEntity<HealthCheck> forceHealthCheck(@PathVariable String nodeId) {
        String addr = nodeAddresses.get(nodeId);
        if (addr == null) return ResponseEntity.notFound().build();
        String[] parts = addr.split(":");
        String host = parts[0];
        int port = Integer.parseInt(parts[1]);
        boolean healthy = isNodeUp(host, port);
        String status = healthy ? "HEALTHY" : "UNHEALTHY";
        HealthCheck hc = new HealthCheck(nodeId, host, port, healthy, Instant.now(), status);
        nodeHealth.put(nodeId, hc);
        return ResponseEntity.ok(hc);
    }

    @PostMapping("/shutdown")
    public ResponseEntity<Map<String, String>> shutdown() {
        new Thread(() -> {
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            System.exit(0);
        }).start();
        Map<String, String> resp = new HashMap<>();
        resp.put("status", "shutting down");
        return ResponseEntity.ok(resp);
    }

    // --- Data class ---
    public static class HealthCheck {
        public final String nodeId;
        public final String host;
        public final int port;
        public final boolean healthy;
        public final Instant lastChecked;
        public final String status;
        public HealthCheck(String nodeId, String host, int port, boolean healthy, Instant lastChecked, String status) {
            this.nodeId = nodeId;
            this.host = host;
            this.port = port;
            this.healthy = healthy;
            this.lastChecked = lastChecked;
            this.status = status;
        }
    }
} 