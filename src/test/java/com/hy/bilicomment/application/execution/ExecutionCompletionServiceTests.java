package com.hy.bilicomment.application.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hy.bilicomment.application.event.TaskEventPublisher;
import com.hy.bilicomment.domain.execution.ExecutionStatus;
import com.hy.bilicomment.domain.execution.TriggerType;
import com.hy.bilicomment.domain.task.CollectionMode;
import com.hy.bilicomment.domain.task.DesiredState;
import com.hy.bilicomment.domain.task.ScheduleType;
import com.hy.bilicomment.domain.task.SourceType;
import com.hy.bilicomment.domain.task.TaskType;
import com.hy.bilicomment.infrastructure.persistence.entity.ExecutionRow;
import com.hy.bilicomment.infrastructure.persistence.entity.TaskRow;
import com.hy.bilicomment.infrastructure.persistence.mapper.ExecutionMapper;
import com.hy.bilicomment.infrastructure.persistence.mapper.TaskMapper;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExecutionCompletionServiceTests {

    private static final String LEASE_OWNER = "fixture-instance:31:claim";
    private static final Instant NOW = Instant.parse("2026-07-14T00:00:00Z");

    @Mock private ExecutionMapper executionMapper;
    @Mock private TaskMapper taskMapper;
    @Mock private TaskScheduleService taskScheduleService;
    @Mock private TaskEventPublisher eventPublisher;

    private ExecutionCompletionService completionService;

    @BeforeEach
    void setUp() {
        completionService = new ExecutionCompletionService(
                executionMapper, taskMapper, taskScheduleService, eventPublisher);
    }

    @Test
    void succeedsAndAdvancesScheduleWithTheTerminalEventInOneServiceTransaction() {
        when(executionMapper.findById(31L)).thenReturn(Optional.of(execution()));
        when(taskMapper.findByIdForUpdate(7L)).thenReturn(Optional.of(task()));
        when(executionMapper.markSucceeded(31L, LEASE_OWNER)).thenReturn(1);

        ExecutionCompletionService.Outcome outcome =
                completionService.completeSuccess(31L, LEASE_OWNER);

        assertThat(outcome).isEqualTo(ExecutionCompletionService.Outcome.SUCCEEDED);
        verify(taskScheduleService).afterExecution(task(), true);
        verify(eventPublisher).publish(
                7L, 31L, "execution.updated", "任务执行成功", Map.of("status", "SUCCEEDED"));
    }

    @Test
    void cancellationWinsTheRaceAgainstFinalSuccess() {
        when(executionMapper.findById(31L)).thenReturn(Optional.of(execution()));
        when(taskMapper.findByIdForUpdate(7L)).thenReturn(Optional.of(task()));
        when(executionMapper.markSucceeded(31L, LEASE_OWNER)).thenReturn(0);
        when(executionMapper.markCancelledOwned(31L, LEASE_OWNER, "任务已请求停止")).thenReturn(1);

        ExecutionCompletionService.Outcome outcome =
                completionService.completeSuccess(31L, LEASE_OWNER);

        assertThat(outcome).isEqualTo(ExecutionCompletionService.Outcome.CANCELLED);
        verify(taskScheduleService).afterExecution(task(), false);
        verify(eventPublisher).publish(
                7L,
                31L,
                "execution.updated",
                "任务执行已取消",
                Map.of("status", "CANCELLED", "code", "EXECUTION_CANCELLED"));
    }

    @Test
    void staleWorkerCannotFinishAReclaimedExecution() {
        when(executionMapper.findById(31L)).thenReturn(Optional.of(execution()));
        when(taskMapper.findByIdForUpdate(7L)).thenReturn(Optional.of(task()));
        when(executionMapper.markSucceeded(31L, LEASE_OWNER)).thenReturn(0);
        when(executionMapper.markCancelledOwned(31L, LEASE_OWNER, "任务已请求停止")).thenReturn(0);

        ExecutionCompletionService.Outcome outcome =
                completionService.completeSuccess(31L, LEASE_OWNER);

        assertThat(outcome).isEqualTo(ExecutionCompletionService.Outcome.LEASE_LOST);
        verify(taskScheduleService, never()).afterExecution(task(), true);
        verify(eventPublisher, never()).publish(
                7L, 31L, "execution.updated", "任务执行成功", Map.of("status", "SUCCEEDED"));
    }

    @Test
    void cancellationWinsTheRaceAgainstFailure() {
        when(executionMapper.findById(31L)).thenReturn(Optional.of(execution()));
        when(taskMapper.findByIdForUpdate(7L)).thenReturn(Optional.of(task()));
        when(executionMapper.markFailedOwned(
                        31L, LEASE_OWNER, "BILIBILI_API_ERROR", "fixture"))
                .thenReturn(0);
        when(executionMapper.markCancelledOwned(31L, LEASE_OWNER, "任务已请求停止"))
                .thenReturn(1);

        ExecutionCompletionService.Outcome outcome = completionService.completeFailure(
                31L, LEASE_OWNER, "BILIBILI_API_ERROR", "fixture", false);

        assertThat(outcome).isEqualTo(ExecutionCompletionService.Outcome.CANCELLED);
        verify(taskScheduleService).afterExecution(task(), false);
        verify(eventPublisher).publish(
                7L,
                31L,
                "execution.updated",
                "任务执行已取消",
                Map.of("status", "CANCELLED", "code", "EXECUTION_CANCELLED"));
    }

    private ExecutionRow execution() {
        return new ExecutionRow(
                31L, 7L, TriggerType.MANUAL, ExecutionStatus.RUNNING, null, 1,
                0, 0, 0, 0, 0, "{}", false, null, null, "trace", "worker",
                NOW, NOW, NOW, null, null, NOW, NOW);
    }

    private TaskRow task() {
        return new TaskRow(
                7L, "Fixture", TaskType.CONTENT_COMMENTS, SourceType.VIDEO, "BVFixture",
                CollectionMode.FOLLOW_ONLY, DesiredState.ACTIVE, ScheduleType.ADAPTIVE,
                null, null, "{}", "{}", null, null, NOW.plusSeconds(30), 0, NOW, NOW);
    }
}
