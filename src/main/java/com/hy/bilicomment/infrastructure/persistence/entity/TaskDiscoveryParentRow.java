package com.hy.bilicomment.infrastructure.persistence.entity;

import com.hy.bilicomment.domain.task.TaskDiscoveryRelationMode;

public record TaskDiscoveryParentRow(
        Long childTaskId,
        Long parentTaskId,
        String parentTaskName,
        TaskDiscoveryRelationMode relationMode) {}
