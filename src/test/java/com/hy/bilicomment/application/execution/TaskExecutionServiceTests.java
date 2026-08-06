package com.hy.bilicomment.application.execution;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
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
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class TaskExecutionServiceTests {

    private static final Instant NOW = Instant.parse("2026-07-14T00:00:00Z");

    @Mock
    private ExecutionMapper executionMapper;

    @Mock
    private TaskMapper taskMapper;

    @Mock
    private PersistentExecutionDispatcher dispatcher;

    @Mock
    private TaskEventPublisher eventPublisher;

    private TaskExecutionService service;

    @BeforeEach
    void setUp() {
        service = new TaskExecutionService(
                executionMapper,
                taskMapper,
                dispatcher,
                eventPublisher);
    }

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void initialCreatorDiscoveryExecutionDispatchesOnlyAfterCommitEvenWhenSchedulingIsDisabled() {
        TaskRow task = task(DesiredState.ACTIVE);
        ExecutionRow execution = execution();
        when(taskMapper.findByIdForUpdate(7L)).thenReturn(Optional.of(task));
        when(executionMapper.insertInitialQueued(
                        org.mockito.ArgumentMatchers.eq(7L),
                        org.mockito.ArgumentMatchers.eq(TriggerType.CREATOR_DISCOVERY),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.of(execution));
        TransactionSynchronizationManager.initSynchronization();

        Optional<ExecutionRow> inserted = service.ensureInitialExecution(7L);

        assertThat(inserted).contains(execution);
        verify(dispatcher, never()).dispatch(31L);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
        verify(dispatcher).dispatch(31L);
    }

    @Test
    void rollbackNeverDispatchesAnInitialExecution() {
        TaskRow task = task(DesiredState.ACTIVE);
        when(taskMapper.findByIdForUpdate(7L)).thenReturn(Optional.of(task));
        when(executionMapper.insertInitialQueued(
                        org.mockito.ArgumentMatchers.eq(7L),
                        org.mockito.ArgumentMatchers.eq(TriggerType.CREATOR_DISCOVERY),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.of(execution()));
        TransactionSynchronizationManager.initSynchronization();

        service.ensureInitialExecution(7L);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(synchronization -> synchronization.afterCompletion(
                        TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(dispatcher, never()).dispatch(31L);
    }

    @Test
    void repeatedInitialExecutionRequestDoesNothingAfterAnyExecutionExists() {
        when(taskMapper.findByIdForUpdate(7L)).thenReturn(Optional.of(task(DesiredState.ACTIVE)));
        when(executionMapper.insertInitialQueued(
                        org.mockito.ArgumentMatchers.eq(7L),
                        org.mockito.ArgumentMatchers.eq(TriggerType.CREATOR_DISCOVERY),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());

        assertThat(service.ensureInitialExecution(7L)).isEmpty();

        verify(eventPublisher, never()).publish(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyMap());
        verify(dispatcher, never()).dispatch(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void pausedManagedTaskIsNotForceStartedByRediscovery() {
        when(taskMapper.findByIdForUpdate(7L)).thenReturn(Optional.of(task(DesiredState.PAUSED)));

        assertThat(service.ensureInitialExecution(7L)).isEmpty();

        verify(executionMapper, never()).insertInitialQueued(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void dispatchDelegatesToTheDurableDispatcher() {

        service.dispatch(31L);

        verify(dispatcher).dispatch(31L);
    }

    @Test
    void immediateDispatchFailureDoesNotEscapeAfterTheQueueTransactionCommitted() {
        doThrow(new IllegalStateException("fixture dispatcher unavailable"))
                .when(dispatcher)
                .dispatch(31L);

        assertThatCode(() -> service.dispatch(31L)).doesNotThrowAnyException();

        verify(dispatcher).dispatch(31L);
    }

    private TaskRow task(DesiredState state) {
        return new TaskRow(
                7L, "Fixture", TaskType.CONTENT_COMMENTS, SourceType.VIDEO, "BVFixture",
                CollectionMode.FOLLOW_ONLY, state, ScheduleType.ADAPTIVE,
                null, null, "{}", "{}", null, null,
                state == DesiredState.ACTIVE ? NOW.plusSeconds(30) : null,
                0, NOW, NOW);
    }

    private ExecutionRow execution() {
        return new ExecutionRow(
                31L, 7L, TriggerType.CREATOR_DISCOVERY, ExecutionStatus.QUEUED, null, 1,
                0, 0, 0, 0, 0, "{}", false, null, null, "trace", null,
                NOW, null, null, null, null, NOW, NOW);
    }
}
