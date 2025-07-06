package com.fastcache.core;

import java.time.Duration;

/**
 * Configuration class for persistence settings.
 */
public class PersistenceConfig {
    private boolean enablePersistence = false;
    private boolean enableWriteAheadLog = true;
    private boolean enableSnapshots = true;
    private boolean enableIncrementalBackup = false;
    
    private Duration snapshotInterval = Duration.ofMinutes(5);
    private Duration walFlushInterval = Duration.ofSeconds(1);
    private String persistenceDir = "/app/data";
    private long maxSnapshotSize = 1024 * 1024 * 1024; // 1GB
    
    // Recovery settings
    private boolean enableCrashRecovery = true;
    private boolean enableNodeRecovery = true;
    private Duration recoveryTimeout = Duration.ofMinutes(10);
    
    // Compression settings
    private boolean enableCompression = false;
    private String compressionAlgorithm = "gzip";
    
    // Encryption settings
    private boolean enableEncryption = false;
    private String encryptionKey = null;
    
    public PersistenceConfig() {}
    
    public PersistenceConfig(boolean enablePersistence) {
        this.enablePersistence = enablePersistence;
    }
    
    // Builder pattern for easy configuration
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private PersistenceConfig config = new PersistenceConfig();
        
        public Builder enablePersistence(boolean enable) {
            config.enablePersistence = enable;
            return this;
        }
        
        public Builder enableWriteAheadLog(boolean enable) {
            config.enableWriteAheadLog = enable;
            return this;
        }
        
        public Builder enableSnapshots(boolean enable) {
            config.enableSnapshots = enable;
            return this;
        }
        
        public Builder snapshotInterval(Duration interval) {
            config.snapshotInterval = interval;
            return this;
        }
        
        public Builder persistenceDir(String dir) {
            config.persistenceDir = dir;
            return this;
        }
        
        public Builder maxSnapshotSize(long size) {
            config.maxSnapshotSize = size;
            return this;
        }
        
        public Builder enableCompression(boolean enable) {
            config.enableCompression = enable;
            return this;
        }
        
        public Builder enableEncryption(boolean enable) {
            config.enableEncryption = enable;
            return this;
        }
        
        public Builder encryptionKey(String key) {
            config.encryptionKey = key;
            return this;
        }
        
        public PersistenceConfig build() {
            return config;
        }
    }
    
    // Getters and setters
    public boolean isEnablePersistence() {
        return enablePersistence;
    }
    
    public void setEnablePersistence(boolean enablePersistence) {
        this.enablePersistence = enablePersistence;
    }
    
    public boolean isEnableWriteAheadLog() {
        return enableWriteAheadLog;
    }
    
    public void setEnableWriteAheadLog(boolean enableWriteAheadLog) {
        this.enableWriteAheadLog = enableWriteAheadLog;
    }
    
    public boolean isEnableSnapshots() {
        return enableSnapshots;
    }
    
    public void setEnableSnapshots(boolean enableSnapshots) {
        this.enableSnapshots = enableSnapshots;
    }
    
    public boolean isEnableIncrementalBackup() {
        return enableIncrementalBackup;
    }
    
    public void setEnableIncrementalBackup(boolean enableIncrementalBackup) {
        this.enableIncrementalBackup = enableIncrementalBackup;
    }
    
    public Duration getSnapshotInterval() {
        return snapshotInterval;
    }
    
    public void setSnapshotInterval(Duration snapshotInterval) {
        this.snapshotInterval = snapshotInterval;
    }
    
    public Duration getWalFlushInterval() {
        return walFlushInterval;
    }
    
    public void setWalFlushInterval(Duration walFlushInterval) {
        this.walFlushInterval = walFlushInterval;
    }
    
    public String getPersistenceDir() {
        return persistenceDir;
    }
    
    public void setPersistenceDir(String persistenceDir) {
        this.persistenceDir = persistenceDir;
    }
    
    public long getMaxSnapshotSize() {
        return maxSnapshotSize;
    }
    
    public void setMaxSnapshotSize(long maxSnapshotSize) {
        this.maxSnapshotSize = maxSnapshotSize;
    }
    
    public boolean isEnableCrashRecovery() {
        return enableCrashRecovery;
    }
    
    public void setEnableCrashRecovery(boolean enableCrashRecovery) {
        this.enableCrashRecovery = enableCrashRecovery;
    }
    
    public boolean isEnableNodeRecovery() {
        return enableNodeRecovery;
    }
    
    public void setEnableNodeRecovery(boolean enableNodeRecovery) {
        this.enableNodeRecovery = enableNodeRecovery;
    }
    
    public Duration getRecoveryTimeout() {
        return recoveryTimeout;
    }
    
    public void setRecoveryTimeout(Duration recoveryTimeout) {
        this.recoveryTimeout = recoveryTimeout;
    }
    
    public boolean isEnableCompression() {
        return enableCompression;
    }
    
    public void setEnableCompression(boolean enableCompression) {
        this.enableCompression = enableCompression;
    }
    
    public String getCompressionAlgorithm() {
        return compressionAlgorithm;
    }
    
    public void setCompressionAlgorithm(String compressionAlgorithm) {
        this.compressionAlgorithm = compressionAlgorithm;
    }
    
    public boolean isEnableEncryption() {
        return enableEncryption;
    }
    
    public void setEnableEncryption(boolean enableEncryption) {
        this.enableEncryption = enableEncryption;
    }
    
    public String getEncryptionKey() {
        return encryptionKey;
    }
    
    public void setEncryptionKey(String encryptionKey) {
        this.encryptionKey = encryptionKey;
    }
    
    /**
     * Creates a configuration from environment variables.
     */
    public static PersistenceConfig fromEnvironment() {
        PersistenceConfig config = new PersistenceConfig();
        
        // Check if persistence is enabled
        String persistenceEnabled = System.getenv("PERSISTENCE_ENABLED");
        if (persistenceEnabled != null && persistenceEnabled.equalsIgnoreCase("true")) {
            config.enablePersistence = true;
        }
        
        // Data directory
        String dataDir = System.getenv("DATA_DIR");
        if (dataDir != null && !dataDir.isEmpty()) {
            config.persistenceDir = dataDir;
        }
        
        // Snapshot interval
        String snapshotInterval = System.getenv("SNAPSHOT_INTERVAL");
        if (snapshotInterval != null) {
            try {
                config.snapshotInterval = Duration.parse(snapshotInterval);
            } catch (Exception e) {
                // Use default if parsing fails
            }
        }
        
        // WAL flush interval
        String walFlushInterval = System.getenv("WAL_FLUSH_INTERVAL");
        if (walFlushInterval != null) {
            try {
                config.walFlushInterval = Duration.parse(walFlushInterval);
            } catch (Exception e) {
                // Use default if parsing fails
            }
        }
        
        // Max snapshot size
        String maxSnapshotSize = System.getenv("MAX_SNAPSHOT_SIZE");
        if (maxSnapshotSize != null) {
            try {
                config.maxSnapshotSize = Long.parseLong(maxSnapshotSize);
            } catch (Exception e) {
                // Use default if parsing fails
            }
        }
        
        // Compression
        String enableCompression = System.getenv("ENABLE_COMPRESSION");
        if (enableCompression != null && enableCompression.equalsIgnoreCase("true")) {
            config.enableCompression = true;
        }
        
        // Encryption
        String enableEncryption = System.getenv("ENABLE_ENCRYPTION");
        if (enableEncryption != null && enableEncryption.equalsIgnoreCase("true")) {
            config.enableEncryption = true;
        }
        
        String encryptionKey = System.getenv("ENCRYPTION_KEY");
        if (encryptionKey != null && !encryptionKey.isEmpty()) {
            config.encryptionKey = encryptionKey;
        }
        
        return config;
    }
    
    @Override
    public String toString() {
        return "PersistenceConfig{" +
                "enablePersistence=" + enablePersistence +
                ", enableWriteAheadLog=" + enableWriteAheadLog +
                ", enableSnapshots=" + enableSnapshots +
                ", snapshotInterval=" + snapshotInterval +
                ", persistenceDir='" + persistenceDir + '\'' +
                ", maxSnapshotSize=" + maxSnapshotSize +
                ", enableCompression=" + enableCompression +
                ", enableEncryption=" + enableEncryption +
                '}';
    }
} 