package com.hy.bilicomment.application.execution;

import com.hy.bilicomment.application.event.TaskEventPublisher;
import com.hy.bilicomment.domain.error.DomainException;
import com.hy.bilicomment.domain.execution.TriggerType;
import com.hy.bilicomment.domain.task.DesiredState;
import com.hy.bilicomment.infrastructure.persistence.entity.ExecutionRow;
import com.hy.bilicomment.infrastructure.persistence.entity.TaskRow;
import com.hy.bilicomment.infrastructure.persistence.mapper.ExecutionMapper;
import com.hy.bilicomment.infrastructure.persistence.mapper.TaskMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class TaskExecutionService {

    private static final Logger log = LoggerFactory.getLogger(TaskExecutionService.class);

    private final ExecutionMapper executionMapper;
    private final TaskMapper taskMapper;
    private final PersistentExecutionDispatcher dispatcher;
    private final TaskEventPublisher eventPublisher;

    public TaskExecutionService(
            ExecutionMapper executionMapper,
            TaskMapper taskMapper,
            PersistentExecutionDispatcher dispatcher,
            TaskEventPublisher eventPublisher) {
        this.executionMapper = executionMapper;
        this.taskMapper = taskMapper;
        this.dispatcher = dispatcher;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public ExecutionRow queue(long taskId, TriggerType triggerType) {
        TaskRow task = taskMapper.findByIdForUpdate(taskId)
                .orElseThrow(() -> new DomainException("TASK_NOT_FOUND", "任务不存在"));
        if ((triggerType == TriggerType.SCHEDULED || triggerType == TriggerType.CREATOR_DISCOVERY)
                && task.desiredState() != DesiredState.ACTIVE) {
            throw new DomainException("TASK_PAUSED", "暂停任务不会执行调度");
        }
        ExecutionRow execution;
        try {
            execution = executionMapper.insertQueued(taskId, triggerType, UUID.randomUUID().toString());
        } catch (DuplicateKeyException exception) {
            throw new DomainException("TASK_ALREADY_RUNNING", "该任务已有活动执行");
        }
        publishQueued(execution);
        dispatchAfterCommit(execution.executionId());
        return execution;
    }

    @Transactional
    public Optional<ExecutionRow> ensureInitialExecution(long taskId) {
        TaskRow task = taskMapper.findByIdForUpdate(taskId)
                .orElseThrow(() -> new DomainException("TASK_NOT_FOUND", "任务不存在"));
        if (task.desiredState() != DesiredState.ACTIVE) {
            return Optional.empty();
        }
        Optional<ExecutionRow> inserted = executionMapper.insertInitialQueued(
                taskId,
                TriggerType.CREATOR_DISCOVERY,
                UUID.randomUUID().toString());
        inserted.ifPresent(execution -> {
            publishQueued(execution);
            dispatchAfterCommit(execution.executionId());
        });
        return inserted;
    }

    private void publishQueued(ExecutionRow execution) {
        eventPublisher.publish(
                execution.taskId(),
                execution.executionId(),
                "execution.updated",
                "任务已进入队列",
                Map.of("status", "QUEUED", "trigger", execution.triggerType().name()));
    }

    private void dispatchAfterCommit(long executionId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                dispatch(executionId);
            }
        });
    }

    @Transactional(readOnly = true)
    public ExecutionRow require(long executionId) {
        return executionMapper.findById(executionId)
                .orElseThrow(() -> new DomainException("EXECUTION_NOT_FOUND", "执行记录不存在"));
    }

    @Transactional(readOnly = true)
    public List<ExecutionRow> history(long taskId, int limit) {
        return history(taskId, null, limit);
    }

    @Transactional(readOnly = true)
    public List<ExecutionRow> history(long taskId, Long beforeExecutionId, int limit) {
        if (taskMapper.findById(taskId).isEmpty()) {
            throw new DomainException("TASK_NOT_FOUND", "任务不存在");
        }
        return executionMapper.findPageByTask(
                taskId,
                beforeExecutionId,
                Math.max(1, Math.min(limit, 101)));
    }

    @Transactional(readOnly = true)
    public long historyCount(long taskId) {
        if (taskMapper.findById(taskId).isEmpty()) {
            throw new DomainException("TASK_NOT_FOUND", "任务不存在");
        }
        return executionMapper.countByTask(taskId);
    }

    void dispatch(long executionId) {
        try {
            dispatcher.dispatch(executionId);
        } catch (RuntimeException exception) {
            log.warn("Immediate execution dispatch failed; durable recovery will retry executionId={}",
                    executionId, exception);
        }
    }
}
