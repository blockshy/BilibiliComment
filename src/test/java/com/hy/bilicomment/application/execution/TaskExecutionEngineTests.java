package com.hy.bilicomment.application.execution;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hy.bilicomment.application.event.TaskEventPublisher;
import com.hy.bilicomment.domain.error.DomainException;
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
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TaskExecutionEngineTests {

    private static final String LEASE_OWNER = "fixture-instance:31:claim";

    @Mock private ExecutionMapper executionMapper;
    @Mock private TaskMapper taskMapper;
    @Mock private CommentCollectionService commentCollectionService;
    @Mock private CreatorDiscoveryService creatorDiscoveryService;
    @Mock private ExecutionRetryCoordinator retryCoordinator;
    @Mock private ExecutionCompletionService completionService;
    @Mock private ExecutionLeaseManager leaseManager;
    @Mock private ExecutionLeaseManager.Lease lease;
    @Mock private TaskEventPublisher eventPublisher;

    private TaskExecutionEngine engine;

    @BeforeEach
    void setUp() {
        engine = new TaskExecutionEngine(
                executionMapper,
                taskMapper,
                commentCollectionService,
                creatorDiscoveryService,
                retryCoordinator,
                completionService,
                leaseManager,
                eventPublisher);
    }

    @Test
    void runsAClaimedContentExecutionAndDelegatesAtomicCompletion() {
        ExecutionRow execution = execution();
        TaskRow task = task(TaskType.CONTENT_COMMENTS);
        prepareClaim(execution, task);

        engine.execute(31L, LEASE_OWNER);

        verify(commentCollectionService).collect(task, 31L, lease);
        verify(creatorDiscoveryService, never()).discover(any(), anyLong(), any());
        verify(completionService).completeSuccess(31L, LEASE_OWNER);
        verify(executionMapper, never()).markFailed(anyLong(), anyString(), anyString());
    }

    @Test
    void persistsDomainFailureThroughTheOwnedCompletionBoundary() {
        ExecutionRow execution = execution();
        TaskRow task = task(TaskType.CONTENT_COMMENTS);
        prepareClaim(execution, task);
        when(commentCollectionService.collect(task, 31L, lease))
                .thenThrow(new DomainException("BILIBILI_API_ERROR", "Fixture 上游错误"));

        assertThatCode(() -> engine.execute(31L, LEASE_OWNER)).doesNotThrowAnyException();

        verify(completionService).completeFailure(
                31L, LEASE_OWNER, "BILIBILI_API_ERROR", "Fixture 上游错误", false);
        verify(completionService, never()).completeSuccess(anyLong(), anyString());
    }

    @Test
    void cooperativeCancellationUsesTheOwnedCancelledTransition() {
        ExecutionRow execution = execution();
        TaskRow task = task(TaskType.CONTENT_COMMENTS);
        prepareClaim(execution, task);
        when(commentCollectionService.collect(task, 31L, lease))
                .thenThrow(new DomainException("EXECUTION_CANCELLED", "任务已请求停止"));

        engine.execute(31L, LEASE_OWNER);

        verify(completionService).completeFailure(
                31L, LEASE_OWNER, "EXECUTION_CANCELLED", "任务已请求停止", true);
        verify(retryCoordinator, never()).retryIfEligible(any(), any(), any(), any(), any());
    }

    @Test
    void durableRetryWaitDoesNotAdvanceTheTerminalSchedule() {
        ExecutionRow execution = execution();
        TaskRow task = task(TaskType.CONTENT_COMMENTS);
        DomainException failure = new DomainException(
                "BILIBILI_NETWORK_ERROR", "Fixture network failure");
        prepareClaim(execution, task);
        when(commentCollectionService.collect(task, 31L, lease)).thenThrow(failure);
        when(retryCoordinator.retryIfEligible(
                        execution, task, failure, "Fixture network failure", LEASE_OWNER))
                .thenReturn(true);

        engine.execute(31L, LEASE_OWNER);

        verify(completionService, never()).completeFailure(
                anyLong(), anyString(), anyString(), anyString(), anyBoolean());
        verify(completionService, never()).completeSuccess(anyLong(), anyString());
    }

    @Test
    void leaseLossStopsWithoutWritingAnyTerminalState() {
        ExecutionRow execution = execution();
        TaskRow task = task(TaskType.CONTENT_COMMENTS);
        prepareClaim(execution, task);
        doThrow(new DomainException(ExecutionLeaseManager.LEASE_LOST_CODE, "lost"))
                .when(lease)
                .assertOwned();
        when(lease.isLost()).thenReturn(true);

        engine.execute(31L, LEASE_OWNER);

        verify(commentCollectionService, never()).collect(any(), anyLong(), any());
        verify(completionService, never()).completeSuccess(anyLong(), anyString());
        verify(completionService, never()).completeFailure(
                anyLong(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void missingTaskCannotBypassTheLeaseOwnedFailurePredicate() {
        when(executionMapper.findById(31L)).thenReturn(Optional.of(execution()));
        when(taskMapper.findById(7L)).thenReturn(Optional.empty());

        engine.execute(31L, LEASE_OWNER);

        verify(executionMapper).markFailedOwned(
                31L, LEASE_OWNER, "TASK_NOT_FOUND", "任务不存在");
        verify(executionMapper, never()).markFailed(anyLong(), anyString(), anyString());
    }

    private void prepareClaim(ExecutionRow execution, TaskRow task) {
        when(executionMapper.findById(31L)).thenReturn(Optional.of(execution));
        when(taskMapper.findById(7L)).thenReturn(Optional.of(task));
        when(leaseManager.monitor(31L, LEASE_OWNER)).thenReturn(lease);
    }

    private ExecutionRow execution() {
        Instant now = Instant.parse("2026-07-14T00:00:00Z");
        return new ExecutionRow(
                31L, 7L, TriggerType.MANUAL, ExecutionStatus.RUNNING, null, 1,
                0, 0, 0, 0, 0, "{}", false, null, null, "fixture-trace", "worker",
                now, now, now, null, null, now, now);
    }

    private TaskRow task(TaskType type) {
        Instant now = Instant.parse("2026-07-14T00:00:00Z");
        return new TaskRow(
                7L,
                "Fixture task",
                type,
                type == TaskType.CONTENT_COMMENTS ? SourceType.VIDEO : SourceType.CREATOR,
                type == TaskType.CONTENT_COMMENTS ? "BVFixture" : "42",
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
                now,
                now);
    }
}
