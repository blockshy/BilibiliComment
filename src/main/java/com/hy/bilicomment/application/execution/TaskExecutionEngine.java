package com.hy.bilicomment.application.execution;

import com.hy.bilicomment.application.event.TaskEventPublisher;
import com.hy.bilicomment.domain.error.DomainException;
import com.hy.bilicomment.domain.task.TaskType;
import com.hy.bilicomment.infrastructure.persistence.entity.ExecutionRow;
import com.hy.bilicomment.infrastructure.persistence.entity.TaskRow;
import com.hy.bilicomment.infrastructure.persistence.mapper.ExecutionMapper;
import com.hy.bilicomment.infrastructure.persistence.mapper.TaskMapper;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TaskExecutionEngine {

    private static final Logger log = LoggerFactory.getLogger(TaskExecutionEngine.class);

    private final ExecutionMapper executionMapper;
    private final TaskMapper taskMapper;
    private final CommentCollectionService commentCollectionService;
    private final CreatorDiscoveryService creatorDiscoveryService;
    private final ExecutionRetryCoordinator retryCoordinator;
    private final ExecutionCompletionService completionService;
    private final ExecutionLeaseManager leaseManager;
    private final TaskEventPublisher eventPublisher;

    public TaskExecutionEngine(
            ExecutionMapper executionMapper,
            TaskMapper taskMapper,
            CommentCollectionService commentCollectionService,
            CreatorDiscoveryService creatorDiscoveryService,
            ExecutionRetryCoordinator retryCoordinator,
            ExecutionCompletionService completionService,
            ExecutionLeaseManager leaseManager,
            TaskEventPublisher eventPublisher) {
        this.executionMapper = executionMapper;
        this.taskMapper = taskMapper;
        this.commentCollectionService = commentCollectionService;
        this.creatorDiscoveryService = creatorDiscoveryService;
        this.retryCoordinator = retryCoordinator;
        this.completionService = completionService;
        this.leaseManager = leaseManager;
        this.eventPublisher = eventPublisher;
    }

    public void execute(long executionId, String leaseOwner) {
        ExecutionRow execution = executionMapper.findById(executionId).orElse(null);
        if (execution == null) {
            return;
        }
        TaskRow task = taskMapper.findById(execution.taskId()).orElse(null);
        if (task == null) {
            executionMapper.markFailedOwned(
                    executionId, leaseOwner, "TASK_NOT_FOUND", "任务不存在");
            return;
        }

        ExecutionLeaseManager.Lease lease;
        try {
            lease = leaseManager.monitor(executionId, leaseOwner);
        } catch (RuntimeException exception) {
            completionService.completeFailure(
                    executionId,
                    leaseOwner,
                    "LEASE_HEARTBEAT_UNAVAILABLE",
                    "无法启动任务执行心跳",
                    false);
            return;
        }

        DomainException domainFailure = null;
        RuntimeException unexpectedFailure = null;
        try (lease) {
            lease.assertOwned();
            publishStarted(task, executionId);
            try {
                if (task.taskType() == TaskType.CONTENT_COMMENTS) {
                    commentCollectionService.collect(task, executionId, lease);
                } else {
                    creatorDiscoveryService.discover(task, executionId, lease);
                }
                lease.assertOwned();
            } catch (DomainException exception) {
                domainFailure = exception;
            } catch (RuntimeException exception) {
                unexpectedFailure = exception;
            }
        } catch (DomainException exception) {
            domainFailure = exception;
        } catch (RuntimeException exception) {
            unexpectedFailure = exception;
        }

        if (lease.isLost()
                || (domainFailure != null
                        && ExecutionLeaseManager.LEASE_LOST_CODE.equals(domainFailure.getCode()))) {
            log.info("Task worker stopped after losing its lease taskId={} executionId={}",
                    task.taskId(), executionId);
            return;
        }
        if (domainFailure != null) {
            completeDomainFailure(execution, task, leaseOwner, domainFailure);
            return;
        }
        if (unexpectedFailure != null) {
            completionService.completeFailure(
                    executionId,
                    leaseOwner,
                    "UNEXPECTED_ERROR",
                    "任务执行发生未预期错误",
                    false);
            log.error("Unexpected task execution failure taskId={} executionId={}",
                    task.taskId(), executionId, unexpectedFailure);
            return;
        }
        completionService.completeSuccess(executionId, leaseOwner);
    }

    private void completeDomainFailure(
            ExecutionRow execution,
            TaskRow task,
            String leaseOwner,
            DomainException exception) {
        String summary = sanitize(exception.getMessage());
        boolean cancelled = "EXECUTION_CANCELLED".equals(exception.getCode());
        if (!cancelled) {
            try {
                if (retryCoordinator.retryIfEligible(
                        execution, task, exception, summary, leaseOwner)) {
                    log.info("Task execution waiting for retry taskId={} executionId={} code={}",
                            task.taskId(), execution.executionId(), exception.getCode());
                    return;
                }
            } catch (RuntimeException retryFailure) {
                log.error("Unable to persist task retry executionId={}",
                        execution.executionId(), retryFailure);
            }
        }
        ExecutionCompletionService.Outcome outcome = completionService.completeFailure(
                execution.executionId(),
                leaseOwner,
                exception.getCode(),
                summary,
                cancelled);
        if (outcome == ExecutionCompletionService.Outcome.CANCELLED) {
            log.info("Task execution cancelled taskId={} executionId={}",
                    task.taskId(), execution.executionId());
        } else if (outcome == ExecutionCompletionService.Outcome.FAILED) {
            log.warn("Task execution failed taskId={} executionId={} code={}",
                    task.taskId(), execution.executionId(), exception.getCode());
        }
    }

    private void publishStarted(TaskRow task, long executionId) {
        try {
            eventPublisher.publish(
                    task.taskId(),
                    executionId,
                    "execution.updated",
                    "任务开始执行",
                    Map.of("status", "RUNNING"));
        } catch (RuntimeException exception) {
            log.warn("Unable to publish execution start event executionId={}", executionId, exception);
        }
    }

    private String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "任务执行失败";
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
