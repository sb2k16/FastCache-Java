package com.fastcache.benchmark;

import com.fastcache.core.CacheEngine;
import com.fastcache.core.EvictionPolicy;
import com.fastcache.core.CacheEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple performance benchmark tests for FastCache.
 */
@DisplayName("Simple Performance Benchmark Tests")
public class SimpleBenchmarkTest {

    private CacheEngine cache;
    private ExecutorService executor;
    private Random random;
    
    @BeforeEach
    void setUp() {
        cache = new CacheEngine(100000, new EvictionPolicy.LRU());
        executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        random = new Random(42); // Fixed seed for reproducible results
    }
    
    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.shutdown();
        }
        if (executor != null) {
            executor.shutdown();
        }
    }
    
    @Test
    @DisplayName("Benchmark single-threaded operations")
    void benchmarkSingleThreadedOperations() {
        int iterations = 100000;
        
        // Benchmark set operations
        long setStartTime = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            cache.set("key_" + i, "value_" + i, CacheEntry.EntryType.STRING);
        }
        long setEndTime = System.nanoTime();
        
        // Benchmark get operations
        long getStartTime = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            cache.get("key_" + i);
        }
        long getEndTime = System.nanoTime();
        
        // Calculate metrics
        double setDuration = (setEndTime - setStartTime) / 1_000_000_000.0;
        double getDuration = (getEndTime - getStartTime) / 1_000_000_000.0;
        double setThroughput = iterations / setDuration;
        double getThroughput = iterations / getDuration;
        
        System.out.printf("Single-threaded benchmark results:%n");
        System.out.printf("  Set operations: %,d in %.2f seconds (%.2f ops/sec)%n", 
                         iterations, setDuration, setThroughput);
        System.out.printf("  Get operations: %,d in %.2f seconds (%.2f ops/sec)%n", 
                         iterations, getDuration, getThroughput);
        
        // Verify data integrity
        for (int i = 0; i < iterations; i++) {
            assertEquals("value_" + i, cache.get("key_" + i));
        }
    }
    
    @Test
    @DisplayName("Benchmark multi-threaded operations")
    void benchmarkMultiThreadedOperations() throws InterruptedException {
        int threads = Runtime.getRuntime().availableProcessors();
        int operationsPerThread = 10000;
        int totalOperations = threads * operationsPerThread;
        
        AtomicInteger completedOperations = new AtomicInteger(0);
        
        long startTime = System.nanoTime();
        
        CompletableFuture<?>[] futures = new CompletableFuture[threads];
        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            futures[t] = CompletableFuture.runAsync(() -> {
                for (int i = 0; i < operationsPerThread; i++) {
                    String key = "key_" + threadId + "_" + i;
                    String value = "value_" + threadId + "_" + i;
                    cache.set(key, value, CacheEntry.EntryType.STRING);
                    cache.get(key);
                    completedOperations.incrementAndGet();
                }
            }, executor);
        }
        
        CompletableFuture.allOf(futures).join();
        
        long endTime = System.nanoTime();
        double duration = (endTime - startTime) / 1_000_000_000.0;
        double throughput = totalOperations / duration;
        
        System.out.printf("Multi-threaded benchmark results:%n");
        System.out.printf("  Threads: %d%n", threads);
        System.out.printf("  Total operations: %,d%n", totalOperations);
        System.out.printf("  Completed operations: %,d%n", completedOperations.get());
        System.out.printf("  Duration: %.2f seconds%n", duration);
        System.out.printf("  Throughput: %.2f ops/sec%n", throughput);
        
        assertEquals(totalOperations, completedOperations.get());
    }
    
    @Test
    @DisplayName("Benchmark eviction policies")
    void benchmarkEvictionPolicies() {
        int maxSize = 10000;
        int operations = 50000;
        
        EvictionPolicy[] policies = {
            new EvictionPolicy.LRU(),
            new EvictionPolicy.LFU(),
            new EvictionPolicy.Random()
        };
        
        for (EvictionPolicy policy : policies) {
            CacheEngine testCache = new CacheEngine(maxSize, policy);
            
            long startTime = System.nanoTime();
            
            for (int i = 0; i < operations; i++) {
                testCache.set("key_" + i, "value_" + i, CacheEntry.EntryType.STRING);
                
                // Access some keys to test policy behavior
                if (i % 10 == 0) {
                    testCache.get("key_" + (i - 5));
                }
            }
            
            long endTime = System.nanoTime();
            double duration = (endTime - startTime) / 1_000_000_000.0;
            double throughput = operations / duration;
            
            System.out.printf("Eviction policy benchmark (%s):%n", policy.getClass().getSimpleName());
            System.out.printf("  Operations: %,d%n", operations);
            System.out.printf("  Final size: %,d%n", testCache.size());
            System.out.printf("  Duration: %.2f seconds%n", duration);
            System.out.printf("  Throughput: %.2f ops/sec%n", throughput);
            
            testCache.shutdown();
        }
    }
    
    @Test
    @DisplayName("Benchmark memory usage")
    void benchmarkMemoryUsage() {
        int iterations = 100000;
        
        Runtime runtime = Runtime.getRuntime();
        System.gc();
        long initialMemory = runtime.totalMemory() - runtime.freeMemory();
        
        // Add data to cache
        for (int i = 0; i < iterations; i++) {
            cache.set("key_" + i, "value_" + i + "_with_additional_data", CacheEntry.EntryType.STRING);
        }
        
        System.gc();
        long finalMemory = runtime.totalMemory() - runtime.freeMemory();
        long memoryUsed = finalMemory - initialMemory;
        
        System.out.printf("Memory usage benchmark:%n");
        System.out.printf("  Entries: %,d%n", iterations);
        System.out.printf("  Memory used: %,d bytes%n", memoryUsed);
        System.out.printf("  Memory per entry: %.2f bytes%n", (double) memoryUsed / iterations);
        System.out.printf("  Cache size: %,d%n", cache.size());
        
        // Verify data integrity
        for (int i = 0; i < iterations; i++) {
            assertEquals("value_" + i + "_with_additional_data", cache.get("key_" + i));
        }
    }
} 