package com.hy.bilicomment.application.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hy.bilicomment.domain.error.DomainException;
import com.hy.bilicomment.domain.task.CollectionMode;
import com.hy.bilicomment.domain.task.DesiredState;
import com.hy.bilicomment.domain.task.ScheduleType;
import com.hy.bilicomment.domain.task.SourceType;
import com.hy.bilicomment.domain.task.TaskDiscoveryRelationMode;
import com.hy.bilicomment.domain.task.TaskType;
import com.hy.bilicomment.infrastructure.persistence.entity.TaskDiscoveryRelationRow;
import com.hy.bilicomment.infrastructure.persistence.entity.TaskExecutionCommentMetricsRow;
import com.hy.bilicomment.infrastructure.persistence.entity.TaskRow;
import com.hy.bilicomment.infrastructure.persistence.mapper.TaskDiscoveryRelationMapper;
import com.hy.bilicomment.infrastructure.persistence.mapper.TaskMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TaskDiscoveryQueryServiceTests {

    private static final Instant NOW = Instant.parse("2026-07-14T00:00:00Z");

    @Mock
    private TaskDiscoveryRelationMapper relationMapper;

    @Mock
    private TaskMapper taskMapper;

    private TaskDiscoveryQueryService service;

    @BeforeEach
    void setUp() {
        service = new TaskDiscoveryQueryService(
                relationMapper,
                taskMapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void rejectsDiscoveryQueryForContentTaskBeforeReadingRelations() {
        when(taskMapper.findById(7L)).thenReturn(Optional.of(task(7L, TaskType.CONTENT_COMMENTS)));

        assertThatThrownBy(() -> service.findDiscoveredTasks(
                        7L, null, null, null, null, 50, null))
                .isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo("TASK_DISCOVERY_UNSUPPORTED");

        verify(relationMapper, never())
                .findPageByParent(7L, null, null, null, null, 51, null, null);
    }

    @Test
    void clampsLimitBuildsParentContextAndReportsNextPage() {
        TaskRow parent = task(7L, TaskType.CREATOR_WATCH);
        TaskRow child = task(8L, TaskType.CONTENT_COMMENTS);
        TaskDiscoveryRelationRow first = relation(7L, 8L, 31L);
        TaskDiscoveryRelationRow lookahead = relation(7L, 9L, 31L);
        var cursor = new TaskDiscoveryQueryService.DiscoveryCursor(
                NOW.minusSeconds(120), 10L);
        when(taskMapper.findById(7L)).thenReturn(Optional.of(parent));
        when(relationMapper.findPageByParent(
                        7L,
                        "fixture",
                        SourceType.VIDEO,
                        null,
                        null,
                        2,
                        cursor.firstDiscoveredAt(),
                        cursor.childTaskId()))
                .thenReturn(List.of(first, lookahead));
        when(taskMapper.findByIds(List.of(8L))).thenReturn(List.of(child));
        when(relationMapper.findCommentMetricsByChildIds(
                        List.of(8L), NOW.minusSeconds(24 * 60 * 60)))
                .thenReturn(List.of(new TaskExecutionCommentMetricsRow(8L, 123L, 9L)));
        when(relationMapper.countByParent(7L, "fixture", SourceType.VIDEO, null, null))
                .thenReturn(5L);
        var page = service.findDiscoveredTasks(
                7L, "fixture", SourceType.VIDEO, null, null, 0, cursor);

        assertThat(page.items()).hasSize(1);
        assertThat(page.hasNext()).isTrue();
        assertThat(page.total()).isEqualTo(5);
        assertThat(page.items().get(0).parent())
                .isEqualTo(new TaskDiscoveryQueryService.ParentContext(
                        7L, parent.taskName(), TaskDiscoveryRelationMode.MANAGED));
        assertThat(page.items().get(0).commentMetrics())
                .isEqualTo(new TaskDiscoveryQueryService.CommentMetrics(123L, 9L));
        verify(relationMapper).findCommentMetricsByChildIds(
                List.of(8L), NOW.minusSeconds(24 * 60 * 60));
    }

    @Test
    void fillsZeroCommentMetricsWhenChildHasNoExecutions() {
        TaskRow parent = task(7L, TaskType.CREATOR_WATCH);
        TaskRow child = task(8L, TaskType.CONTENT_COMMENTS);
        when(taskMapper.findById(7L)).thenReturn(Optional.of(parent));
        when(relationMapper.findPageByParent(
                        7L, null, null, null, null, 51, null, null))
                .thenReturn(List.of(relation(7L, 8L, 31L)));
        when(taskMapper.findByIds(List.of(8L))).thenReturn(List.of(child));
        when(relationMapper.findCommentMetricsByChildIds(
                        List.of(8L), NOW.minusSeconds(24 * 60 * 60)))
                .thenReturn(List.of());

        var page = service.findDiscoveredTasks(
                7L, null, null, null, null, 50, null);

        assertThat(page.items().getFirst().commentMetrics())
                .isEqualTo(TaskDiscoveryQueryService.CommentMetrics.empty());
    }

    private TaskDiscoveryRelationRow relation(long parentId, long childId, long executionId) {
        return new TaskDiscoveryRelationRow(
                parentId,
                childId,
                TaskDiscoveryRelationMode.MANAGED,
                NOW.minusSeconds(60),
                NOW,
                executionId,
                executionId);
    }

    private TaskRow task(long taskId, TaskType taskType) {
        SourceType sourceType = taskType == TaskType.CREATOR_WATCH
                ? SourceType.CREATOR
                : SourceType.VIDEO;
        return new TaskRow(
                taskId,
                "Fixture task " + taskId,
                taskType,
                sourceType,
                "fixture-" + taskId,
                CollectionMode.FOLLOW_ONLY,
                DesiredState.ACTIVE,
                ScheduleType.ADAPTIVE,
                null,
                1L,
                "{}",
                "{}",
                null,
                null,
                NOW.plusSeconds(30),
                0,
                NOW.minusSeconds(3600),
                NOW);
    }
}
