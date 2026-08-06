package com.hy.bilicomment.application.execution;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hy.bilicomment.application.task.TaskDiscoveryRelationService;
import com.hy.bilicomment.domain.error.DomainException;
import com.hy.bilicomment.infrastructure.persistence.entity.ExecutionRow;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@ExtendWith(MockitoExtension.class)
class ManagedDiscoveryInitialExecutionRecoveryTests {

    @Mock private TaskDiscoveryRelationService relationService;
    @Mock private TaskExecutionService executionService;
    @Mock private ThreadPoolTaskScheduler scheduler;

    private ManagedDiscoveryInitialExecutionRecovery recovery;

    @BeforeEach
    void setUp() {
        recovery = new ManagedDiscoveryInitialExecutionRecovery(
                relationService, executionService, scheduler);
    }

    @Test
    void applicationReadyOnlySubmitsRecoveryToTheSharedScheduler() {
        ArgumentCaptor<Runnable> recoveryTask = ArgumentCaptor.forClass(Runnable.class);

        recovery.onApplicationReady();

        verify(scheduler).execute(recoveryTask.capture());
        verifyNoInteractions(relationService, executionService);

        when(relationService.findManagedChildrenWithoutExecution(50)).thenReturn(List.of());
        recoveryTask.getValue().run();

        verify(relationService).findManagedChildrenWithoutExecution(50);
    }

    @Test
    void recoveryContinuesPastTheFirstFiftyManagedChildren() {
        List<Long> firstBatch = LongStream.rangeClosed(1, 50).boxed().toList();
        List<Long> secondBatch = LongStream.rangeClosed(51, 63).boxed().toList();
        when(relationService.findManagedChildrenWithoutExecution(50))
                .thenReturn(firstBatch, secondBatch);
        when(executionService.ensureInitialExecution(anyLong()))
                .thenReturn(Optional.of(mock(ExecutionRow.class)));

        recovery.recoverAllBatches();

        verify(relationService, times(2)).findManagedChildrenWithoutExecution(50);
        for (long taskId = 1; taskId <= 63; taskId++) {
            verify(executionService).ensureInitialExecution(taskId);
        }
    }

    @Test
    void oneBrokenChildDoesNotBlockTheRemainingRecoveryBatch() {
        when(relationService.findManagedChildrenWithoutExecution(50))
                .thenReturn(List.of(8L, 9L, 10L));
        when(executionService.ensureInitialExecution(8L))
                .thenThrow(new DomainException("FIXTURE_FAILURE", "fixture"));
        when(executionService.ensureInitialExecution(9L)).thenReturn(Optional.empty());
        when(executionService.ensureInitialExecution(10L)).thenReturn(Optional.empty());

        assertThatCode(recovery::recoverAllBatches).doesNotThrowAnyException();

        verify(executionService).ensureInitialExecution(9L);
        verify(executionService).ensureInitialExecution(10L);
    }

    @Test
    void fullBatchWithNoSuccessfulInsertStopsInsteadOfLoopingForever() {
        List<Long> brokenBatch = LongStream.rangeClosed(1, 50).boxed().toList();
        when(relationService.findManagedChildrenWithoutExecution(50)).thenReturn(brokenBatch);
        when(executionService.ensureInitialExecution(anyLong()))
                .thenThrow(new DomainException("FIXTURE_FAILURE", "fixture"));

        assertThatCode(recovery::recoverAllBatches).doesNotThrowAnyException();

        verify(relationService).findManagedChildrenWithoutExecution(50);
        verify(executionService, times(50)).ensureInitialExecution(anyLong());
    }

    @Test
    void repeatedFullBatchIsNotProcessedTwice() {
        List<Long> repeatedBatch = LongStream.rangeClosed(1, 50).boxed().toList();
        when(relationService.findManagedChildrenWithoutExecution(50))
                .thenReturn(repeatedBatch, repeatedBatch);
        when(executionService.ensureInitialExecution(anyLong()))
                .thenReturn(Optional.of(mock(ExecutionRow.class)));

        recovery.recoverAllBatches();

        verify(relationService, times(2)).findManagedChildrenWithoutExecution(50);
        verify(executionService, times(50)).ensureInitialExecution(anyLong());
    }

    @Test
    void repeatedRecoveryRemainsSafeBecauseInitialExecutionCreationIsIdempotent() {
        when(relationService.findManagedChildrenWithoutExecution(50)).thenReturn(List.of(8L));
        when(executionService.ensureInitialExecution(8L)).thenReturn(Optional.empty());

        recovery.recoverAllBatches();
        recovery.recoverAllBatches();

        verify(executionService, times(2)).ensureInitialExecution(8L);
    }

    @Test
    void relationInspectionFailureEndsTheBackgroundRecoverySafely() {
        when(relationService.findManagedChildrenWithoutExecution(50))
                .thenThrow(new IllegalStateException("fixture unavailable"));

        assertThatCode(recovery::recoverAllBatches).doesNotThrowAnyException();
    }

    @Test
    void schedulerRejectionDoesNotRunRecoveryOnTheApplicationReadyThread() {
        doThrow(new IllegalStateException("fixture stopping"))
                .when(scheduler)
                .execute(any(Runnable.class));

        assertThatCode(recovery::onApplicationReady).doesNotThrowAnyException();

        verify(relationService, never()).findManagedChildrenWithoutExecution(50);
    }
}
