package com.hy.bilicomment.infrastructure.persistence.entity;

import java.time.Instant;

public record EventRow(
        Long eventSequence,
        Long taskId,
        Long executionId,
        String eventType,
        String message,
        String payloadJson,
        Instant createdAt) {}
