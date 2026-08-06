package com.hy.bilicomment.application.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.hy.bilicomment.infrastructure.persistence.entity.CredentialRow;
import com.hy.bilicomment.infrastructure.persistence.entity.TaskRow;
import com.hy.bilicomment.infrastructure.persistence.mapper.CredentialMapper;
import com.hy.bilicomment.infrastructure.persistence.mapper.TaskMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class TaskServiceStateTests {

    @Mock
    private TaskMapper taskMapper;

    @Mock
    private CredentialMapper credentialMapper;

    @Mock
    private SourceNormalizer sourceNormalizer;

    @Mock
    private AdaptiveSchedulePolicy schedulePolicy;

    @Mock
    private TaskEventPublisher eventPublisher;

    private TaskService service;

    @BeforeEach
    void setUp() {
        service = new TaskService(
                taskMapper,
                credentialMapper,
                sourceNormalizer,
                schedulePolicy,
                eventPublisher,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-07-14T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void transitionsAnActiveTaskToPausedWithOptimisticLocking() {
        TaskRow active = task(DesiredState.ACTIVE, 4L);
        TaskRow paused = task(DesiredState.PAUSED, 5L);
        when(taskMapper.findById(7L)).thenReturn(Optional.of(active), Optional.of(paused));
        when(taskMapper.updateDesiredState(7L, 4L, DesiredState.PAUSED, null)).thenReturn(1);

        TaskRow result = service.changeState(7L, DesiredState.PAUSED);

        assertThat(result).isSameAs(paused);
        verify(taskMapper).updateDesiredState(7L, 4L, DesiredState.PAUSED, null);
        verify(eventPublisher).publish(
                7L,
                null,
                "task.updated",
                "任务已暂停",
                Map.of("taskId", "7", "state", "PAUSED"));
    }

    @Test
    void listsTasksUsingTheProvidedTaskIdBoundaryAndClampedLimit() {
        TaskRow row = task(DesiredState.ACTIVE, 4L);
        when(taskMapper.findPage(
                        "fixture", DesiredState.ACTIVE, SourceType.VIDEO,
                        null, null, null, 100, 17L))
                .thenReturn(List.of(row));

        assertThat(service.list(
                        "fixture", DesiredState.ACTIVE, SourceType.VIDEO, 17L, 500))
                .containsExactly(row);

        verify(taskMapper).findPage(
                "fixture", DesiredState.ACTIVE, SourceType.VIDEO,
                null, null, null, 100, 17L);
    }

    @Test
    void createsAContentTaskWithoutRequiringTheInternalTableDdlFunction() {
        TaskRow created = task(DesiredState.ACTIVE, 0L);
        when(sourceNormalizer.normalize(SourceType.VIDEO, "BVFixture"))
                .thenReturn(new NormalizedSource(
                        SourceType.VIDEO,
                        "BVFixture",
                        "https://www.bilibili.com/video/BVFixture"));
        when(schedulePolicy.nextDelay(any(), any(), any()))
                .thenReturn(Optional.of(Duration.ofMinutes(5)));
        when(taskMapper.insert(
                        any(), any(), any(), any(), any(), any(), any(),
                        any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(created));

        TaskRow result = service.create(new CreateTaskCommand(
                "Fixture task",
                TaskType.CONTENT_COMMENTS,
                SourceType.VIDEO,
                "BVFixture",
                CollectionMode.FOLLOW_ONLY,
                DesiredState.ACTIVE,
                ScheduleType.ADAPTIVE,
                null,
                "Asia/Shanghai",
                null,
                null,
                Map.of()));

        assertThat(result).isSameAs(created);
        verify(eventPublisher).publish(
                7L,
                null,
                "task.updated",
                "任务已创建",
                Map.of("taskId", "7", "state", "ACTIVE"));
    }

    @Test
    void createsCreatorWatchUsingTheTypedAdaptivePolicy() {
        TaskRow created = task(
                TaskType.CREATOR_WATCH,
                DesiredState.ACTIVE,
                0L,
                Instant.parse("2026-07-14T00:00:00Z"));
        when(sourceNormalizer.normalize(SourceType.CREATOR, "42"))
                .thenReturn(new NormalizedSource(
                        SourceType.CREATOR,
                        "42",
                        "https://space.bilibili.com/42"));
        when(credentialMapper.findById(9L)).thenReturn(Optional.of(credential(9L)));
        when(schedulePolicy.nextDelay(
                        TaskType.CREATOR_WATCH,
                        Instant.parse("2026-07-14T00:00:00Z"),
                        Instant.parse("2026-07-14T00:00:00Z")))
                .thenReturn(Optional.of(Duration.ofSeconds(5)));
        when(taskMapper.insert(
                        any(), any(), any(), any(), any(), any(), any(),
                        any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(created));

        assertThat(service.create(new CreateTaskCommand(
                        "Creator fixture",
                        TaskType.CREATOR_WATCH,
                        SourceType.CREATOR,
                        "42",
                        CollectionMode.FOLLOW_ONLY,
                        DesiredState.ACTIVE,
                        ScheduleType.ADAPTIVE,
                        null,
                        "Asia/Shanghai",
                        9L,
                        null,
                        Map.of())))
                .isSameAs(created);

        verify(schedulePolicy).nextDelay(
                TaskType.CREATOR_WATCH,
                Instant.parse("2026-07-14T00:00:00Z"),
                Instant.parse("2026-07-14T00:00:00Z"));
    }

    @Test
    void transitionsAPausedTaskBackToActive() {
        TaskRow paused = task(DesiredState.PAUSED, 9L);
        TaskRow active = task(DesiredState.ACTIVE, 10L);
        when(taskMapper.findById(7L)).thenReturn(Optional.of(paused), Optional.of(active));
        when(schedulePolicy.nextDelay(
                        paused.taskType(),
                        paused.createdAt(),
                        Instant.parse("2026-07-14T00:00:00Z")))
                .thenReturn(Optional.of(Duration.ofMinutes(5)));
        when(taskMapper.updateDesiredState(
                        7L,
                        9L,
                        DesiredState.ACTIVE,
                        Instant.parse("2026-07-14T00:05:00Z")))
                .thenReturn(1);

        assertThat(service.changeState(7L, DesiredState.ACTIVE)).isSameAs(active);

        verify(taskMapper).updateDesiredState(
                7L,
                9L,
                DesiredState.ACTIVE,
                Instant.parse("2026-07-14T00:05:00Z"));

        verify(eventPublisher).publish(
                7L,
                null,
                "task.updated",
                "任务已恢复",
                Map.of("taskId", "7", "state", "ACTIVE"));
    }

    @Test
    void resumesCreatorWatchOlderThan72HoursWithANextExecution() {
        Instant now = Instant.parse("2026-07-14T00:00:00Z");
        Instant createdAt = now.minus(Duration.ofDays(30));
        TaskRow paused = task(TaskType.CREATOR_WATCH, DesiredState.PAUSED, 9L, createdAt);
        TaskRow active = task(TaskType.CREATOR_WATCH, DesiredState.ACTIVE, 10L, createdAt);
        when(taskMapper.findById(7L)).thenReturn(Optional.of(paused), Optional.of(active));
        when(schedulePolicy.nextDelay(TaskType.CREATOR_WATCH, createdAt, now))
                .thenReturn(Optional.of(Duration.ofMinutes(5)));
        when(taskMapper.updateDesiredState(
                        7L,
                        9L,
                        DesiredState.ACTIVE,
                        now.plus(Duration.ofMinutes(5))))
                .thenReturn(1);

        assertThat(service.changeState(7L, DesiredState.ACTIVE)).isSameAs(active);

        verify(taskMapper).updateDesiredState(
                7L,
                9L,
                DesiredState.ACTIVE,
                now.plus(Duration.ofMinutes(5)));
    }

    @Test
    void treatsAnAlreadyDesiredStateAsAnIdempotentNoOp() {
        TaskRow active = task(DesiredState.ACTIVE, 4L);
        when(taskMapper.findById(7L)).thenReturn(Optional.of(active));

        assertThat(service.changeState(7L, DesiredState.ACTIVE)).isSameAs(active);

        verify(taskMapper, never()).updateDesiredState(anyLong(), anyLong(), any(), any());
        verify(eventPublisher, never()).publish(any(), any(), any(), any(), any());
    }

    @Test
    void rejectsAStaleStateTransition() {
        when(taskMapper.findById(7L)).thenReturn(Optional.of(task(DesiredState.ACTIVE, 4L)));
        when(taskMapper.updateDesiredState(7L, 4L, DesiredState.PAUSED, null)).thenReturn(0);

        assertThatThrownBy(() -> service.changeState(7L, DesiredState.PAUSED))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("TASK_CONCURRENT_MODIFICATION"));
        verify(eventPublisher, never()).publish(any(), any(), any(), any(), any());
    }

    @Test
    void reportsTheExistingTaskWhenInsertLosesTheUniqueKeyRace() {
        TaskRow existing = task(DesiredState.ACTIVE, 2L);
        when(sourceNormalizer.normalize(SourceType.VIDEO, "BVFixture"))
                .thenReturn(new NormalizedSource(
                        SourceType.VIDEO,
                        "BVFixture",
                        "https://www.bilibili.com/video/BVFixture"));
        when(schedulePolicy.nextDelay(any(), any(), any()))
                .thenReturn(Optional.of(Duration.ofMinutes(5)));
        when(taskMapper.insert(
                        any(), any(), any(), any(), any(), any(), any(),
                        any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(taskMapper.findBySource(
                        TaskType.CONTENT_COMMENTS,
                        SourceType.VIDEO,
                        "BVFixture",
                        CollectionMode.FOLLOW_ONLY))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.create(new CreateTaskCommand(
                        "Fixture task",
                        TaskType.CONTENT_COMMENTS,
                        SourceType.VIDEO,
                        "BVFixture",
                        CollectionMode.FOLLOW_ONLY,
                        DesiredState.ACTIVE,
                        ScheduleType.ADAPTIVE,
                        null,
                        "Asia/Shanghai",
                        null,
                        null,
                        Map.of())))
                .isInstanceOfSatisfying(TaskConflictException.class,
                        exception -> assertThat(exception.getExistingTaskId()).isEqualTo(7L));

        verify(eventPublisher, never()).publish(any(), any(), any(), any(), any());
    }

    private TaskRow task(DesiredState state, long version) {
        Instant now = Instant.parse("2026-07-14T00:00:00Z");
        return task(TaskType.CONTENT_COMMENTS, state, version, now);
    }

    private TaskRow task(TaskType taskType, DesiredState state, long version, Instant createdAt) {
        Instant now = Instant.parse("2026-07-14T00:00:00Z");
        return new TaskRow(
                7L,
                "Fixture task",
                taskType,
                taskType == TaskType.CREATOR_WATCH ? SourceType.CREATOR : SourceType.VIDEO,
                taskType == TaskType.CREATOR_WATCH ? "42" : "BVFixture",
                CollectionMode.FOLLOW_ONLY,
                state,
                ScheduleType.ADAPTIVE,
                null,
                null,
                "{}",
                "{\"zoneId\":\"Asia/Shanghai\"}",
                null,
                null,
                now.plusSeconds(30),
                version,
                createdAt,
                now);
    }

    private CredentialRow credential(long id) {
        Instant now = Instant.parse("2026-07-14T00:00:00Z");
        return new CredentialRow(
                id,
                "credential-key",
                "Fixture credential",
                true,
                "encrypted",
                "VALID",
                now,
                null,
                1,
                0L,
                now,
                now);
    }
}
