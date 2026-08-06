package com.hy.bilicomment.application.execution;

import com.hy.bilicomment.application.event.TaskEventPublisher;
import com.hy.bilicomment.infrastructure.persistence.entity.ExecutionRow;
import com.hy.bilicomment.infrastructure.persistence.entity.TaskRow;
import com.hy.bilicomment.infrastructure.persistence.mapper.ExecutionMapper;
import com.hy.bilicomment.infrastructure.persistence.mapper.TaskMapper;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExecutionCompletionService {

    private final ExecutionMapper executionMapper;
    private final TaskMapper taskMapper;
    private final TaskScheduleService taskScheduleService;
    private final TaskEventPublisher eventPublisher;

    public ExecutionCompletionService(
            ExecutionMapper executionMapper,
            TaskMapper taskMapper,
            TaskScheduleService taskScheduleService,
            TaskEventPublisher eventPublisher) {
        this.executionMapper = executionMapper;
        this.taskMapper = taskMapper;
        this.taskScheduleService = taskScheduleService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public Outcome completeSuccess(long executionId, String leaseOwner) {
        CompletionContext context = lockContext(executionId);
        if (executionMapper.markSucceeded(executionId, leaseOwner) == 1) {
            taskScheduleService.afterExecution(context.task(), true);
            eventPublisher.publish(
                    context.task().taskId(),
                    executionId,
                    "execution.updated",
                    "任务执行成功",
                    Map.of("status", "SUCCEEDED"));
            return Outcome.SUCCEEDED;
        }
        if (executionMapper.markCancelledOwned(executionId, leaseOwner, "任务已请求停止") == 1) {
            publishCancelled(context.task(), executionId);
            return Outcome.CANCELLED;
        }
        return Outcome.LEASE_LOST;
    }

    @Transactional
    public Outcome completeFailure(
            long executionId,
            String leaseOwner,
            String errorCode,
            String summary,
            boolean cancelled) {
        CompletionContext context = lockContext(executionId);
        int changed = cancelled
                ? executionMapper.markCancelledOwned(executionId, leaseOwner, summary)
                : executionMapper.markFailedOwned(executionId, leaseOwner, errorCode, summary);
        if (!cancelled
                && changed != 1
                && executionMapper.markCancelledOwned(executionId, leaseOwner, "任务已请求停止") == 1) {
            publishCancelled(context.task(), executionId);
            return Outcome.CANCELLED;
        }
        if (changed != 1) {
            return Outcome.LEASE_LOST;
        }
        taskScheduleService.afterExecution(context.task(), false);
        eventPublisher.publish(
                context.task().taskId(),
                executionId,
                "execution.updated",
                cancelled ? "任务执行已取消" : "任务执行失败",
                Map.of(
                        "status", cancelled ? "CANCELLED" : "FAILED",
                        "code", cancelled ? "EXECUTION_CANCELLED" : errorCode));
        return cancelled ? Outcome.CANCELLED : Outcome.FAILED;
    }

    @Transactional
    public boolean cancelWaitingRetry(long executionId) {
        CompletionContext context = lockContext(executionId);
        if (executionMapper.markCancelled(executionId, "任务已请求停止") != 1) {
            return false;
        }
        publishCancelled(context.task(), executionId);
        return true;
    }

    private CompletionContext lockContext(long executionId) {
        ExecutionRow execution = executionMapper.findById(executionId)
                .orElseThrow(() -> new IllegalStateException("Execution disappeared: " + executionId));
        TaskRow task = taskMapper.findByIdForUpdate(execution.taskId())
                .orElseThrow(() -> new IllegalStateException("Execution task disappeared: " + execution.taskId()));
        return new CompletionContext(execution, task);
    }

    private void publishCancelled(TaskRow task, long executionId) {
        taskScheduleService.afterExecution(task, false);
        eventPublisher.publish(
                task.taskId(),
                executionId,
                "execution.updated",
                "任务执行已取消",
                Map.of("status", "CANCELLED", "code", "EXECUTION_CANCELLED"));
    }

    public enum Outcome {
        SUCCEEDED,
        FAILED,
        CANCELLED,
        LEASE_LOST
    }

    private record CompletionContext(ExecutionRow execution, TaskRow task) {}
}
