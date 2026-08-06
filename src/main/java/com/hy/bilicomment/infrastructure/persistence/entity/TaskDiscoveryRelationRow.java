package com.hy.bilicomment.infrastructure.persistence.entity;

import com.hy.bilicomment.domain.task.TaskDiscoveryRelationMode;
import java.time.Instant;

public record TaskDiscoveryRelationRow(
        Long parentTaskId,
        Long childTaskId,
        TaskDiscoveryRelationMode relationMode,
        Instant firstDiscoveredAt,
        Instant lastDiscoveredAt,
        Long firstDiscoveredExecutionId,
        Long lastDiscoveredExecutionId) {}
