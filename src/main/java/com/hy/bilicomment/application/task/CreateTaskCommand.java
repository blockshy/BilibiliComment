package com.hy.bilicomment.application.task;

import com.hy.bilicomment.domain.task.CollectionMode;
import com.hy.bilicomment.domain.task.DesiredState;
import com.hy.bilicomment.domain.task.ScheduleType;
import com.hy.bilicomment.domain.task.SourceType;
import com.hy.bilicomment.domain.task.TaskType;
import java.util.Map;

public record CreateTaskCommand(
        String name,
        TaskType taskType,
        SourceType sourceType,
        String sourceInput,
        CollectionMode collectionMode,
        DesiredState desiredState,
        ScheduleType scheduleType,
        String cronExpression,
        String scheduleZone,
        Long credentialProfileId,
        String remarks,
        Map<String, Object> metadata) {}
