package com.hy.bilicomment.infrastructure.persistence.entity;

import com.hy.bilicomment.domain.task.CollectionMode;
import com.hy.bilicomment.domain.task.DesiredState;
import com.hy.bilicomment.domain.task.ScheduleType;
import com.hy.bilicomment.domain.task.SourceType;
import com.hy.bilicomment.domain.task.TaskType;
import java.time.Instant;

public record TaskRow(
        Long taskId,
        String taskName,
        TaskType taskType,
        SourceType sourceType,
        String sourceId,
        CollectionMode collectionMode,
        DesiredState desiredState,
        ScheduleType scheduleType,
        String cronExpression,
        Long credentialProfileId,
        String sourceMetadataJson,
        String scheduleConfigJson,
        String remarks,
        Instant lastExecutionAt,
        Instant nextExecutionAt,
        long version,
        Instant createdAt,
        Instant updatedAt) {}
