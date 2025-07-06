package com.fastcache.core;

import java.io.IOException;

public class FastCache {
    private final CacheEngine engine;

    private FastCache(CacheEngine engine) {
        this.engine = engine;
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean set(String key, Object value, long ttlSeconds, CacheEntry.EntryType type) {
        return engine.set(key, value, ttlSeconds, type);
    }

    public boolean set(String key, Object value, CacheEntry.EntryType type) {
        return engine.set(key, value, type);
    }

    public Object get(String key) {
        return engine.get(key);
    }

    public boolean delete(String key) {
        return engine.delete(key);
    }

    public boolean exists(String key) {
        return engine.exists(key);
    }

    public long ttl(String key) {
        return engine.ttl(key);
    }

    public boolean expire(String key, long ttlSeconds) {
        return engine.expire(key, ttlSeconds);
    }

    public void flush() {
        engine.flush();
    }

    public CacheEngine.CacheStats getStats() {
        return engine.getStats();
    }

    public void shutdown() {
        engine.shutdown();
    }

    public static class Builder {
        private boolean persistenceEnabled = false;
        private String dataDir = "/app/data";
        private int maxSize = 10000;
        private EvictionPolicy evictionPolicy = new EvictionPolicy.LRU();

        public Builder enablePersistence(boolean enabled) {
            this.persistenceEnabled = enabled;
            return this;
        }

        public Builder dataDir(String dir) {
            this.dataDir = dir;
            return this;
        }

        public Builder maxSize(int size) {
            this.maxSize = size;
            return this;
        }

        public Builder evictionPolicy(EvictionPolicy policy) {
            this.evictionPolicy = policy;
            return this;
        }

        public FastCache build() {
            try {
                if (persistenceEnabled) {
                    return new FastCache(new PersistentCacheEngine(dataDir, "default", maxSize, evictionPolicy));
                } else {
                    return new FastCache(new CacheEngine(maxSize, evictionPolicy));
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to initialize cache engine", e);
            }
        }
    }
} 