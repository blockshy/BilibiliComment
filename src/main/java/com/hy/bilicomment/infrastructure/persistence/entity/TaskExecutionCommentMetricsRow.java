package com.hy.bilicomment.infrastructure.persistence.entity;

public record TaskExecutionCommentMetricsRow(
        Long taskId,
        long commentsTotal,
        long commentsInserted24h) {}
