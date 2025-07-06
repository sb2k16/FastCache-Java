package com.fastcache.proxy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

/**
 * Client for proxies to query the centralized health checker service.
 */
public class HealthServiceClient {
    private final String healthServiceUrl;
    private final HttpClient httpClient;

    public HealthServiceClient(String healthServiceUrl) {
        this.healthServiceUrl = healthServiceUrl;
        this.httpClient = HttpClient.newHttpClient();
    }

    /**
     * Checks if a node is healthy by querying the health checker REST API.
     * @param nodeId The node ID (e.g., node1)
     * @return CompletableFuture<Boolean> indicating health
     */
    public CompletableFuture<Boolean> isNodeHealthy(String nodeId) {
        String url = String.format("%s/health/nodes/%s", healthServiceUrl, nodeId);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200 && response.body().contains("\"healthy\":true")) {
                        return true;
                    } else {
                        return false;
                    }
                })
                .exceptionally(ex -> false);
    }
} 