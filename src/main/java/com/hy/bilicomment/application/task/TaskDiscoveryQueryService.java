package com.hy.bilicomment.application.task;

import com.hy.bilicomment.domain.error.DomainException;
import com.hy.bilicomment.domain.task.SourceType;
import com.hy.bilicomment.domain.task.TaskDiscoveryRelationMode;
import com.hy.bilicomment.domain.task.TaskType;
import com.hy.bilicomment.infrastructure.persistence.entity.TaskDiscoveryParentRow;
import com.hy.bilicomment.infrastructure.persistence.entity.TaskDiscoveryRelationRow;
import com.hy.bilicomment.infrastructure.persistence.entity.TaskDiscoverySummaryRow;
import com.hy.bilicomment.infrastructure.persistence.entity.TaskExecutionCommentMetricsRow;
import com.hy.bilicomment.infrastructure.persistence.entity.TaskRow;
import com.hy.bilicomment.infrastructure.persistence.mapper.TaskDiscoveryRelationMapper;
import com.hy.bilicomment.infrastructure.persistence.mapper.TaskMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskDiscoveryQueryService {

    private final TaskDiscoveryRelationMapper relationMapper;
    private final TaskMapper taskMapper;
    private final Clock clock;

    public TaskDiscoveryQueryService(
            TaskDiscoveryRelationMapper relationMapper,
            TaskMapper taskMapper,
            Clock clock) {
        this.relationMapper = relationMapper;
        this.taskMapper = taskMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Map<Long, ParentContext> preferredParents(List<Long> childTaskIds) {
        if (childTaskIds == null || childTaskIds.isEmpty()) {
            return Map.of();
        }
        return relationMapper.findPreferredParentsByChildIds(childTaskIds).stream()
                .collect(Collectors.toMap(
                        TaskDiscoveryParentRow::childTaskId,
                        row -> new ParentContext(
                                row.parentTaskId(),
                                row.parentTaskName(),
                                row.relationMode()),
                        (first, ignored) -> first,
                        LinkedHashMap::new));
    }

    @Transactional(readOnly = true)
    public Map<Long, DiscoverySummary> summaries(List<Long> parentTaskIds) {
        if (parentTaskIds == null || parentTaskIds.isEmpty()) {
            return Map.of();
        }
        Instant since = clock.instant().minus(Duration.ofHours(24));
        return relationMapper.findSummariesByParentIds(parentTaskIds, since).stream()
                .collect(Collectors.toMap(
                        TaskDiscoverySummaryRow::parentTaskId,
                        this::summary,
                        (first, ignored) -> first,
                        LinkedHashMap::new));
    }

    @Transactional(readOnly = true)
    public DiscoveredTaskPage findDiscoveredTasks(
            long parentTaskId,
            String query,
            SourceType sourceType,
            String runtimeState,
            String health,
            int limit,
            DiscoveryCursor cursor) {
        TaskRow parent = taskMapper.findById(parentTaskId)
                .orElseThrow(() -> new DomainException("TASK_NOT_FOUND", "任务不存在"));
        if (parent.taskType() != TaskType.CREATOR_WATCH) {
            throw new DomainException(
                    "TASK_DISCOVERY_UNSUPPORTED", "只有 UP 主内容监控任务包含发现内容");
        }
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<TaskDiscoveryRelationRow> relations = relationMapper.findPageByParent(
                parentTaskId,
                query,
                sourceType,
                runtimeState,
                health,
                safeLimit + 1,
                cursor == null ? null : cursor.firstDiscoveredAt(),
                cursor == null ? null : cursor.childTaskId());
        boolean hasNext = relations.size() > safeLimit;
        if (hasNext) {
            relations = relations.subList(0, safeLimit);
        }
        List<Long> childTaskIds = relations.stream()
                .map(TaskDiscoveryRelationRow::childTaskId)
                .toList();
        Map<Long, TaskRow> tasksById = taskMapper.findByIds(childTaskIds)
                .stream()
                .collect(Collectors.toMap(TaskRow::taskId, Function.identity()));
        Map<Long, CommentMetrics> metricsByTaskId = relationMapper
                .findCommentMetricsByChildIds(
                        childTaskIds,
                        clock.instant().minus(Duration.ofHours(24)))
                .stream()
                .collect(Collectors.toMap(
                        TaskExecutionCommentMetricsRow::taskId,
                        row -> new CommentMetrics(
                                row.commentsTotal(),
                                row.commentsInserted24h())));
        List<DiscoveredTask> items = relations.stream()
                .map(relation -> {
                    TaskRow child = tasksById.get(relation.childTaskId());
                    if (child == null) {
                        throw new IllegalStateException(
                                "Discovery child disappeared: " + relation.childTaskId());
                    }
                    return new DiscoveredTask(
                            child,
                            relation,
                            new ParentContext(
                                    parent.taskId(),
                                    parent.taskName(),
                                    relation.relationMode()),
                            metricsByTaskId.getOrDefault(
                                    child.taskId(), CommentMetrics.empty()));
                })
                .toList();
        long total = relationMapper.countByParent(
                parentTaskId, query, sourceType, runtimeState, health);
        return new DiscoveredTaskPage(items, total, hasNext);
    }

    private DiscoverySummary summary(TaskDiscoverySummaryRow row) {
        return new DiscoverySummary(
                row.total(),
                row.neverStarted(),
                row.queuedOrRunning(),
                row.retryWaiting(),
                row.errors(),
                row.commentsInserted24h(),
                row.newestDiscoveredAt());
    }

    public record ParentContext(
            long taskId,
            String name,
            TaskDiscoveryRelationMode relationMode) {}

    public record DiscoverySummary(
            long total,
            long neverStarted,
            long queuedOrRunning,
            long retryWaiting,
            long errors,
            long commentsInserted24h,
            Instant newestDiscoveredAt) {

        public static DiscoverySummary empty() {
            return new DiscoverySummary(0, 0, 0, 0, 0, 0, null);
        }
    }

    public record DiscoveredTask(
            TaskRow task,
            TaskDiscoveryRelationRow relation,
            ParentContext parent,
            CommentMetrics commentMetrics) {}

    public record CommentMetrics(long commentsTotal, long commentsInserted24h) {

        public static CommentMetrics empty() {
            return new CommentMetrics(0, 0);
        }
    }

    public record DiscoveryCursor(Instant firstDiscoveredAt, long childTaskId) {

        public DiscoveryCursor {
            if (firstDiscoveredAt == null || childTaskId <= 0) {
                throw new IllegalArgumentException("Discovery cursor values must be positive");
            }
        }
    }

    public record DiscoveredTaskPage(
            List<DiscoveredTask> items,
            long total,
            boolean hasNext) {

        public DiscoveredTaskPage {
            items = List.copyOf(items);
        }
    }
}
