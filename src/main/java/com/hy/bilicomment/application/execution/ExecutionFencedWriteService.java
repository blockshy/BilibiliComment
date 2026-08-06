package com.hy.bilicomment.application.execution;

import com.hy.bilicomment.application.scheduling.DynamicTaskRegistry;
import com.hy.bilicomment.application.task.TaskDiscoveryRelationService;
import com.hy.bilicomment.application.task.TaskService;
import com.hy.bilicomment.domain.comment.CommentRecord;
import com.hy.bilicomment.domain.error.DomainException;
import com.hy.bilicomment.domain.task.SourceType;
import com.hy.bilicomment.domain.task.TaskDiscoveryRelationMode;
import com.hy.bilicomment.infrastructure.persistence.CommentRepository;
import com.hy.bilicomment.infrastructure.persistence.mapper.ExecutionMapper;
import com.hy.bilicomment.infrastructure.persistence.mapper.TaskMapper;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ExecutionFencedWriteService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionFencedWriteService.class);

    private final ExecutionMapper executionMapper;
    private final TaskMapper taskMapper;
    private final CommentRepository commentRepository;
    private final TaskService taskService;
    private final TaskDiscoveryRelationService relationService;
    private final TaskExecutionService executionService;
    private final DynamicTaskRegistry taskRegistry;

    public ExecutionFencedWriteService(
            ExecutionMapper executionMapper,
            TaskMapper taskMapper,
            CommentRepository commentRepository,
            TaskService taskService,
            TaskDiscoveryRelationService relationService,
            TaskExecutionService executionService,
            DynamicTaskRegistry taskRegistry) {
        this.executionMapper = executionMapper;
        this.taskMapper = taskMapper;
        this.commentRepository = commentRepository;
        this.taskService = taskService;
        this.relationService = relationService;
        this.executionService = executionService;
        this.taskRegistry = taskRegistry;
    }

    @Transactional
    public CommentRepository.InsertResult insertComments(
            long taskId,
            long executionId,
            String leaseOwner,
            List<CommentRecord> comments) {
        lockLease(taskId, executionId, leaseOwner);
        return commentRepository.insert(taskId, comments);
    }

    @Transactional
    public void updateSourceMetadata(
            long taskId,
            long executionId,
            String leaseOwner,
            String sourceMetadataJson) {
        lockLease(taskId, executionId, leaseOwner);
        if (taskMapper.updateSourceMetadata(taskId, sourceMetadataJson) != 1) {
            throw new DomainException("TASK_METADATA_UPDATE_REJECTED", "无法保存任务采集游标");
        }
    }

    @Transactional
    public TaskService.DiscoveredTaskResult createDiscovered(
            long parentTaskId,
            long executionId,
            String leaseOwner,
            String name,
            SourceType sourceType,
            String sourceId,
            Long credentialProfileId,
            Map<String, Object> metadata) {
        lockLease(parentTaskId, executionId, leaseOwner);
        TaskService.DiscoveredTaskResult child = taskService.createDiscovered(
                name, sourceType, sourceId, credentialProfileId, metadata);
        boolean managed = child.created() || relationService.isManagedChild(child.task().taskId());
        relationService.upsert(
                parentTaskId,
                child.task().taskId(),
                executionId,
                managed
                        ? TaskDiscoveryRelationMode.MANAGED
                        : TaskDiscoveryRelationMode.REFERENCED);
        if (managed) {
            executionService.ensureInitialExecution(child.task().taskId());
        }
        reconcileAfterCommit();
        return child;
    }

    private void reconcileAfterCommit() {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    taskRegistry.reconcileNow();
                } catch (RuntimeException exception) {
                    log.warn("Unable to reconcile schedules after discovered task commit", exception);
                }
            }
        });
    }

    private void lockLease(long taskId, long executionId, String leaseOwner) {
        if (executionMapper.lockOwnedLease(executionId, taskId, leaseOwner).isEmpty()) {
            throw new DomainException(ExecutionLeaseManager.LEASE_LOST_CODE, "执行租约已失效");
        }
    }
}
