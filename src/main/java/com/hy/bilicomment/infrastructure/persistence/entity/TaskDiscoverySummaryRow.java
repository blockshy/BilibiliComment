package com.hy.bilicomment.infrastructure.persistence.entity;

import java.time.Instant;

public record TaskDiscoverySummaryRow(
        Long parentTaskId,
        long total,
        long neverStarted,
        long queuedOrRunning,
        long retryWaiting,
        long errors,
        long commentsInserted24h,
        Instant newestDiscoveredAt) {}
