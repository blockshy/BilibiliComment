package com.hy.bilicomment.interfaces.web;

import com.hy.bilicomment.application.execution.TaskExecutionService;
import com.hy.bilicomment.application.idempotency.IdempotencyService;
import com.hy.bilicomment.application.scheduling.DynamicTaskRegistry;
import com.hy.bilicomment.application.source.SourceNormalizer;
import com.hy.bilicomment.application.task.CreateTaskCommand;
import com.hy.bilicomment.application.task.TaskDiscoveryQueryService;
import com.hy.bilicomment.application.task.TaskDiscoveryQueryService.DiscoveryCursor;
import com.hy.bilicomment.application.task.TaskDiscoveryQueryService.ParentContext;
import com.hy.bilicomment.application.task.TaskService;
import com.hy.bilicomment.domain.error.DomainException;
import com.hy.bilicomment.domain.execution.TriggerType;
import com.hy.bilicomment.domain.source.NormalizedSource;
import com.hy.bilicomment.domain.task.CollectionMode;
import com.hy.bilicomment.domain.task.DesiredState;
import com.hy.bilicomment.domain.task.ScheduleType;
import com.hy.bilicomment.domain.task.SourceType;
import com.hy.bilicomment.domain.task.TaskListScope;
import com.hy.bilicomment.domain.task.TaskType;
import com.hy.bilicomment.infrastructure.persistence.entity.ExecutionRow;
import com.hy.bilicomment.infrastructure.persistence.entity.TaskRow;
import com.hy.bilicomment.infrastructure.persistence.mapper.ExecutionMapper;
import com.hy.bilicomment.infrastructure.persistence.mapper.TaskMapper;
import com.hy.bilicomment.interfaces.web.dto.ApiPage;
import com.hy.bilicomment.interfaces.web.dto.TaskDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1")
public class TaskController {

    private static final Logger log = LoggerFactory.getLogger(TaskController.class);

    private final TaskService taskService;
    private final TaskExecutionService executionService;
    private final TaskMapper taskMapper;
    private final ExecutionMapper executionMapper;
    private final TaskViewAssembler assembler;
    private final SourceNormalizer sourceNormalizer;
    private final IdempotencyService idempotencyService;
    private final DynamicTaskRegistry taskRegistry;
    private final TaskDiscoveryQueryService discoveryQueryService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TaskController(
            TaskService taskService,
            TaskExecutionService executionService,
            TaskMapper taskMapper,
            ExecutionMapper executionMapper,
            TaskViewAssembler assembler,
            SourceNormalizer sourceNormalizer,
            IdempotencyService idempotencyService,
            DynamicTaskRegistry taskRegistry,
            TaskDiscoveryQueryService discoveryQueryService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.taskService = taskService;
        this.executionService = executionService;
        this.taskMapper = taskMapper;
        this.executionMapper = executionMapper;
        this.assembler = assembler;
        this.sourceNormalizer = sourceNormalizer;
        this.idempotencyService = idempotencyService;
        this.taskRegistry = taskRegistry;
        this.discoveryQueryService = discoveryQueryService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @GetMapping("/task-capabilities")
    public List<TaskCapability> capabilities() {
        return List.of(
                new TaskCapability(
                        TaskType.CONTENT_COMMENTS.name(),
                        List.of(SourceType.VIDEO.name(), SourceType.DYNAMIC.name()),
                        List.of(CollectionMode.FOLLOW_ONLY.name(), CollectionMode.BACKFILL_ONLY.name())),
                new TaskCapability(
                        TaskType.CREATOR_WATCH.name(),
                        List.of(SourceType.CREATOR.name()),
                        List.of(CollectionMode.FOLLOW_ONLY.name())));
    }

    @PostMapping("/sources/resolve")
    public SourceResolution resolve(@Valid @RequestBody ResolveSourceRequest request) {
        NormalizedSource source = sourceNormalizer.normalize(request.sourceType(), request.input());
        return new SourceResolution(
                new TaskDtos.TaskSource(source.type().name(), source.id(), source.id()),
                true,
                null);
    }

    @GetMapping("/tasks")
    public ApiPage<TaskDtos.TaskSummary> tasks(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) SourceType sourceType,
            @RequestParam(required = false) CollectionMode collectionMode,
            @RequestParam(required = false) String runtimeState,
            @RequestParam(required = false) String health,
            @RequestParam(defaultValue = "PRIMARY") TaskListScope scope,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        if (query != null && query.length() > 255) {
            throw new DomainException("QUERY_TOO_LONG", "搜索内容不能超过 255 个字符");
        }
        int safeLimit = Math.max(1, Math.min(limit, 100));
        Long beforeTaskId = parseTaskCursor(cursor);
        String safeRuntimeState = validateFilter(
                runtimeState,
                Set.of("IDLE", "QUEUED", "RUNNING", "RETRY_WAIT", "ERROR", "COMPLETED"),
                "runtimeState");
        String safeHealth = validateFilter(
                health,
                Set.of("UNKNOWN", "HEALTHY", "DEGRADED", "ERROR"),
                "health");
        List<TaskRow> rows = taskMapper.findPageScoped(
                query,
                null,
                sourceType,
                collectionMode,
                safeRuntimeState,
                safeHealth,
                scope.name(),
                safeLimit + 1,
                beforeTaskId);
        boolean hasNext = rows.size() > safeLimit;
        if (hasNext) {
            rows = new ArrayList<>(rows.subList(0, safeLimit));
        }
        List<TaskDtos.TaskSummary> items = assembler.summaries(rows);
        String next = hasNext ? encodeTaskCursor(rows.getLast().taskId()) : null;
        return new ApiPage<>(
                items,
                next,
                taskMapper.countFilteredScoped(
                        query,
                        null,
                        sourceType,
                        collectionMode,
                        safeRuntimeState,
                        safeHealth,
                        scope.name()));
    }

    @GetMapping("/tasks/summary")
    public DashboardSummary dashboardSummary() {
        var since = clock.instant().minus(Duration.ofHours(24));
        return new DashboardSummary(
                taskMapper.countActive(),
                taskMapper.countPrimaryActive(),
                taskMapper.countManagedActive(),
                executionMapper.countRunning(),
                executionMapper.countRetryWaiting(),
                executionMapper.sumInsertedSince(since),
                executionMapper.countFailuresSince(since));
    }

    @GetMapping("/tasks/{taskId}")
    public TaskDtos.TaskDetail task(@PathVariable String taskId) {
        return assembler.detail(taskService.require(parseId(taskId, "taskId")));
    }

    @GetMapping("/tasks/{taskId}/discovered-tasks")
    public ApiPage<TaskDtos.DiscoveredTaskItem> discoveredTasks(
            @PathVariable String taskId,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) SourceType sourceType,
            @RequestParam(required = false) String runtimeState,
            @RequestParam(required = false) String health,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        if (query != null && query.length() > 255) {
            throw new DomainException("QUERY_TOO_LONG", "搜索内容不能超过 255 个字符");
        }
        if (sourceType == SourceType.CREATOR) {
            throw new DomainException("FILTER_INVALID", "发现内容来源仅支持视频或动态");
        }
        int safeLimit = Math.max(1, Math.min(limit, 100));
        DiscoveryCursor discoveryCursor = parseDiscoveryCursor(cursor);
        String safeRuntimeState = validateFilter(
                runtimeState,
                Set.of("IDLE", "QUEUED", "RUNNING", "RETRY_WAIT", "ERROR", "COMPLETED"),
                "runtimeState");
        String safeHealth = validateFilter(
                health,
                Set.of("UNKNOWN", "HEALTHY", "DEGRADED", "ERROR"),
                "health");
        var page = discoveryQueryService.findDiscoveredTasks(
                parseId(taskId, "taskId"),
                query,
                sourceType,
                safeRuntimeState,
                safeHealth,
                safeLimit,
                discoveryCursor);
        Map<Long, ParentContext> parents = page.items().stream()
                .collect(java.util.stream.Collectors.toMap(
                        item -> item.task().taskId(),
                        item -> item.parent(),
                        (first, ignored) -> first));
        List<TaskDtos.TaskSummary> summaries = assembler.summaries(
                page.items().stream()
                        .map(TaskDiscoveryQueryService.DiscoveredTask::task)
                        .toList(),
                parents);
        List<TaskDtos.DiscoveredTaskItem> items = new ArrayList<>();
        for (int index = 0; index < page.items().size(); index++) {
            var discovered = page.items().get(index);
            var relation = discovered.relation();
            items.add(new TaskDtos.DiscoveredTaskItem(
                    summaries.get(index),
                    discovered.commentMetrics().commentsTotal(),
                    discovered.commentMetrics().commentsInserted24h(),
                    relation.firstDiscoveredAt(),
                    relation.lastDiscoveredAt(),
                    nullableId(relation.firstDiscoveredExecutionId()),
                    nullableId(relation.lastDiscoveredExecutionId())));
        }
        String next = null;
        if (page.hasNext()) {
            var lastRelation = page.items().get(page.items().size() - 1).relation();
            next = encodeDiscoveryCursor(new DiscoveryCursor(
                    lastRelation.firstDiscoveredAt(), lastRelation.childTaskId()));
        }
        return new ApiPage<>(items, next, page.total());
    }

    @PostMapping("/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public TaskDtos.TaskDetail create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateTaskRequest request) {
        String canonical;
        try {
            canonical = objectMapper.writeValueAsString(request);
        } catch (Exception exception) {
            throw new DomainException("REQUEST_SERIALIZATION_FAILED", "请求无法序列化", exception);
        }
        IdempotencyService.Claim claim = idempotencyService.claim("CREATE_TASK", idempotencyKey, canonical);
        if (claim.replay()) {
            return assembler.detail(taskService.require(parseId(claim.resourceId(), "resourceId")));
        }
        boolean synchronizedTransaction = TransactionSynchronizationManager.isSynchronizationActive();
        if (synchronizedTransaction) {
            registerCreateSynchronization(claim.id());
        }
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (request.watchedContentTypes() != null && !request.watchedContentTypes().isEmpty()) {
                validateWatchedContentTypes(request.kind(), request.watchedContentTypes());
                metadata.put("watchedContentTypes", request.watchedContentTypes());
            }
            TaskRow task = taskService.create(new CreateTaskCommand(
                    request.name(),
                    request.kind(),
                    request.source().type(),
                    request.source().input(),
                    request.collectionMode(),
                    request.desiredState(),
                    request.schedule().strategy(),
                    request.schedule().cronExpression(),
                    request.schedule().zoneId(),
                    parseId(request.credentialProfileId(), "credentialProfileId"),
                    request.remark(),
                    metadata));
            if (request.startNow().booleanValue()) {
                executionService.queue(task.taskId(), TriggerType.MANUAL);
            }
            TaskDtos.TaskDetail detail = assembler.detail(taskService.require(task.taskId()));
            idempotencyService.complete(claim.id(), Long.toString(task.taskId()));
            if (!synchronizedTransaction) {
                taskRegistry.reconcileNow();
            }
            return detail;
        } catch (RuntimeException exception) {
            if (!synchronizedTransaction) {
                idempotencyService.fail(claim.id());
            }
            throw exception;
        }
    }

    private void registerCreateSynchronization(long claimId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    taskRegistry.reconcileNow();
                } catch (RuntimeException exception) {
                    log.error("Task registry reconciliation failed after task creation", exception);
                }
            }

            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) {
                    return;
                }
                try {
                    idempotencyService.fail(claimId);
                } catch (RuntimeException exception) {
                    log.error("Failed to release rolled-back idempotency claim {}", claimId, exception);
                }
            }
        });
    }

    @PostMapping("/tasks/{taskId}/pause")
    public TaskDtos.TaskDetail pause(@PathVariable String taskId) {
        TaskRow task = taskService.changeState(parseId(taskId, "taskId"), DesiredState.PAUSED);
        taskRegistry.reconcileNow();
        return assembler.detail(task);
    }

    @PostMapping("/tasks/{taskId}/resume")
    public TaskDtos.TaskDetail resume(@PathVariable String taskId) {
        TaskRow task = taskService.changeState(parseId(taskId, "taskId"), DesiredState.ACTIVE);
        taskRegistry.reconcileNow();
        return assembler.detail(task);
    }

    @PostMapping("/tasks/{taskId}/run")
    public TaskDtos.TaskExecution run(@PathVariable String taskId) {
        ExecutionRow execution = executionService.queue(parseId(taskId, "taskId"), TriggerType.MANUAL);
        return assembler.execution(execution);
    }

    private Long parseTaskCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        if (cursor.length() > 128) {
            throw new DomainException("CURSOR_INVALID", "分页游标无效");
        }
        try {
            String value = new String(
                    Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
            String[] parts = value.split(":", -1);
            if (parts.length != 2 || !"task-v1".equals(parts[0])) {
                throw new IllegalArgumentException();
            }
            long taskId = Long.parseLong(parts[1]);
            if (taskId <= 0 || !encodeTaskCursor(taskId).equals(cursor)) {
                throw new IllegalArgumentException();
            }
            return taskId;
        } catch (IllegalArgumentException exception) {
            throw new DomainException("CURSOR_INVALID", "分页游标无效");
        }
    }

    private String encodeTaskCursor(long taskId) {
        String value = "task-v1:" + taskId;
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.US_ASCII));
    }

    private DiscoveryCursor parseDiscoveryCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        if (cursor.length() > 256) {
            throw new DomainException("CURSOR_INVALID", "分页游标无效");
        }
        try {
            String value = new String(
                    Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
            String[] parts = value.split(":", -1);
            if (parts.length != 3) {
                throw new IllegalArgumentException();
            }
            long epochSecond = Long.parseLong(parts[0]);
            int nano = Integer.parseInt(parts[1]);
            long childTaskId = Long.parseLong(parts[2]);
            if (nano < 0 || nano > 999_999_999) {
                throw new IllegalArgumentException();
            }
            return new DiscoveryCursor(Instant.ofEpochSecond(epochSecond, nano), childTaskId);
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw new DomainException("CURSOR_INVALID", "分页游标无效");
        }
    }

    private String encodeDiscoveryCursor(DiscoveryCursor cursor) {
        String value = cursor.firstDiscoveredAt().getEpochSecond()
                + ":" + cursor.firstDiscoveredAt().getNano()
                + ":" + cursor.childTaskId();
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.US_ASCII));
    }

    private String validateFilter(String value, Set<String> allowed, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (!allowed.contains(value)) {
            throw new DomainException("FILTER_INVALID", field + " 筛选值无效");
        }
        return value;
    }

    private String nullableId(Long value) {
        return value == null ? null : Long.toString(value);
    }

    static long parseId(String value, String field) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new DomainException("ID_INVALID", field + " 必须是正整数");
        }
    }

    private void validateWatchedContentTypes(TaskType kind, List<SourceType> watchedContentTypes) {
        if (kind != TaskType.CREATOR_WATCH) {
            throw new DomainException("WATCHED_TYPES_NOT_APPLICABLE", "仅 UP 主监控任务可选择内容类型");
        }
        if (watchedContentTypes.stream().anyMatch(type -> type != SourceType.VIDEO && type != SourceType.DYNAMIC)) {
            throw new DomainException("WATCHED_TYPES_INVALID", "监控内容类型仅支持视频和动态");
        }
    }

    public record TaskCapability(String kind, List<String> sourceTypes, List<String> collectionModes) {}

    public record ResolveSourceRequest(
            @NotNull SourceType sourceType,
            @NotBlank @Size(max = 2048) String input) {}

    public record SourceResolution(TaskDtos.TaskSource source, boolean accessible, String message) {}

    public record DashboardSummary(
            long activeTasks,
            long primaryActiveTasks,
            long managedActiveTasks,
            long runningTasks,
            long retryWaitingTasks,
            long commentsInserted24h,
            long recentFailures) {}

    public record CreateTaskRequest(
            @NotBlank @Size(max = 255) String name,
            @NotNull TaskType kind,
            @Valid @NotNull TaskSourceInput source,
            @NotNull CollectionMode collectionMode,
            @NotBlank String credentialProfileId,
            @Valid @NotNull TaskScheduleInput schedule,
            List<SourceType> watchedContentTypes,
            @NotNull DesiredState desiredState,
            @NotNull Boolean startNow,
            @Size(max = 2000) String remark) {}

    public record TaskSourceInput(
            @NotNull SourceType type,
            @NotBlank @Size(max = 2048) String input) {}

    public record TaskScheduleInput(
            @NotNull ScheduleType strategy,
            String cronExpression,
            @NotBlank @Pattern(regexp = "Asia/Shanghai") String zoneId) {}
}
