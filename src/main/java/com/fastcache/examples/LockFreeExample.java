package com.fastcache.examples;

import com.fastcache.core.PartitionedCacheEngine;
import com.fastcache.core.CacheEntry;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Example demonstrating the lock-free partitioned cache engine.
 * Shows how thread partitioning eliminates the need for explicit locks.
 */
public class LockFreeExample {
    
    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Lock-Free FastCache Example ===\n");
        
        // Create partitioned cache engine with 8 partitions
        PartitionedCacheEngine cache = new PartitionedCacheEngine(8);
        
        // Test concurrent access without locks
        testConcurrentAccess(cache);
        
        // Test partition distribution
        testPartitionDistribution(cache);
        
        // Test performance comparison
        testPerformance(cache);
        
        cache.shutdown();
        System.out.println("\n=== Example Complete ===");
    }
    
    /**
     * Tests concurrent access to demonstrate lock-free operation.
     */
    private static void testConcurrentAccess(PartitionedCacheEngine cache) throws InterruptedException {
        System.out.println("1. Testing Concurrent Access (Lock-Free)");
        System.out.println("========================================");
        
        int numThreads = 16;
        int operationsPerThread = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        
        AtomicLong totalOperations = new AtomicLong(0);
        AtomicLong successfulOperations = new AtomicLong(0);
        
        long startTime = System.currentTimeMillis();
        
        // Submit concurrent tasks
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                for (int j = 0; j < operationsPerThread; j++) {
                    String key = "thread-" + threadId + "-key-" + j;
                    String value = "value-" + threadId + "-" + j;
                    
                    // Set operation
                    boolean setResult = cache.set(key, value, CacheEntry.EntryType.STRING);
                    if (setResult) {
                        successfulOperations.incrementAndGet();
                    }
                    
                    // Get operation
                    Object retrieved = cache.get(key);
                    if (retrieved != null) {
                        successfulOperations.incrementAndGet();
                    }
                    
                    totalOperations.addAndGet(2); // Set + Get
                }
            });
        }
        
        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);
        
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        
        System.out.printf("Threads: %d\n", numThreads);
        System.out.printf("Operations per thread: %d\n", operationsPerThread);
        System.out.printf("Total operations: %,d\n", totalOperations.get());
        System.out.printf("Successful operations: %,d\n", successfulOperations.get());
        System.out.printf("Duration: %d ms\n", duration);
        System.out.printf("Throughput: %,d ops/sec\n", (totalOperations.get() * 1000) / duration);
        System.out.printf("Success rate: %.2f%%\n", 
                         (double) successfulOperations.get() / totalOperations.get() * 100);
        
        // Show cache stats
        var stats = cache.getStats();
        System.out.printf("Cache size: %d entries\n", stats.getSize());
        System.out.printf("Hit rate: %.2f%%\n", stats.getHitRate() * 100);
        System.out.println();
    }
    
    /**
     * Tests partition distribution to ensure even load distribution.
     */
    private static void testPartitionDistribution(PartitionedCacheEngine cache) {
        System.out.println("2. Testing Partition Distribution");
        System.out.println("=================================");
        
        // Load some test data
        for (int i = 0; i < 1000; i++) {
            String key = "test-key-" + i;
            String value = "test-value-" + i;
            cache.set(key, value, CacheEntry.EntryType.STRING);
        }
        
        // Get distribution stats
        var distribution = cache.getPartitionDistribution();
        
        System.out.printf("Number of partitions: %d\n", distribution.getNumPartitions());
        System.out.printf("Min partition size: %d\n", distribution.getMinSize());
        System.out.printf("Max partition size: %d\n", distribution.getMaxSize());
        System.out.printf("Average partition size: %.2f\n", distribution.getAvgSize());
        System.out.printf("Distribution variance: %.2f\n", distribution.getDistributionVariance());
        System.out.printf("Balance ratio: %.2f\n", distribution.getBalanceRatio());
        
        // Evaluate distribution quality
        double balanceRatio = distribution.getBalanceRatio();
        if (balanceRatio > 0.8) {
            System.out.println("✓ Excellent distribution balance");
        } else if (balanceRatio > 0.6) {
            System.out.println("✓ Good distribution balance");
        } else if (balanceRatio > 0.4) {
            System.out.println("⚠ Fair distribution balance");
        } else {
            System.out.println("✗ Poor distribution balance");
        }
        System.out.println();
    }
    
    /**
     * Tests performance characteristics.
     */
    private static void testPerformance(PartitionedCacheEngine cache) {
        System.out.println("3. Performance Characteristics");
        System.out.println("=============================");
        
        int numOperations = 100000;
        
        // Test write performance
        long writeStart = System.nanoTime();
        for (int i = 0; i < numOperations; i++) {
            cache.set("perf-key-" + i, "perf-value-" + i, CacheEntry.EntryType.STRING);
        }
        long writeEnd = System.nanoTime();
        long writeDuration = writeEnd - writeStart;
        
        // Test read performance
        long readStart = System.nanoTime();
        for (int i = 0; i < numOperations; i++) {
            cache.get("perf-key-" + i);
        }
        long readEnd = System.nanoTime();
        long readDuration = readEnd - readStart;
        
        // Calculate metrics
        double writeThroughput = (double) numOperations / (writeDuration / 1_000_000_000.0);
        double readThroughput = (double) numOperations / (readDuration / 1_000_000_000.0);
        double avgWriteLatency = (double) writeDuration / numOperations / 1_000_000.0; // microseconds
        double avgReadLatency = (double) readDuration / numOperations / 1_000_000.0; // microseconds
        
        System.out.printf("Write operations: %,d\n", numOperations);
        System.out.printf("Write throughput: %.0f ops/sec\n", writeThroughput);
        System.out.printf("Average write latency: %.2f μs\n", avgWriteLatency);
        System.out.println();
        
        System.out.printf("Read operations: %,d\n", numOperations);
        System.out.printf("Read throughput: %.0f ops/sec\n", readThroughput);
        System.out.printf("Average read latency: %.2f μs\n", avgReadLatency);
        System.out.println();
        
        // Performance assessment
        System.out.println("Performance Assessment:");
        if (writeThroughput > 100000) {
            System.out.println("✓ Excellent write performance");
        } else if (writeThroughput > 50000) {
            System.out.println("✓ Good write performance");
        } else {
            System.out.println("⚠ Moderate write performance");
        }
        
        if (readThroughput > 200000) {
            System.out.println("✓ Excellent read performance");
        } else if (readThroughput > 100000) {
            System.out.println("✓ Good read performance");
        } else {
            System.out.println("⚠ Moderate read performance");
        }
        
        if (avgReadLatency < 10) {
            System.out.println("✓ Excellent read latency");
        } else if (avgReadLatency < 50) {
            System.out.println("✓ Good read latency");
        } else {
            System.out.println("⚠ Moderate read latency");
        }
        System.out.println();
    }
    
    /**
     * Demonstrates the key benefits of the lock-free approach.
     */
    private static void demonstrateBenefits() {
        System.out.println("4. Lock-Free Benefits");
        System.out.println("=====================");
        System.out.println("✓ No lock contention between threads");
        System.out.println("✓ Predictable latency (no lock waiting)");
        System.out.println("✓ Linear scalability with CPU cores");
        System.out.println("✓ Better CPU cache utilization");
        System.out.println("✓ Reduced context switching");
        System.out.println("✓ No deadlock risk");
        System.out.println("✓ Simplified debugging and profiling");
        System.out.println();
    }
} 