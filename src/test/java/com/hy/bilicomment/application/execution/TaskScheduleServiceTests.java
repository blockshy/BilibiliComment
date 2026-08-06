package com.hy.bilicomment.application.execution;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hy.bilicomment.application.scheduling.AdaptiveSchedulePolicy;
import com.hy.bilicomment.domain.task.CollectionMode;
import com.hy.bilicomment.domain.task.DesiredState;
import com.hy.bilicomment.domain.task.ScheduleType;
import com.hy.bilicomment.domain.task.SourceType;
import com.hy.bilicomment.domain.task.TaskType;
import com.hy.bilicomment.infrastructure.persistence.entity.TaskRow;
import com.hy.bilicomment.infrastructure.persistence.mapper.TaskMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class TaskScheduleServiceTests {

    private static final Instant NOW = Instant.parse("2026-07-14T00:00:00Z");

    @Mock private TaskMapper taskMapper;
    @Mock private AdaptiveSchedulePolicy adaptiveSchedulePolicy;

    private TaskScheduleService service;

    @BeforeEach
    void setUp() {
        service = new TaskScheduleService(
                taskMapper,
                adaptiveSchedulePolicy,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new ObjectMapper());
    }

    @Test
    void successfulAdaptiveExecutionUsesThePolicyFromTheInjectedClock() {
        TaskRow task = task(DesiredState.ACTIVE, CollectionMode.FOLLOW_ONLY, ScheduleType.ADAPTIVE, null);
        when(adaptiveSchedulePolicy.nextDelay(task.taskType(), task.createdAt(), NOW))
                .thenReturn(Optional.of(Duration.ofMinutes(5)));

        service.afterExecution(task, true);

        verify(taskMapper).updateSchedule(7L, NOW, NOW.plus(Duration.ofMinutes(5)));
    }

    @Test
    void creatorWatchOlderThan72HoursKeepsSchedulingAfterSuccess() {
        TaskRow task = task(
                TaskType.CREATOR_WATCH,
                DesiredState.ACTIVE,
                CollectionMode.FOLLOW_ONLY,
                ScheduleType.ADAPTIVE,
                null);
        when(adaptiveSchedulePolicy.nextDelay(task.taskType(), task.createdAt(), NOW))
                .thenReturn(Optional.of(Duration.ofMinutes(5)));

        service.afterExecution(task, true);

        verify(taskMapper).updateSchedule(7L, NOW, NOW.plus(Duration.ofMinutes(5)));
    }

    @Test
    void failedCreatorWatchStillUsesItsAdaptiveCadenceAfterExecutionRetriesAreExhausted() {
        TaskRow task = task(
                TaskType.CREATOR_WATCH,
                DesiredState.ACTIVE,
                CollectionMode.FOLLOW_ONLY,
                ScheduleType.ADAPTIVE,
                null);
        when(adaptiveSchedulePolicy.nextDelay(task.taskType(), task.createdAt(), NOW))
                .thenReturn(Optional.of(Duration.ofMinutes(5)));

        service.afterExecution(task, false);

        verify(taskMapper).updateSchedule(7L, NOW, NOW.plus(Duration.ofMinutes(5)));
    }

    @Test
    void pausedTaskAlwaysClearsItsNextExecutionTime() {
        TaskRow task = task(DesiredState.PAUSED, CollectionMode.FOLLOW_ONLY, ScheduleType.ADAPTIVE, null);

        service.afterExecution(task, false);

        verify(taskMapper).updateSchedule(7L, NOW, null);
        verify(adaptiveSchedulePolicy, never())
                .nextDelay(task.taskType(), task.createdAt(), NOW);
    }

    @Test
    void completedBackfillPausesInsteadOfSchedulingAnotherRun() {
        TaskRow task = task(DesiredState.ACTIVE, CollectionMode.BACKFILL_ONLY, ScheduleType.MANUAL, null);

        service.afterExecution(task, true);

        verify(taskMapper).pauseAfterCompletion(7L, NOW);
        verify(taskMapper, never()).updateSchedule(7L, NOW, null);
    }

    @Test
    void exhaustedAdaptiveSchedulePausesAndRecordsTheCompletionTime() {
        TaskRow task = task(DesiredState.ACTIVE, CollectionMode.FOLLOW_ONLY, ScheduleType.ADAPTIVE, null);
        when(adaptiveSchedulePolicy.nextDelay(task.taskType(), task.createdAt(), NOW))
                .thenReturn(Optional.empty());

        service.afterExecution(task, true);

        verify(taskMapper).pauseAfterCompletion(7L, NOW);
        verify(taskMapper, never()).updateSchedule(7L, NOW, null);
    }

    @Test
    void cronAtAnExactClockBoundarySchedulesTheFollowingBoundary() {
        TaskRow task = task(
                DesiredState.ACTIVE,
                CollectionMode.FOLLOW_ONLY,
                ScheduleType.CRON,
                "0 * * * * *");

        service.afterExecution(task, true);

        verify(taskMapper).updateSchedule(7L, NOW, Instant.parse("2026-07-14T00:01:00Z"));
    }

    private TaskRow task(
            DesiredState desiredState,
            CollectionMode collectionMode,
            ScheduleType scheduleType,
            String cronExpression) {
        return task(
                TaskType.CONTENT_COMMENTS,
                desiredState,
                collectionMode,
                scheduleType,
                cronExpression);
    }

    private TaskRow task(
            TaskType taskType,
            DesiredState desiredState,
            CollectionMode collectionMode,
            ScheduleType scheduleType,
            String cronExpression) {
        return new TaskRow(
                7L,
                "Fixture task",
                taskType,
                taskType == TaskType.CREATOR_WATCH ? SourceType.CREATOR : SourceType.VIDEO,
                taskType == TaskType.CREATOR_WATCH ? "42" : "BVFixture",
                collectionMode,
                desiredState,
                scheduleType,
                cronExpression,
                null,
                "{}",
                "{\"zoneId\":\"UTC\"}",
                null,
                null,
                NOW.minus(Duration.ofDays(1)),
                0,
                NOW.minus(Duration.ofDays(30)),
                NOW.minus(Duration.ofDays(1)));
    }
}
