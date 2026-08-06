package com.hy.bilicomment.infrastructure.persistence.entity;

import java.time.Instant;

public record CredentialRow(
        Long credentialProfileId,
        String credentialKey,
        String displayName,
        boolean enabled,
        String encryptedCookie,
        String validationStatus,
        Instant lastValidatedAt,
        String lastValidationMessage,
        int secretVersion,
        long version,
        Instant createdAt,
        Instant updatedAt) {}
