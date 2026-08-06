package com.hy.bilicomment.infrastructure.persistence.entity;

import java.time.Instant;

public record CommentExportJobRow(
        long exportId,
        long taskId,
        String requestedBy,
        String exportFormat,
        String exportColumnsJson,
        String filterJson,
        String filterHash,
        String sortOrder,
        long snapshotMaxCommentId,
        String status,
        long rowLimit,
        long byteLimit,
        long rowsWritten,
        long bytesWritten,
        String fileKey,
        String encryptedSha256,
        boolean cancellationRequested,
        String workerOwner,
        Instant leaseUntil,
        String errorCode,
        String errorSummary,
        Instant createdAt,
        Instant startedAt,
        Instant heartbeatAt,
        Instant finishedAt,
        Instant expiresAt,
        Instant updatedAt) {}
