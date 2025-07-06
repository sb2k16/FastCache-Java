package com.fastcache.discovery;

import com.fastcache.cluster.CacheNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Client for the Service Discovery API that allows dynamic node discovery.
 * This client can be used by proxies to automatically discover and track cache nodes.
 */
public class ServiceDiscoveryClient {
    
    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService scheduler;
    private final int refreshIntervalMs;
    
    private volatile List<CacheNode> cachedNodes = List.of();
    private volatile long lastRefresh = 0;
    
    public ServiceDiscoveryClient(String baseUrl) {
        this(baseUrl, 5000); // 5 second refresh interval
    }
    
    public ServiceDiscoveryClient(String baseUrl, int refreshIntervalMs) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.refreshIntervalMs = refreshIntervalMs;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        this.objectMapper = new ObjectMapper();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "service-discovery-client");
            t.setDaemon(true);
            return t;
        });
        
        System.out.println("ServiceDiscoveryClient initialized with baseUrl=" + baseUrl + 
            ", refreshInterval=" + refreshIntervalMs + "ms");
    }
    
    /**
     * Starts periodic refresh of node information.
     */
    public void startPeriodicRefresh() {
        scheduler.scheduleAtFixedRate(this::refreshNodes, 0, refreshIntervalMs, TimeUnit.MILLISECONDS);
        System.out.println("Started periodic node refresh every " + refreshIntervalMs + "ms");
    }
    
    /**
     * Stops periodic refresh.
     */
    public void stopPeriodicRefresh() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        System.out.println("Stopped periodic node refresh");
    }
    
    /**
     * Manually refreshes the cached node list.
     */
    public void refreshNodes() {
        try {
            List<CacheNode> nodes = getCacheNodes("CACHE");
            this.cachedNodes = nodes;
            this.lastRefresh = System.currentTimeMillis();
            System.out.println("Refreshed node list: " + nodes.size() + " nodes");
        } catch (Exception e) {
            System.err.println("Failed to refresh nodes: " + e.getMessage());
        }
    }
    
    /**
     * Gets the currently cached list of cache nodes.
     * @return List of cache nodes
     */
    public List<CacheNode> getCachedNodes() {
        return new ArrayList<>(cachedNodes);
    }
    
    /**
     * Gets the timestamp of the last refresh.
     * @return Last refresh timestamp
     */
    public long getLastRefresh() {
        return lastRefresh;
    }
    
    /**
     * Registers a node with the service discovery.
     * @param nodeId Node identifier
     * @param host Host address
     * @param port Port number
     * @param nodeType Node type
     * @return CompletableFuture with registration result
     */
    public CompletableFuture<Boolean> registerNode(String nodeId, String host, int port, String nodeType) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String requestBody = objectMapper.writeValueAsString(
                    new ServiceDiscoveryAPI.NodeRegistrationRequest(nodeId, host, port, nodeType)
                );
                
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/discovery/nodes"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
                
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() == 200) {
                    var result = objectMapper.readValue(response.body(), 
                        new TypeReference<Map<String, Object>>() {});
                    return Boolean.TRUE.equals(result.get("success"));
                }
                return false;
            } catch (Exception e) {
                System.err.println("Failed to register node: " + e.getMessage());
                return false;
            }
        });
    }
    
    /**
     * Deregisters a node from service discovery.
     * @param nodeId Node identifier
     * @return CompletableFuture with deregistration result
     */
    public CompletableFuture<Boolean> deregisterNode(String nodeId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/discovery/nodes/" + nodeId))
                    .DELETE()
                    .build();
                
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() == 200) {
                    var result = objectMapper.readValue(response.body(), 
                        new TypeReference<Map<String, Object>>() {});
                    return Boolean.TRUE.equals(result.get("success"));
                }
                return false;
            } catch (Exception e) {
                System.err.println("Failed to deregister node: " + e.getMessage());
                return false;
            }
        });
    }
    
    /**
     * Sends a heartbeat for a node.
     * @param nodeId Node identifier
     * @return CompletableFuture with heartbeat result
     */
    public CompletableFuture<Boolean> heartbeat(String nodeId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/discovery/nodes/" + nodeId + "/heartbeat"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
                
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() == 200) {
                    var result = objectMapper.readValue(response.body(), 
                        new TypeReference<Map<String, Object>>() {});
                    return Boolean.TRUE.equals(result.get("success"));
                }
                return false;
            } catch (Exception e) {
                System.err.println("Failed to send heartbeat: " + e.getMessage());
                return false;
            }
        });
    }
    
    /**
     * Gets all cache nodes of a specific type.
     * @param nodeType Node type to retrieve
     * @return List of cache nodes
     */
    public List<CacheNode> getCacheNodes(String nodeType) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/discovery/nodes/type/" + nodeType + "/cache"))
            .GET()
            .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() == 200) {
            List<Map<String, Object>> nodeData = objectMapper.readValue(response.body(), 
                new TypeReference<List<Map<String, Object>>>() {});
            
            return nodeData.stream()
                .map(data -> new CacheNode(
                    (String) data.get("id"),
                    (String) data.get("host"),
                    ((Number) data.get("port")).intValue()
                ))
                .collect(java.util.stream.Collectors.toList());
        }
        
        throw new IOException("Failed to get cache nodes: HTTP " + response.statusCode());
    }
    
    /**
     * Gets service discovery statistics.
     * @return ServiceDiscoveryStats
     */
    public ServiceDiscovery.ServiceDiscoveryStats getStats() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/discovery/stats"))
            .GET()
            .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() == 200) {
            return objectMapper.readValue(response.body(), ServiceDiscovery.ServiceDiscoveryStats.class);
        }
        
        throw new IOException("Failed to get stats: HTTP " + response.statusCode());
    }
    
    /**
     * Checks if the service discovery API is available.
     * @return true if available, false otherwise
     */
    public boolean isAvailable() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/discovery/ping"))
                .GET()
                .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Shuts down the client.
     */
    public void shutdown() {
        stopPeriodicRefresh();
        System.out.println("ServiceDiscoveryClient shutdown complete");
    }
} 