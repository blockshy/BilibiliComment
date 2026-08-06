package com.hy.bilicomment.infrastructure.persistence.entity;

import com.hy.bilicomment.domain.execution.ExecutionPhase;
import com.hy.bilicomment.domain.execution.ExecutionStatus;
import com.hy.bilicomment.domain.execution.TriggerType;
import java.time.Instant;

public record ExecutionRow(
        Long executionId,
        Long taskId,
        TriggerType triggerType,
        ExecutionStatus status,
        ExecutionPhase phase,
        int attempt,
        long pagesProcessed,
        long commentsDiscovered,
        long commentsInserted,
        long commentsDuplicate,
        int retryCount,
        String cursorStateJson,
        boolean cancellationRequested,
        String errorCode,
        String errorSummary,
        String traceId,
        String workerId,
        Instant queuedAt,
        Instant startedAt,
        Instant heartbeatAt,
        Instant nextRetryAt,
        Instant finishedAt,
        Instant createdAt,
        Instant updatedAt) {}
