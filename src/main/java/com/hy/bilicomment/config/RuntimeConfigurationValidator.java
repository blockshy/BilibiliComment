package com.hy.bilicomment.config;

import java.net.URI;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class RuntimeConfigurationValidator implements InitializingBean {

    private final AppProperties properties;
    private final Environment environment;

    public RuntimeConfigurationValidator(AppProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        validateCryptographicKeys();
        validateScheduler();
        validateCommentFeatures();
        validateEnvironmentBoundary();
    }

    private void validateCryptographicKeys() {
        byte[] credential = decodeKey(
                "CREDENTIAL_ENCRYPTION_KEY",
                properties.getCrypto().getCredentialKey());
        byte[] cursor = decodeKey(
                "CURSOR_SIGNING_KEY",
                properties.getCursor().getSigningKey());
        byte[] export = decodeKey(
                "EXPORT_ENCRYPTION_KEY",
                properties.getExports().getEncryptionKey());
        if (MessageDigest.isEqual(credential, cursor)
                || MessageDigest.isEqual(credential, export)
                || MessageDigest.isEqual(cursor, export)) {
            throw new IllegalStateException(
                    "Credential, cursor, and export keys must be independently generated");
        }
    }

    private byte[] decodeKey(String name, String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(name + " must be configured");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(configured);
            if (decoded.length != 32) {
                throw new IllegalStateException(name + " must decode to exactly 32 bytes");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(name + " must be valid Base64", exception);
        }
    }

    private void validateCommentFeatures() {
        AppProperties.Exports exports = properties.getExports();
        if (!positive(properties.getCursor().getTtl())
                || exports.getDirectory() == null
                || exports.getDirectory().isBlank()
                || !positive(exports.getPollDelay())
                || !positive(exports.getTtl())
                || !positive(exports.getMaximumRuntime())
                || exports.getMaximumQueued() < 1
                || exports.getMaximumRows() < 1
                || exports.getMaximumBytes() < 1
                || exports.getChunkSize() < 1
                || exports.getChunkSize() > 5_000) {
            throw new IllegalStateException("Comment search and export settings must be positive and consistent");
        }
    }

    private void validateScheduler() {
        AppProperties.Scheduler scheduler = properties.getScheduler();
        if (scheduler.getPoolSize() < 1
                || scheduler.getWorkerCoreSize() < 1
                || scheduler.getWorkerMaxSize() < scheduler.getWorkerCoreSize()
                || scheduler.getWorkerQueueCapacity() < 1
                || scheduler.getBackfillConcurrency() < 1
                || scheduler.getBackfillQueueCapacity() < 1
                || scheduler.getExecutionRecoveryBatchSize() < 1
                || scheduler.getReconcileDelay() == null
                || scheduler.getReconcileDelay().isNegative()
                || scheduler.getReconcileDelay().isZero()
                || !positive(scheduler.getExecutionDispatchDelay())
                || !positive(scheduler.getRetryRecoveryDelay())
                || !positive(scheduler.getExecutionLeaseDuration())
                || !positive(scheduler.getExecutionHeartbeatDelay())
                || scheduler.getExecutionHeartbeatDelay().compareTo(
                        scheduler.getExecutionLeaseDuration()) >= 0
                || properties.getBilibili().getMaximumConcurrentRequestsPerCredential() < 1
                || properties.getBilibili().getMaximumAttempts() < 1
                || properties.getBilibili().getBackfillPageLimit() < 1
                || properties.getBilibili().getConnectTimeout() == null
                || properties.getBilibili().getConnectTimeout().isNegative()
                || properties.getBilibili().getConnectTimeout().isZero()
                || properties.getBilibili().getResponseTimeout() == null
                || properties.getBilibili().getResponseTimeout().isNegative()
                || properties.getBilibili().getResponseTimeout().isZero()
                || properties.getBilibili().getMinimumRequestInterval() == null
                || properties.getBilibili().getMinimumRequestInterval().isNegative()
                || properties.getBilibili().getMaximumExecutionRetries() < 0
                || properties.getBilibili().getExecutionRetryBaseDelay() == null
                || properties.getBilibili().getExecutionRetryBaseDelay().isNegative()
                || properties.getBilibili().getExecutionRetryBaseDelay().isZero()) {
            throw new IllegalStateException("Concurrency and worker pool sizes must be positive and consistent");
        }
    }

    private boolean positive(java.time.Duration duration) {
        return duration != null && !duration.isNegative() && !duration.isZero();
    }

    private void validateEnvironmentBoundary() {
        String appEnvironment = properties.getEnvironment() == null
                ? ""
                : properties.getEnvironment().trim().toLowerCase(Locale.ROOT);
        if (!Set.of("local", "dev", "prod").contains(appEnvironment)) {
            throw new IllegalStateException("APP_ENV must be one of local, dev, or prod");
        }
        if ("local".equals(appEnvironment)) {
            return;
        }
        String expectedHost = switch (appEnvironment) {
            case "dev" -> "dev.bili-comments.tyukki.com";
            case "prod" -> "bili-comments.tyukki.com";
            default -> throw new IllegalStateException("Unsupported APP_ENV");
        };
        String expectedDatabase = "bilibili_comment_" + appEnvironment;
        String expectedUsername = expectedDatabase + "_app";
        URI publicBaseUrl;
        try {
            publicBaseUrl = URI.create(properties.getPublicBaseUrl());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("PUBLIC_BASE_URL must be a valid absolute URI", exception);
        }
        if (!"https".equalsIgnoreCase(publicBaseUrl.getScheme())
                || !expectedHost.equalsIgnoreCase(publicBaseUrl.getHost())) {
            throw new IllegalStateException(
                    "PUBLIC_BASE_URL does not match the configured application environment");
        }
        String datasourceUrl = environment.getProperty("spring.datasource.url", "");
        if (!expectedDatabase.equals(databaseName(datasourceUrl))) {
            throw new IllegalStateException(
                    "The datasource database does not match the configured application environment");
        }
        String datasourceUsername = environment.getProperty("spring.datasource.username", "");
        if (!expectedUsername.equals(datasourceUsername)) {
            throw new IllegalStateException(
                    "The datasource runtime username does not match the configured application environment");
        }
        if (!environment.getProperty("server.servlet.session.cookie.secure", Boolean.class, false)) {
            throw new IllegalStateException("Secure session cookies are required in dev and prod");
        }
        if (environment.getProperty("spring.flyway.enabled", Boolean.class, true)) {
            throw new IllegalStateException("The persistent API must start with FLYWAY_ENABLED=false");
        }
    }

    private String databaseName(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:")) {
            throw new IllegalStateException("spring.datasource.url must be a valid JDBC URL");
        }
        try {
            String path = URI.create(jdbcUrl.substring("jdbc:".length())).getPath();
            if (path == null || path.length() <= 1) {
                throw new IllegalStateException("spring.datasource.url must include a database name");
            }
            return path.substring(1);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("spring.datasource.url must be a valid JDBC URL", exception);
        }
    }
}
