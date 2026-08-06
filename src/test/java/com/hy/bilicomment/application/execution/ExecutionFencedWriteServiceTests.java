package com.hy.bilicomment.application.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hy.bilicomment.application.scheduling.DynamicTaskRegistry;
import com.hy.bilicomment.application.task.TaskDiscoveryRelationService;
import com.hy.bilicomment.application.task.TaskService;
import com.hy.bilicomment.domain.comment.CommentRecord;
import com.hy.bilicomment.domain.error.DomainException;
import com.hy.bilicomment.domain.task.CollectionMode;
import com.hy.bilicomment.domain.task.DesiredState;
import com.hy.bilicomment.domain.task.ScheduleType;
import com.hy.bilicomment.domain.task.SourceType;
import com.hy.bilicomment.domain.task.TaskDiscoveryRelationMode;
import com.hy.bilicomment.domain.task.TaskType;
import com.hy.bilicomment.infrastructure.persistence.CommentRepository;
import com.hy.bilicomment.infrastructure.persistence.entity.TaskRow;
import com.hy.bilicomment.infrastructure.persistence.mapper.ExecutionMapper;
import com.hy.bilicomment.infrastructure.persistence.mapper.TaskMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class ExecutionFencedWriteServiceTests {

    private static final String LEASE_OWNER = "fixture-instance:31:claim";

    @Mock private ExecutionMapper executionMapper;
    @Mock private TaskMapper taskMapper;
    @Mock private CommentRepository commentRepository;
    @Mock private TaskService taskService;
    @Mock private TaskDiscoveryRelationService relationService;
    @Mock private TaskExecutionService taskExecutionService;
    @Mock private DynamicTaskRegistry taskRegistry;

    private ExecutionFencedWriteService service;

    @BeforeEach
    void setUp() {
        service = new ExecutionFencedWriteService(
                executionMapper,
                taskMapper,
                commentRepository,
                taskService,
                relationService,
                taskExecutionService,
                taskRegistry);
    }

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void expiredLeaseCannotReachTheCommentInsert() {
        when(executionMapper.lockOwnedLease(31L, 7L, LEASE_OWNER))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.insertComments(7L, 31L, LEASE_OWNER, List.of()))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo(ExecutionLeaseManager.LEASE_LOST_CODE));

        verify(commentRepository, never()).insert(7L, List.of());
    }

    @Test
    void leaseRowIsLockedBeforeTheCommentWrite() {
        List<CommentRecord> comments = List.of();
        CommentRepository.InsertResult expected = new CommentRepository.InsertResult(0, 0, 0);
        when(executionMapper.lockOwnedLease(31L, 7L, LEASE_OWNER))
                .thenReturn(Optional.of(31L));
        when(commentRepository.insert(7L, comments)).thenReturn(expected);

        CommentRepository.InsertResult result =
                service.insertComments(7L, 31L, LEASE_OWNER, comments);

        assertThat(result).isEqualTo(expected);
        InOrder order = inOrder(executionMapper, commentRepository);
        order.verify(executionMapper).lockOwnedLease(31L, 7L, LEASE_OWNER);
        order.verify(commentRepository).insert(7L, comments);
    }

    @Test
    void expiredLeaseCannotUpdateMetadataOrCreateAChildTask() {
        when(executionMapper.lockOwnedLease(31L, 7L, LEASE_OWNER))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateSourceMetadata(
                        7L, 31L, LEASE_OWNER, "{}"))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> service.createDiscovered(
                        7L,
                        31L,
                        LEASE_OWNER,
                        "Child",
                        SourceType.VIDEO,
                        "BVFixture",
                        9L,
                        java.util.Map.of()))
                .isInstanceOf(DomainException.class);

        verify(taskMapper, never()).updateSourceMetadata(7L, "{}");
        verify(taskService, never()).createDiscovered(
                "Child", SourceType.VIDEO, "BVFixture", 9L, java.util.Map.of());
        verify(relationService, never()).upsert(
                anyLong(), anyLong(), anyLong(), org.mockito.ArgumentMatchers.any());
        verify(taskExecutionService, never()).ensureInitialExecution(anyLong());
    }

    @Test
    void newlyCreatedChildIsManagedAndQueuedBeforeSchedulerReconciliation() {
        TaskRow child = child();
        when(executionMapper.lockOwnedLease(31L, 7L, LEASE_OWNER))
                .thenReturn(Optional.of(31L));
        when(taskService.createDiscovered(
                        "Child", SourceType.VIDEO, "BVFixture", 9L, Map.of()))
                .thenReturn(new TaskService.DiscoveredTaskResult(child, true));
        TransactionSynchronizationManager.initSynchronization();

        TaskService.DiscoveredTaskResult result = service.createDiscovered(
                7L, 31L, LEASE_OWNER,
                "Child", SourceType.VIDEO, "BVFixture", 9L, Map.of());

        assertThat(result).isEqualTo(new TaskService.DiscoveredTaskResult(child, true));
        InOrder order = inOrder(executionMapper, taskService, relationService, taskExecutionService);
        order.verify(executionMapper).lockOwnedLease(31L, 7L, LEASE_OWNER);
        order.verify(taskService).createDiscovered(
                "Child", SourceType.VIDEO, "BVFixture", 9L, Map.of());
        order.verify(relationService).upsert(
                7L, 8L, 31L, TaskDiscoveryRelationMode.MANAGED);
        order.verify(taskExecutionService).ensureInitialExecution(8L);
        verify(taskRegistry, never()).reconcileNow();

        commitSynchronizations();

        verify(taskRegistry).reconcileNow();
    }

    @Test
    void independentlyCreatedReusedTaskIsReferencedWithoutBeingForceQueued() {
        TaskRow child = child();
        when(executionMapper.lockOwnedLease(31L, 7L, LEASE_OWNER))
                .thenReturn(Optional.of(31L));
        when(taskService.createDiscovered(
                        "Child", SourceType.VIDEO, "BVFixture", 9L, Map.of()))
                .thenReturn(new TaskService.DiscoveredTaskResult(child, false));
        when(relationService.isManagedChild(8L)).thenReturn(false);
        TransactionSynchronizationManager.initSynchronization();

        service.createDiscovered(
                7L, 31L, LEASE_OWNER,
                "Child", SourceType.VIDEO, "BVFixture", 9L, Map.of());

        verify(relationService).upsert(
                7L, 8L, 31L, TaskDiscoveryRelationMode.REFERENCED);
        verify(taskExecutionService, never()).ensureInitialExecution(8L);
    }

    @Test
    void reusedManagedTaskRepairsAMissingInitialExecutionIdempotently() {
        TaskRow child = child();
        when(executionMapper.lockOwnedLease(31L, 7L, LEASE_OWNER))
                .thenReturn(Optional.of(31L));
        when(taskService.createDiscovered(
                        "Child", SourceType.VIDEO, "BVFixture", 9L, Map.of()))
                .thenReturn(new TaskService.DiscoveredTaskResult(child, false));
        when(relationService.isManagedChild(8L)).thenReturn(true);
        TransactionSynchronizationManager.initSynchronization();

        service.createDiscovered(
                7L, 31L, LEASE_OWNER,
                "Child", SourceType.VIDEO, "BVFixture", 9L, Map.of());

        verify(relationService).upsert(
                7L, 8L, 31L, TaskDiscoveryRelationMode.MANAGED);
        verify(taskExecutionService).ensureInitialExecution(8L);
    }

    @Test
    void duplicateDiscoveryKeepsTheRelationshipManagedAndReliesOnIdempotentEnsure() {
        TaskRow child = child();
        when(executionMapper.lockOwnedLease(31L, 7L, LEASE_OWNER))
                .thenReturn(Optional.of(31L));
        when(taskService.createDiscovered(
                        "Child", SourceType.VIDEO, "BVFixture", 9L, Map.of()))
                .thenReturn(new TaskService.DiscoveredTaskResult(child, true))
                .thenReturn(new TaskService.DiscoveredTaskResult(child, false));
        when(relationService.isManagedChild(8L)).thenReturn(true);
        TransactionSynchronizationManager.initSynchronization();

        service.createDiscovered(
                7L, 31L, LEASE_OWNER,
                "Child", SourceType.VIDEO, "BVFixture", 9L, Map.of());
        service.createDiscovered(
                7L, 31L, LEASE_OWNER,
                "Child", SourceType.VIDEO, "BVFixture", 9L, Map.of());

        verify(relationService, times(2)).upsert(
                7L, 8L, 31L, TaskDiscoveryRelationMode.MANAGED);
        verify(taskExecutionService, times(2)).ensureInitialExecution(8L);
    }

    @Test
    void rollbackDoesNotRequestSchedulerReconciliation() {
        TaskRow child = child();
        when(executionMapper.lockOwnedLease(31L, 7L, LEASE_OWNER))
                .thenReturn(Optional.of(31L));
        when(taskService.createDiscovered(
                        "Child", SourceType.VIDEO, "BVFixture", 9L, Map.of()))
                .thenReturn(new TaskService.DiscoveredTaskResult(child, true));
        TransactionSynchronizationManager.initSynchronization();

        service.createDiscovered(
                7L, 31L, LEASE_OWNER,
                "Child", SourceType.VIDEO, "BVFixture", 9L, Map.of());
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(synchronization -> synchronization.afterCompletion(
                        TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(taskRegistry, never()).reconcileNow();
    }

    @Test
    void schedulerReconciliationFailureCannotUndoACommittedDiscoveredTask() {
        TaskRow child = child();
        when(executionMapper.lockOwnedLease(31L, 7L, LEASE_OWNER))
                .thenReturn(Optional.of(31L));
        when(taskService.createDiscovered(
                        "Child", SourceType.VIDEO, "BVFixture", 9L, Map.of()))
                .thenReturn(new TaskService.DiscoveredTaskResult(child, true));
        doThrow(new IllegalStateException("fixture scheduler unavailable"))
                .when(taskRegistry)
                .reconcileNow();
        TransactionSynchronizationManager.initSynchronization();

        service.createDiscovered(
                7L, 31L, LEASE_OWNER,
                "Child", SourceType.VIDEO, "BVFixture", 9L, Map.of());

        assertThatCode(this::commitSynchronizations).doesNotThrowAnyException();
    }

    private void commitSynchronizations() {
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
    }

    private TaskRow child() {
        Instant now = Instant.parse("2026-07-14T00:00:00Z");
        return new TaskRow(
                8L, "Child", TaskType.CONTENT_COMMENTS, SourceType.VIDEO, "BVFixture",
                CollectionMode.FOLLOW_ONLY, DesiredState.ACTIVE, ScheduleType.ADAPTIVE,
                null, 9L, "{}", "{}", null, null, now.plusSeconds(30), 0, now, now);
    }
}
