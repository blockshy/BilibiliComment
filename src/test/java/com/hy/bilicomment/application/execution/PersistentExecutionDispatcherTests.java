package com.hy.bilicomment.application.execution;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

import com.hy.bilicomment.application.event.TaskEventPublisher;
import com.hy.bilicomment.config.AppProperties;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@ExtendWith(MockitoExtension.class)
class PersistentExecutionDispatcherTests {

    private static final Instant NOW = Instant.parse("2026-07-14T00:00:00Z");

    @Mock private ExecutionMapper executionMapper;
    @Mock private TaskMapper taskMapper;
    @Mock private ThreadPoolTaskExecutor taskWorkerExecutor;
    @Mock private ThreadPoolTaskExecutor backfillTaskExecutor;
    @Mock private ThreadPoolTaskScheduler scheduler;
    @Mock private ObjectProvider<TaskExecutionEngine> engineProvider;
    @Mock private TaskExecutionEngine engine;
    @Mock private TaskEventPublisher eventPublisher;
    @Mock private ExecutionInstanceIdentity instanceIdentity;

    private AppProperties properties;
    private PersistentExecutionDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        properties.getScheduler().setExecutionLeaseDuration(java.time.Duration.ofMinutes(2));
        properties.getScheduler().setExecutionRecoveryBatchSize(25);
        dispatcher = new PersistentExecutionDispatcher(
                executionMapper,
                taskMapper,
                taskWorkerExecutor,
                backfillTaskExecutor,
                scheduler,
                engineProvider,
                eventPublisher,
                instanceIdentity,
                properties);
    }

    @Test
    void claimsQueuedExecutionOnlyWhenANormalWorkerActuallyStarts() {
        ExecutionRow execution = execution();
        when(instanceIdentity.newLeaseOwner(31L)).thenReturn("fixture-instance:31:claim");
        when(engineProvider.getObject()).thenReturn(engine);
        when(executionMapper.findById(31L)).thenReturn(Optional.of(execution));
        when(taskMapper.findById(7L)).thenReturn(Optional.of(task(CollectionMode.FOLLOW_ONLY)));
        when(executionMapper.claimQueued(
                        31L,
                        "fixture-instance:31:claim",
                        "test-worker",
                        120L))
                .thenReturn(1);
        doAnswer(invocation -> {
                    Thread.currentThread().setName("test-worker");
                    invocation.<Runnable>getArgument(0).run();
                    return null;
                })
                .when(taskWorkerExecutor)
                .execute(any(Runnable.class));

        dispatcher.dispatch(31L);

        verify(executionMapper).claimQueued(
                31L,
                "fixture-instance:31:claim",
                "test-worker",
                120L);
        verify(engine).execute(31L, "fixture-instance:31:claim");
        verify(backfillTaskExecutor, never()).execute(any(Runnable.class));
    }

    @Test
    void routesBackfillToItsOwnExecutorInsteadOfBlockingNormalWorkers() {
        when(executionMapper.findById(31L)).thenReturn(Optional.of(execution()));
        when(taskMapper.findById(7L)).thenReturn(Optional.of(task(CollectionMode.BACKFILL_ONLY)));
        doAnswer(invocation -> null).when(backfillTaskExecutor).execute(any(Runnable.class));

        dispatcher.dispatch(31L);

        verify(backfillTaskExecutor).execute(any(Runnable.class));
        verify(taskWorkerExecutor, never()).execute(any(Runnable.class));
        verify(executionMapper, never()).claimQueued(anyLong(), any(), any(), anyLong());
    }

    @Test
    void rejectionLeavesExecutionDurablyQueuedForTheNextRecoverySweep() {
        when(executionMapper.findById(31L)).thenReturn(Optional.of(execution()));
        when(taskMapper.findById(7L)).thenReturn(Optional.of(task(CollectionMode.FOLLOW_ONLY)));
        doThrow(new RejectedExecutionException("fixture full"))
                .when(taskWorkerExecutor)
                .execute(any(Runnable.class));

        dispatcher.dispatch(31L);

        verify(executionMapper, never()).claimQueued(anyLong(), any(), any(), anyLong());
        verify(executionMapper, never()).markFailed(anyLong(), any(), any());
    }

    @Test
    void recoveryRequeuesExpiredLeasesAndResubmitsPersistedQueuedRows() {
        ExecutionRow execution = execution();
        when(executionMapper.requeueExpiredLeases(25)).thenReturn(List.of(execution));
        when(executionMapper.findQueued(25)).thenReturn(List.of(execution));
        when(executionMapper.findById(31L)).thenReturn(Optional.of(execution));
        when(taskMapper.findById(7L)).thenReturn(Optional.of(task(CollectionMode.FOLLOW_ONLY)));
        doAnswer(invocation -> null).when(taskWorkerExecutor).execute(any(Runnable.class));

        dispatcher.recoverAndDispatch();

        verify(eventPublisher).publish(
                eq(7L), eq(31L), eq("execution.updated"), eq("任务执行租约过期，已重新排队"), any());
        verify(taskWorkerExecutor).execute(any(Runnable.class));
    }

    @Test
    void recoveryDispatchesACommittedCreatorDiscoveryExecutionWithoutDynamicScheduling() {
        ExecutionRow execution = execution(TriggerType.CREATOR_DISCOVERY);
        properties.getScheduler().setEnabled(false);
        when(executionMapper.requeueExpiredLeases(25)).thenReturn(List.of());
        when(executionMapper.findQueued(25)).thenReturn(List.of(execution));
        when(executionMapper.findById(31L)).thenReturn(Optional.of(execution));
        when(taskMapper.findById(7L)).thenReturn(Optional.of(task(CollectionMode.FOLLOW_ONLY)));
        doAnswer(invocation -> null).when(taskWorkerExecutor).execute(any(Runnable.class));

        dispatcher.recoverAndDispatch();

        verify(taskWorkerExecutor).execute(any(Runnable.class));
    }

    @Test
    void repeatedRecoveryDoesNotFillTheLocalQueueWithTheSameExecution() {
        ExecutionRow execution = execution();
        when(executionMapper.requeueExpiredLeases(25)).thenReturn(List.of());
        when(executionMapper.findQueued(25)).thenReturn(List.of(execution));
        when(executionMapper.findById(31L)).thenReturn(Optional.of(execution));
        when(taskMapper.findById(7L)).thenReturn(Optional.of(task(CollectionMode.FOLLOW_ONLY)));
        doAnswer(invocation -> null).when(taskWorkerExecutor).execute(any(Runnable.class));

        dispatcher.recoverAndDispatch();
        dispatcher.recoverAndDispatch();

        verify(taskWorkerExecutor, times(1)).execute(any(Runnable.class));
    }

    @Test
    void queuedRunnableDoesNotClaimAfterDispatcherHasStopped() {
        when(executionMapper.findById(31L)).thenReturn(Optional.of(execution()));
        when(taskMapper.findById(7L)).thenReturn(Optional.of(task(CollectionMode.FOLLOW_ONLY)));
        ArgumentCaptor<Runnable> runnable = ArgumentCaptor.forClass(Runnable.class);
        doAnswer(invocation -> null).when(taskWorkerExecutor).execute(runnable.capture());

        dispatcher.dispatch(31L);
        dispatcher.stop();
        runnable.getValue().run();

        verify(executionMapper, never()).claimQueued(anyLong(), any(), any(), anyLong());
        verify(engine, never()).execute(eq(31L), any());
    }

    private ExecutionRow execution() {
        return execution(TriggerType.MANUAL);
    }

    private ExecutionRow execution(TriggerType triggerType) {
        return new ExecutionRow(
                31L, 7L, triggerType, ExecutionStatus.QUEUED, null, 1,
                0, 0, 0, 0, 0, "{}", false, null, null, "trace", null,
                NOW, null, null, null, null, NOW, NOW);
    }

    private TaskRow task(CollectionMode mode) {
        return new TaskRow(
                7L, "Fixture", TaskType.CONTENT_COMMENTS, SourceType.VIDEO, "BVFixture",
                mode, DesiredState.ACTIVE,
                mode == CollectionMode.BACKFILL_ONLY ? ScheduleType.MANUAL : ScheduleType.ADAPTIVE,
                null, null, "{}", "{}", null, null, NOW.plusSeconds(30), 0, NOW, NOW);
    }
}
