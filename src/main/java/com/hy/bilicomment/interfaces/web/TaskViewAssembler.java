package com.hy.bilicomment.interfaces.web;

import com.hy.bilicomment.application.task.TaskDiscoveryQueryService;
import com.hy.bilicomment.application.task.TaskDiscoveryQueryService.DiscoverySummary;
import com.hy.bilicomment.application.task.TaskDiscoveryQueryService.ParentContext;
import com.hy.bilicomment.domain.task.TaskDiscoveryRelationMode;
import com.hy.bilicomment.domain.task.TaskOrigin;
import com.hy.bilicomment.domain.task.TaskType;
import com.hy.bilicomment.domain.execution.ExecutionStatus;
import com.hy.bilicomment.infrastructure.persistence.entity.CredentialRow;
import com.hy.bilicomment.infrastructure.persistence.entity.ExecutionRow;
import com.hy.bilicomment.infrastructure.persistence.entity.TaskRow;
import com.hy.bilicomment.infrastructure.persistence.mapper.CredentialMapper;
import com.hy.bilicomment.infrastructure.persistence.mapper.ExecutionMapper;
import com.hy.bilicomment.interfaces.web.dto.TaskDtos;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class TaskViewAssembler {

    private final ExecutionMapper executionMapper;
    private final CredentialMapper credentialMapper;
    private final TaskDiscoveryQueryService discoveryQueryService;
    private final ObjectMapper objectMapper;

    public TaskViewAssembler(
            ExecutionMapper executionMapper,
            CredentialMapper credentialMapper,
            TaskDiscoveryQueryService discoveryQueryService,
            ObjectMapper objectMapper) {
        this.executionMapper = executionMapper;
        this.credentialMapper = credentialMapper;
        this.discoveryQueryService = discoveryQueryService;
        this.objectMapper = objectMapper;
    }

    public List<TaskDtos.TaskSummary> summaries(List<TaskRow> tasks) {
        return summaries(tasks, Map.of());
    }

    public List<TaskDtos.TaskSummary> summaries(
            List<TaskRow> tasks,
            Map<Long, ParentContext> parentOverrides) {
        if (tasks.isEmpty()) {
            return List.of();
        }
        List<Long> taskIds = tasks.stream().map(TaskRow::taskId).distinct().toList();
        Map<Long, ParentContext> globalParents = discoveryQueryService.preferredParents(taskIds);
        Map<Long, ParentContext> parents = new LinkedHashMap<>(globalParents);
        parents.putAll(parentOverrides);
        List<Long> creatorTaskIds = tasks.stream()
                .filter(task -> task.taskType() == TaskType.CREATOR_WATCH)
                .map(TaskRow::taskId)
                .distinct()
                .toList();
        Map<Long, DiscoverySummary> discoverySummaries =
                discoveryQueryService.summaries(creatorTaskIds);
        Map<Long, CredentialRow> credentials = credentialMapper.findAll().stream()
                .collect(Collectors.toMap(CredentialRow::credentialProfileId, Function.identity()));
        Map<Long, ExecutionRow> latestByTask = executionsByTask(
                executionMapper.findLatestByTaskIds(taskIds));
        Map<Long, ExecutionRow> lastSuccessByTask = executionsByTask(
                executionMapper.findLastSucceededByTaskIds(taskIds));
        return tasks.stream()
                .map(task -> summary(
                        task,
                        credentials,
                        latestByTask.get(task.taskId()),
                        lastSuccessByTask.get(task.taskId()),
                        globalParents.get(task.taskId()),
                        parents.get(task.taskId()),
                        discoverySummaries.get(task.taskId())))
                .toList();
    }

    public TaskDtos.TaskDetail detail(TaskRow task) {
        Map<Long, CredentialRow> credentials = credentialMapper.findAll().stream()
                .collect(Collectors.toMap(CredentialRow::credentialProfileId, Function.identity()));
        ExecutionRow latest = executionMapper.findByTask(task.taskId(), 1).stream()
                .findFirst()
                .orElse(null);
        ExecutionRow lastSuccess = executionMapper.findLastSucceeded(task.taskId()).orElse(null);
        ParentContext parent = discoveryQueryService.preferredParents(List.of(task.taskId()))
                .get(task.taskId());
        DiscoverySummary discoverySummary = task.taskType() == TaskType.CREATOR_WATCH
                ? discoveryQueryService.summaries(List.of(task.taskId())).get(task.taskId())
                : null;
        TaskDtos.TaskSummary summary = summary(
                task, credentials, latest, lastSuccess, parent, parent, discoverySummary);
        JsonNode metadata = metadata(task);
        List<String> watched = metadata.path("watchedContentTypes").isArray()
                ? java.util.stream.StreamSupport.stream(
                                metadata.path("watchedContentTypes").spliterator(), false)
                        .map(JsonNode::asText)
                        .toList()
                : List.of();
        return new TaskDtos.TaskDetail(
                summary.id(),
                summary.version(),
                summary.name(),
                summary.kind(),
                summary.source(),
                summary.collectionMode(),
                summary.desiredState(),
                summary.runtimeState(),
                summary.health(),
                summary.scheduleLabel(),
                summary.credentialName(),
                summary.lastSuccessAt(),
                summary.nextRunAt(),
                summary.latestInsertedCount(),
                summary.updatedAt(),
                summary.origin(),
                summary.relationMode(),
                summary.parentTask(),
                summary.discoveredSummary(),
                task.remarks(),
                task.createdAt(),
                watched);
    }

    public TaskDtos.TaskExecution execution(ExecutionRow row) {
        return new TaskDtos.TaskExecution(
                Long.toString(row.executionId()),
                Long.toString(row.taskId()),
                trigger(row),
                row.status().name(),
                row.phase() == null ? null : row.phase().name(),
                row.pagesProcessed(),
                row.commentsDiscovered(),
                row.commentsInserted(),
                row.commentsDuplicate(),
                row.retryCount(),
                cursor(row.cursorStateJson()),
                row.errorSummary(),
                row.startedAt(),
                row.finishedAt(),
                row.heartbeatAt());
    }

    private TaskDtos.TaskSummary summary(
            TaskRow task,
            Map<Long, CredentialRow> credentials,
            ExecutionRow latest,
            ExecutionRow lastSuccess,
            ParentContext originParent,
            ParentContext parent,
            DiscoverySummary discoverySummary) {
        JsonNode metadata = metadata(task);
        String title = metadata.path("title").asText("");
        if (title.isBlank()) {
            title = task.sourceId();
        }
        CredentialRow credential = task.credentialProfileId() == null
                ? null
                : credentials.get(task.credentialProfileId());
        TaskOrigin origin = originParent != null
                        && originParent.relationMode() == TaskDiscoveryRelationMode.MANAGED
                ? TaskOrigin.DISCOVERED
                : TaskOrigin.MANUAL;
        return new TaskDtos.TaskSummary(
                Long.toString(task.taskId()),
                task.version(),
                task.taskName(),
                task.taskType().name(),
                new TaskDtos.TaskSource(task.sourceType().name(), task.sourceId(), title),
                task.collectionMode().name(),
                task.desiredState().name(),
                runtimeState(latest),
                health(latest),
                scheduleLabel(task),
                credential == null ? "未选择" : credential.displayName(),
                lastSuccess == null ? null : lastSuccess.finishedAt(),
                task.nextExecutionAt(),
                latest == null ? 0 : latest.commentsInserted(),
                task.updatedAt(),
                origin.name(),
                parent == null ? null : parent.relationMode().name(),
                parent == null
                        ? null
                        : new TaskDtos.ParentTask(
                                Long.toString(parent.taskId()), parent.name()),
                task.taskType() == TaskType.CREATOR_WATCH
                        ? discoveredSummary(discoverySummary)
                        : null);
    }

    private TaskDtos.DiscoveredSummary discoveredSummary(DiscoverySummary summary) {
        DiscoverySummary value = summary == null ? DiscoverySummary.empty() : summary;
        return new TaskDtos.DiscoveredSummary(
                value.total(),
                value.neverStarted(),
                value.queuedOrRunning(),
                value.retryWaiting(),
                value.errors(),
                value.commentsInserted24h(),
                value.newestDiscoveredAt());
    }

    private Map<Long, ExecutionRow> executionsByTask(List<ExecutionRow> executions) {
        return executions.stream().collect(Collectors.toMap(
                ExecutionRow::taskId,
                Function.identity(),
                (first, ignored) -> first));
    }

    private String runtimeState(ExecutionRow execution) {
        if (execution == null) {
            return "IDLE";
        }
        return switch (execution.status()) {
            case QUEUED -> "QUEUED";
            case RUNNING -> "RUNNING";
            case RETRY_WAIT -> "RETRY_WAIT";
            case SUCCEEDED -> "COMPLETED";
            case FAILED -> "ERROR";
            case CANCELLED -> "IDLE";
        };
    }

    private String health(ExecutionRow execution) {
        if (execution == null) {
            return "UNKNOWN";
        }
        return switch (execution.status()) {
            case FAILED -> "ERROR";
            case RETRY_WAIT -> "DEGRADED";
            case SUCCEEDED, RUNNING, QUEUED -> "HEALTHY";
            case CANCELLED -> "UNKNOWN";
        };
    }

    private String scheduleLabel(TaskRow task) {
        return switch (task.scheduleType()) {
            case ADAPTIVE -> "自适应";
            case MANUAL -> "手动执行";
            case CRON -> task.cronExpression();
        };
    }

    private String trigger(ExecutionRow row) {
        return switch (row.triggerType()) {
            case MANUAL -> "MANUAL";
            case CREATOR_DISCOVERY -> "DISCOVERED";
            case SCHEDULED, RECOVERY -> "SCHEDULED";
        };
    }

    private String cursor(String cursorStateJson) {
        try {
            String cursor = objectMapper.readTree(cursorStateJson == null ? "{}" : cursorStateJson)
                    .path("nextCursor")
                    .asText("");
            return cursor.isBlank() ? null : cursor;
        } catch (Exception ignored) {
            return null;
        }
    }

    private JsonNode metadata(TaskRow task) {
        try {
            return objectMapper.readTree(task.sourceMetadataJson());
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }
}
