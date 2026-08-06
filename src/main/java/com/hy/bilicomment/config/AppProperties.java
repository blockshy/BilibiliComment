package com.hy.bilicomment.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app")
public class AppProperties {

    private String environment = "local";
    private String publicBaseUrl = "http://localhost:5173";
    private final Admin admin = new Admin();
    private final Crypto crypto = new Crypto();
    private final Scheduler scheduler = new Scheduler();
    private final Bilibili bilibili = new Bilibili();
    private final Events events = new Events();
    private final Cursor cursor = new Cursor();
    private final Exports exports = new Exports();

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public String getPublicBaseUrl() {
        return publicBaseUrl;
    }

    public void setPublicBaseUrl(String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl;
    }

    public Admin getAdmin() {
        return admin;
    }

    public Crypto getCrypto() {
        return crypto;
    }

    public Scheduler getScheduler() {
        return scheduler;
    }

    public Bilibili getBilibili() {
        return bilibili;
    }

    public Events getEvents() {
        return events;
    }

    public Cursor getCursor() {
        return cursor;
    }

    public Exports getExports() {
        return exports;
    }

    public static class Admin {
        private String username = "";
        private String passwordHash = "";

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPasswordHash() {
            return passwordHash;
        }

        public void setPasswordHash(String passwordHash) {
            this.passwordHash = passwordHash;
        }
    }

    public static class Crypto {
        private String credentialKey = "";

        public String getCredentialKey() {
            return credentialKey;
        }

        public void setCredentialKey(String credentialKey) {
            this.credentialKey = credentialKey;
        }
    }

    public static class Scheduler {
        private boolean enabled = true;
        private Duration reconcileDelay = Duration.ofSeconds(30);
        private int poolSize = 2;
        private int workerCoreSize = 2;
        private int workerMaxSize = 2;
        private int workerQueueCapacity = 20;
        private int backfillConcurrency = 1;
        private int backfillQueueCapacity = 3;
        private Duration executionDispatchDelay = Duration.ofSeconds(2);
        private Duration retryRecoveryDelay = Duration.ofSeconds(2);
        private Duration executionLeaseDuration = Duration.ofMinutes(2);
        private Duration executionHeartbeatDelay = Duration.ofSeconds(20);
        private int executionRecoveryBatchSize = 100;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getReconcileDelay() {
            return reconcileDelay;
        }

        public void setReconcileDelay(Duration reconcileDelay) {
            this.reconcileDelay = reconcileDelay;
        }

        public int getPoolSize() {
            return poolSize;
        }

        public void setPoolSize(int poolSize) {
            this.poolSize = poolSize;
        }

        public int getWorkerCoreSize() {
            return workerCoreSize;
        }

        public void setWorkerCoreSize(int workerCoreSize) {
            this.workerCoreSize = workerCoreSize;
        }

        public int getWorkerMaxSize() {
            return workerMaxSize;
        }

        public void setWorkerMaxSize(int workerMaxSize) {
            this.workerMaxSize = workerMaxSize;
        }

        public int getWorkerQueueCapacity() {
            return workerQueueCapacity;
        }

        public void setWorkerQueueCapacity(int workerQueueCapacity) {
            this.workerQueueCapacity = workerQueueCapacity;
        }

        public int getBackfillConcurrency() {
            return backfillConcurrency;
        }

        public void setBackfillConcurrency(int backfillConcurrency) {
            this.backfillConcurrency = backfillConcurrency;
        }

        public int getBackfillQueueCapacity() {
            return backfillQueueCapacity;
        }

        public void setBackfillQueueCapacity(int backfillQueueCapacity) {
            this.backfillQueueCapacity = backfillQueueCapacity;
        }

        public Duration getExecutionDispatchDelay() {
            return executionDispatchDelay;
        }

        public void setExecutionDispatchDelay(Duration executionDispatchDelay) {
            this.executionDispatchDelay = executionDispatchDelay;
        }

        public Duration getRetryRecoveryDelay() {
            return retryRecoveryDelay;
        }

        public void setRetryRecoveryDelay(Duration retryRecoveryDelay) {
            this.retryRecoveryDelay = retryRecoveryDelay;
        }

        public Duration getExecutionLeaseDuration() {
            return executionLeaseDuration;
        }

        public void setExecutionLeaseDuration(Duration executionLeaseDuration) {
            this.executionLeaseDuration = executionLeaseDuration;
        }

        public Duration getExecutionHeartbeatDelay() {
            return executionHeartbeatDelay;
        }

        public void setExecutionHeartbeatDelay(Duration executionHeartbeatDelay) {
            this.executionHeartbeatDelay = executionHeartbeatDelay;
        }

        public int getExecutionRecoveryBatchSize() {
            return executionRecoveryBatchSize;
        }

        public void setExecutionRecoveryBatchSize(int executionRecoveryBatchSize) {
            this.executionRecoveryBatchSize = executionRecoveryBatchSize;
        }
    }

    public static class Bilibili {
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration responseTimeout = Duration.ofSeconds(20);
        private int maximumAttempts = 3;
        private int maximumExecutionRetries = 2;
        private Duration executionRetryBaseDelay = Duration.ofSeconds(30);
        private Duration minimumRequestInterval = Duration.ofMillis(500);
        private int maximumConcurrentRequestsPerCredential = 1;
        private int backfillPageLimit = 10_000;
        private String userAgent = "BilibiliComment/0.1";

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getResponseTimeout() {
            return responseTimeout;
        }

        public void setResponseTimeout(Duration responseTimeout) {
            this.responseTimeout = responseTimeout;
        }

        public int getMaximumAttempts() {
            return maximumAttempts;
        }

        public void setMaximumAttempts(int maximumAttempts) {
            this.maximumAttempts = maximumAttempts;
        }

        public int getMaximumExecutionRetries() {
            return maximumExecutionRetries;
        }

        public void setMaximumExecutionRetries(int maximumExecutionRetries) {
            this.maximumExecutionRetries = maximumExecutionRetries;
        }

        public Duration getExecutionRetryBaseDelay() {
            return executionRetryBaseDelay;
        }

        public void setExecutionRetryBaseDelay(Duration executionRetryBaseDelay) {
            this.executionRetryBaseDelay = executionRetryBaseDelay;
        }

        public Duration getMinimumRequestInterval() {
            return minimumRequestInterval;
        }

        public void setMinimumRequestInterval(Duration minimumRequestInterval) {
            this.minimumRequestInterval = minimumRequestInterval;
        }

        public int getMaximumConcurrentRequestsPerCredential() {
            return maximumConcurrentRequestsPerCredential;
        }

        public void setMaximumConcurrentRequestsPerCredential(int maximumConcurrentRequestsPerCredential) {
            this.maximumConcurrentRequestsPerCredential = maximumConcurrentRequestsPerCredential;
        }

        public int getBackfillPageLimit() {
            return backfillPageLimit;
        }

        public void setBackfillPageLimit(int backfillPageLimit) {
            this.backfillPageLimit = backfillPageLimit;
        }

        public String getUserAgent() {
            return userAgent;
        }

        public void setUserAgent(String userAgent) {
            this.userAgent = userAgent;
        }
    }

    public static class Events {
        private Duration emitterTimeout = Duration.ofMinutes(30);
        private Duration heartbeatDelay = Duration.ofSeconds(25);

        public Duration getEmitterTimeout() {
            return emitterTimeout;
        }

        public void setEmitterTimeout(Duration emitterTimeout) {
            this.emitterTimeout = emitterTimeout;
        }

        public Duration getHeartbeatDelay() {
            return heartbeatDelay;
        }

        public void setHeartbeatDelay(Duration heartbeatDelay) {
            this.heartbeatDelay = heartbeatDelay;
        }
    }

    public static class Cursor {
        private String signingKey = "";
        private Duration ttl = Duration.ofHours(2);

        public String getSigningKey() {
            return signingKey;
        }

        public void setSigningKey(String signingKey) {
            this.signingKey = signingKey;
        }

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }
    }

    public static class Exports {
        private boolean enabled = true;
        private String directory = "/tmp/bilibili-comment-exports";
        private String encryptionKey = "";
        private Duration pollDelay = Duration.ofSeconds(2);
        private Duration ttl = Duration.ofHours(24);
        private Duration maximumRuntime = Duration.ofMinutes(10);
        private int maximumQueued = 3;
        private long maximumRows = 250_000;
        private long maximumBytes = 256L * 1024 * 1024;
        private int chunkSize = 1_000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getDirectory() {
            return directory;
        }

        public void setDirectory(String directory) {
            this.directory = directory;
        }

        public String getEncryptionKey() {
            return encryptionKey;
        }

        public void setEncryptionKey(String encryptionKey) {
            this.encryptionKey = encryptionKey;
        }

        public Duration getPollDelay() {
            return pollDelay;
        }

        public void setPollDelay(Duration pollDelay) {
            this.pollDelay = pollDelay;
        }

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }

        public Duration getMaximumRuntime() {
            return maximumRuntime;
        }

        public void setMaximumRuntime(Duration maximumRuntime) {
            this.maximumRuntime = maximumRuntime;
        }

        public int getMaximumQueued() {
            return maximumQueued;
        }

        public void setMaximumQueued(int maximumQueued) {
            this.maximumQueued = maximumQueued;
        }

        public long getMaximumRows() {
            return maximumRows;
        }

        public void setMaximumRows(long maximumRows) {
            this.maximumRows = maximumRows;
        }

        public long getMaximumBytes() {
            return maximumBytes;
        }

        public void setMaximumBytes(long maximumBytes) {
            this.maximumBytes = maximumBytes;
        }

        public int getChunkSize() {
            return chunkSize;
        }

        public void setChunkSize(int chunkSize) {
            this.chunkSize = chunkSize;
        }
    }
}
