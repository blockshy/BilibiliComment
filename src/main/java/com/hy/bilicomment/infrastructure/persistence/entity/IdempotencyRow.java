package com.hy.bilicomment.infrastructure.persistence.entity;

import java.time.Instant;

public record IdempotencyRow(
        Long idempotencyRequestId,
        String requestScope,
        String idempotencyKey,
        String requestHash,
        String state,
        String resourceType,
        String resourceId,
        Instant expiresAt) {}
