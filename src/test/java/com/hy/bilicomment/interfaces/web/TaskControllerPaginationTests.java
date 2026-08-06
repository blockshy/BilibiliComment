package com.hy.bilicomment.interfaces.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hy.bilicomment.application.execution.TaskExecutionService;
import com.hy.bilicomment.application.idempotency.IdempotencyService;
import com.hy.bilicomment.application.scheduling.DynamicTaskRegistry;
import com.hy.bilicomment.application.source.SourceNormalizer;
import com.hy.bilicomment.application.task.TaskDiscoveryQueryService;
import com.hy.bilicomment.application.task.TaskService;
import com.hy.bilicomment.domain.error.DomainException;
import com.hy.bilicomment.domain.task.CollectionMode;
import com.hy.bilicomment.domain.task.DesiredState;
import com.hy.bilicomment.domain.task.ScheduleType;
import com.hy.bilicomment.domain.task.SourceType;
import com.hy.bilicomment.domain.task.TaskListScope;
import com.hy.bilicomment.domain.task.TaskType;
import com.hy.bilicomment.infrastructure.persistence.entity.TaskRow;
import com.hy.bilicomment.infrastructure.persistence.mapper.ExecutionMapper;
import com.hy.bilicomment.infrastructure.persistence.mapper.TaskMapper;
import com.hy.bilicomment.interfaces.web.dto.TaskDtos;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class TaskControllerPaginationTests {

    @Mock private TaskService taskService;
    @Mock private TaskExecutionService executionService;
    @Mock private TaskMapper taskMapper;
    @Mock private ExecutionMapper executionMapper;
    @Mock private TaskViewAssembler assembler;
    @Mock private SourceNormalizer sourceNormalizer;
    @Mock private IdempotencyService idempotencyService;
    @Mock private DynamicTaskRegistry taskRegistry;
    @Mock private TaskDiscoveryQueryService discoveryQueryService;

    private TaskController controller;

    @BeforeEach
    void setUp() {
        controller = new TaskController(
                taskService,
                executionService,
                taskMapper,
                executionMapper,
                assembler,
                sourceNormalizer,
                idempotencyService,
                taskRegistry,
                discoveryQueryService,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-07-14T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void usesTheLastTaskIdAsAnOpaqueBoundaryForTheNextPage() {
        when(assembler.summaries(anyList())).thenAnswer(invocation -> {
            List<TaskRow> rows = invocation.getArgument(0);
            return rows.stream().map(this::summary).toList();
        });
        when(taskMapper.findPageScoped(
                        isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                        eq("PRIMARY"), eq(3), isNull()))
                .thenReturn(List.of(task(9L), task(8L), task(7L)));
        when(taskMapper.findPageScoped(
                        isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                        eq("PRIMARY"), eq(3), eq(8L)))
                .thenReturn(List.of(task(7L), task(6L)));
        when(taskMapper.countFilteredScoped(
                        isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq("PRIMARY")))
                .thenReturn(4L);

        var first = controller.tasks(
                null, null, null, null, null, TaskListScope.PRIMARY, null, 2);

        assertThat(first.items()).extracting(TaskDtos.TaskSummary::id)
                .containsExactly("9", "8");
        assertThat(first.nextCursor()).isNotBlank().isNotEqualTo("8");
        assertThat(first.total()).isEqualTo(4L);

        var second = controller.tasks(
                null, null, null, null, null, TaskListScope.PRIMARY, first.nextCursor(), 2);

        assertThat(second.items()).extracting(TaskDtos.TaskSummary::id)
                .containsExactly("7", "6");
        assertThat(second.nextCursor()).isNull();
        assertThat(second.total()).isEqualTo(4L);
        verify(taskMapper).findPageScoped(
                null, null, null, null, null, null, "PRIMARY", 3, 8L);
    }

    @Test
    void rejectsMalformedAndLegacyOffsetCursorsBeforeQueryingTheDatabase() {
        for (String cursor : List.of("2", "not-base64!", "eC12MQ")) {
            assertThatThrownBy(() -> controller.tasks(
                            null, null, null, null, null,
                            TaskListScope.PRIMARY, cursor, 50))
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getCode()).isEqualTo("CURSOR_INVALID"));
        }

        verifyNoInteractions(taskMapper);
    }

    private TaskRow task(long taskId) {
        Instant now = Instant.parse("2026-07-14T00:00:00Z");
        return new TaskRow(
                taskId,
                "Fixture " + taskId,
                TaskType.CONTENT_COMMENTS,
                SourceType.VIDEO,
                "BVFixture" + taskId,
                CollectionMode.FOLLOW_ONLY,
                DesiredState.ACTIVE,
                ScheduleType.ADAPTIVE,
                null,
                null,
                "{}",
                "{}",
                null,
                null,
                now.plusSeconds(30),
                0,
                now.minusSeconds(taskId),
                now.plusSeconds(taskId));
    }

    private TaskDtos.TaskSummary summary(TaskRow task) {
        return new TaskDtos.TaskSummary(
                Long.toString(task.taskId()),
                task.version(),
                task.taskName(),
                task.taskType().name(),
                new TaskDtos.TaskSource(
                        task.sourceType().name(), task.sourceId(), task.taskName()),
                task.collectionMode().name(),
                task.desiredState().name(),
                "IDLE",
                "UNKNOWN",
                "自适应",
                "未选择",
                null,
                task.nextExecutionAt(),
                0,
                task.updatedAt(),
                "MANUAL",
                null,
                null,
                null);
    }
}
