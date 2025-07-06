package com.fastcache.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Health checker for FastCache nodes and proxies.
 */
public class HealthChecker {
    private static final Logger logger = LoggerFactory.getLogger(HealthChecker.class);
    
    private final ScheduledExecutorService executor;
    private final Duration checkInterval;
    private final Duration timeout;
    private final Consumer<HealthCheck> healthCallback;
    
    public HealthChecker(Duration checkInterval, Duration timeout, Consumer<HealthCheck> healthCallback) {
        this.checkInterval = checkInterval;
        this.timeout = timeout;
        this.healthCallback = healthCallback;
        this.executor = Executors.newScheduledThreadPool(4, r -> {
            Thread t = new Thread(r, "health-checker");
            t.setDaemon(true);
            return t;
        });
    }
    
    public HealthChecker(Consumer<HealthCheck> healthCallback) {
        this(Duration.ofSeconds(30), Duration.ofSeconds(5), healthCallback);
    }
    
    /**
     * Start periodic health checks for a node.
     */
    public void startHealthCheck(String nodeId, String nodeType, String host, int port) {
        logger.info("Starting health checks for {} {}:{}:{}", nodeType, nodeId, host, port);
        
        executor.scheduleAtFixedRate(() -> {
            try {
                HealthCheck healthCheck = performHealthCheck(nodeId, nodeType, host, port);
                healthCallback.accept(healthCheck);
            } catch (Exception e) {
                logger.error("Health check failed for {} {}:{}:{}", nodeType, nodeId, host, port, e);
                HealthCheck failedCheck = new HealthCheck(nodeId, nodeType, host, port, 
                    HealthCheck.Status.UNHEALTHY, e.getMessage());
                healthCallback.accept(failedCheck);
            }
        }, 0, checkInterval.toMillis(), TimeUnit.MILLISECONDS);
    }
    
    /**
     * Perform a single health check.
     */
    public HealthCheck performHealthCheck(String nodeId, String nodeType, String host, int port) {
        Instant start = Instant.now();
        
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), (int) timeout.toMillis());
            
            Duration responseTime = Duration.between(start, Instant.now());
            HealthCheck healthCheck = new HealthCheck(nodeId, nodeType, host, port, HealthCheck.Status.HEALTHY);
            healthCheck.addMetric("responseTimeMs", responseTime.toMillis());
            healthCheck.addMetric("timestamp", start.toEpochMilli());
            
            logger.debug("Health check passed for {} {}:{}:{} in {}ms", 
                nodeType, nodeId, host, port, responseTime.toMillis());
            
            return healthCheck;
            
        } catch (IOException e) {
            Duration responseTime = Duration.between(start, Instant.now());
            HealthCheck healthCheck = new HealthCheck(nodeId, nodeType, host, port, 
                HealthCheck.Status.UNHEALTHY, e.getMessage());
            healthCheck.addMetric("responseTimeMs", responseTime.toMillis());
            healthCheck.addMetric("timestamp", start.toEpochMilli());
            
            logger.debug("Health check failed for {} {}:{}:{} after {}ms: {}", 
                nodeType, nodeId, host, port, responseTime.toMillis(), e.getMessage());
            
            return healthCheck;
        }
    }
    
    /**
     * Perform a health check asynchronously.
     */
    public CompletableFuture<HealthCheck> performHealthCheckAsync(String nodeId, String nodeType, 
                                                                 String host, int port) {
        return CompletableFuture.supplyAsync(() -> 
            performHealthCheck(nodeId, nodeType, host, port), executor);
    }
    
    /**
     * Stop the health checker.
     */
    public void shutdown() {
        logger.info("Shutting down health checker");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Get the check interval.
     */
    public Duration getCheckInterval() {
        return checkInterval;
    }
    
    /**
     * Get the timeout duration.
     */
    public Duration getTimeout() {
        return timeout;
    }
} 