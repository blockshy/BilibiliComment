package com.hy.bilicomment.application.task;

import com.hy.bilicomment.application.event.TaskEventPublisher;
import com.hy.bilicomment.application.scheduling.AdaptiveSchedulePolicy;
import com.hy.bilicomment.application.source.SourceNormalizer;
import com.hy.bilicomment.domain.error.DomainException;
import com.hy.bilicomment.domain.error.TaskConflictException;
import com.hy.bilicomment.domain.source.NormalizedSource;
import com.hy.bilicomment.domain.task.CollectionMode;
import com.hy.bilicomment.domain.task.DesiredState;
import com.hy.bilicomment.domain.task.ScheduleType;
import com.hy.bilicomment.domain.task.SourceType;
import com.hy.bilicomment.domain.task.TaskType;
import com.hy.bilicomment.infrastructure.persistence.entity.TaskRow;
import com.hy.bilicomment.infrastructure.persistence.mapper.CredentialMapper;
import com.hy.bilicomment.infrastructure.persistence.mapper.TaskMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class TaskService {

    private final TaskMapper taskMapper;
    private final CredentialMapper credentialMapper;
    private final SourceNormalizer sourceNormalizer;
    private final AdaptiveSchedulePolicy schedulePolicy;
    private final TaskEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TaskService(
            TaskMapper taskMapper,
            CredentialMapper credentialMapper,
            SourceNormalizer sourceNormalizer,
            AdaptiveSchedulePolicy schedulePolicy,
            TaskEventPublisher eventPublisher,
            ObjectMapper objectMapper,
            Clock clock) {
        this.taskMapper = taskMapper;
        this.credentialMapper = credentialMapper;
        this.sourceNormalizer = sourceNormalizer;
        this.schedulePolicy = schedulePolicy;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public TaskRow create(CreateTaskCommand command) {
        ValidatedTask validated = validate(command);
        TaskRow row = taskMapper.insert(
                    validated.name(),
                    validated.taskType(),
                    validated.source().type(),
                    validated.source().id(),
                    validated.collectionMode(),
                    validated.desiredState(),
                    validated.scheduleType(),
                    validated.cronExpression(),
                    validated.credentialProfileId(),
                    writeJson(validated.metadata()),
                    writeJson(validated.scheduleConfig()),
                    validated.remarks(),
                    validated.nextExecutionAt())
                .orElse(null);
        if (row == null) {
            TaskRow existing = taskMapper.findBySource(
                            validated.taskType(),
                            validated.source().type(),
                            validated.source().id(),
                            validated.collectionMode())
                    .orElseThrow(() -> new DomainException(
                            "TASK_CONFLICT_STATE_UNAVAILABLE",
                            "重复任务已存在，但暂时无法读取其状态"));
            throw new TaskConflictException(existing.taskId());
        }
        eventPublisher.publish(
                row.taskId(),
                null,
                "task.updated",
                "任务已创建",
                Map.of("taskId", Long.toString(row.taskId()), "state", row.desiredState().name()));
        return row;
    }

    @Transactional
    public DiscoveredTaskResult createDiscovered(
            String name,
            SourceType sourceType,
            String sourceId,
            Long credentialProfileId,
            Map<String, Object> metadata) {
        try {
            return new DiscoveredTaskResult(create(new CreateTaskCommand(
                    name,
                    TaskType.CONTENT_COMMENTS,
                    sourceType,
                    sourceId,
                    CollectionMode.FOLLOW_ONLY,
                    DesiredState.ACTIVE,
                    ScheduleType.ADAPTIVE,
                    null,
                    "Asia/Shanghai",
                    credentialProfileId,
                    "由 UP 主监控任务发现",
                    metadata)), true);
        } catch (TaskConflictException conflict) {
            return new DiscoveredTaskResult(require(conflict.getExistingTaskId()), false);
        }
    }

    @Transactional(readOnly = true)
    public TaskRow require(long taskId) {
        return taskMapper.findById(taskId)
                .orElseThrow(() -> new DomainException("TASK_NOT_FOUND", "任务不存在"));
    }

    @Transactional(readOnly = true)
    public List<TaskRow> list(
            String query,
            DesiredState desiredState,
            SourceType sourceType,
            Long beforeTaskId,
            int size) {
        int safeSize = Math.max(1, Math.min(size, 100));
        return taskMapper.findPage(
                query,
                desiredState,
                sourceType,
                null,
                null,
                null,
                safeSize,
                beforeTaskId);
    }

    @Transactional
    public TaskRow changeState(long taskId, DesiredState desiredState) {
        TaskRow current = require(taskId);
        if (current.desiredState() == desiredState) {
            return current;
        }
        Instant nextExecutionAt = desiredState == DesiredState.PAUSED
                ? null
                : nextExecution(
                        current.taskType(),
                        current.scheduleType(),
                        current.cronExpression(),
                        scheduleZone(current),
                        current.createdAt(),
                        clock.instant());
        if (taskMapper.updateDesiredState(
                taskId, current.version(), desiredState, nextExecutionAt) != 1) {
            throw new DomainException("TASK_CONCURRENT_MODIFICATION", "任务已被其他操作更新，请刷新后重试");
        }
        TaskRow updated = require(taskId);
        eventPublisher.publish(
                taskId,
                null,
                "task.updated",
                desiredState == DesiredState.ACTIVE ? "任务已恢复" : "任务已暂停",
                Map.of("taskId", Long.toString(taskId), "state", desiredState.name()));
        return updated;
    }

    private ZoneId scheduleZone(TaskRow task) {
        try {
            String zoneId = objectMapper.readTree(task.scheduleConfigJson() == null
                            ? "{}"
                            : task.scheduleConfigJson())
                    .path("zoneId")
                    .asText("Asia/Shanghai");
            return ZoneId.of(zoneId);
        } catch (Exception exception) {
            throw new DomainException("SCHEDULE_CONFIG_INVALID", "任务调度配置无效", exception);
        }
    }

    private ValidatedTask validate(CreateTaskCommand command) {
        if (command.taskType() == null || command.sourceType() == null) {
            throw new DomainException("TASK_TYPE_REQUIRED", "任务类型和来源类型不能为空");
        }
        if (command.taskType() == TaskType.CONTENT_COMMENTS
                && command.sourceType() == SourceType.CREATOR) {
            throw new DomainException("TASK_SOURCE_MISMATCH", "评论任务只能使用视频或动态来源");
        }
        if (command.taskType() == TaskType.CREATOR_WATCH
                && command.sourceType() != SourceType.CREATOR) {
            throw new DomainException("TASK_SOURCE_MISMATCH", "UP 主监控任务只能使用 UID 来源");
        }
        CollectionMode mode = command.collectionMode() == null
                ? CollectionMode.FOLLOW_ONLY
                : command.collectionMode();
        if (command.taskType() == TaskType.CREATOR_WATCH && mode != CollectionMode.FOLLOW_ONLY) {
            throw new DomainException("TASK_MODE_INVALID", "UP 主监控任务仅支持持续增量采集");
        }
        DesiredState state = command.desiredState() == null ? DesiredState.ACTIVE : command.desiredState();
        ScheduleType scheduleType = command.scheduleType() == null ? ScheduleType.ADAPTIVE : command.scheduleType();
        if (mode == CollectionMode.BACKFILL_ONLY) {
            scheduleType = ScheduleType.MANUAL;
        }
        String cron = normalizeCron(scheduleType, command.cronExpression());
        NormalizedSource source = sourceNormalizer.normalize(command.sourceType(), command.sourceInput());
        if ((source.type() == SourceType.DYNAMIC || source.type() == SourceType.CREATOR)
                && command.credentialProfileId() == null) {
            throw new DomainException("CREDENTIAL_REQUIRED", "动态或 UP 主任务必须选择凭据");
        }
        if (command.credentialProfileId() != null
                && credentialMapper.findById(command.credentialProfileId()).isEmpty()) {
            throw new DomainException("CREDENTIAL_NOT_FOUND", "所选凭据不存在");
        }
        Instant now = clock.instant();
        String zoneId = command.scheduleZone() == null || command.scheduleZone().isBlank()
                ? "Asia/Shanghai"
                : command.scheduleZone();
        java.time.ZoneId scheduleZone;
        try {
            scheduleZone = java.time.ZoneId.of(zoneId);
        } catch (java.time.DateTimeException exception) {
            throw new DomainException("SCHEDULE_ZONE_INVALID", "调度时区无效", exception);
        }
        Instant next = state == DesiredState.PAUSED
                ? null
                : nextExecution(command.taskType(), scheduleType, cron, scheduleZone, now, now);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("canonicalUrl", source.canonicalUrl());
        if (command.metadata() != null) {
            metadata.putAll(command.metadata());
        }
        String name = command.name() == null || command.name().isBlank()
                ? defaultName(command.taskType(), source)
                : command.name().trim();
        return new ValidatedTask(
                name,
                command.taskType(),
                source,
                mode,
                state,
                scheduleType,
                cron,
                command.credentialProfileId(),
                command.remarks(),
                metadata,
                Map.of("zoneId", scheduleZone.getId()),
                next);
    }

    private String normalizeCron(ScheduleType scheduleType, String cronExpression) {
        if (scheduleType != ScheduleType.CRON) {
            return null;
        }
        if (cronExpression == null || cronExpression.isBlank()) {
            throw new DomainException("CRON_REQUIRED", "Cron 调度必须填写表达式");
        }
        try {
            CronExpression.parse(cronExpression.trim());
            return cronExpression.trim();
        } catch (IllegalArgumentException exception) {
            throw new DomainException("CRON_INVALID", "Cron 表达式无效", exception);
        }
    }

    private Instant nextExecution(
            TaskType taskType,
            ScheduleType type,
            String cron,
            java.time.ZoneId zone,
            Instant createdAt,
            Instant now) {
        if (type == ScheduleType.MANUAL) {
            return null;
        }
        if (type == ScheduleType.CRON) {
            ZonedDateTime next = CronExpression.parse(cron).next(now.atZone(zone));
            return next == null ? null : next.toInstant();
        }
        return schedulePolicy.nextDelay(taskType, createdAt, now).map(now::plus).orElse(null);
    }

    private String defaultName(TaskType type, NormalizedSource source) {
        return type == TaskType.CREATOR_WATCH
                ? "UP 主 " + source.id()
                : (source.type() == SourceType.VIDEO ? "视频 " : "动态 ") + source.id();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new DomainException("TASK_METADATA_INVALID", "任务元数据无法序列化", exception);
        }
    }

    private record ValidatedTask(
            String name,
            TaskType taskType,
            NormalizedSource source,
            CollectionMode collectionMode,
            DesiredState desiredState,
            ScheduleType scheduleType,
            String cronExpression,
            Long credentialProfileId,
            String remarks,
            Map<String, Object> metadata,
            Map<String, Object> scheduleConfig,
            Instant nextExecutionAt) {}

    public record DiscoveredTaskResult(TaskRow task, boolean created) {}
}
