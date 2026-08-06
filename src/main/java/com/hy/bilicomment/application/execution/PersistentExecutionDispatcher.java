package com.hy.bilicomment.application.execution;

import com.hy.bilicomment.application.event.TaskEventPublisher;
import com.hy.bilicomment.config.AppProperties;
import com.hy.bilicomment.domain.execution.ExecutionStatus;
import com.hy.bilicomment.domain.task.CollectionMode;
import com.hy.bilicomment.infrastructure.persistence.entity.ExecutionRow;
import com.hy.bilicomment.infrastructure.persistence.entity.TaskRow;
import com.hy.bilicomment.infrastructure.persistence.mapper.ExecutionMapper;
import com.hy.bilicomment.infrastructure.persistence.mapper.TaskMapper;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

@Component
public class PersistentExecutionDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PersistentExecutionDispatcher.class);

    private final ExecutionMapper executionMapper;
    private final TaskMapper taskMapper;
    private final ThreadPoolTaskExecutor taskWorkerExecutor;
    private final ThreadPoolTaskExecutor backfillTaskExecutor;
    private final ThreadPoolTaskScheduler scheduler;
    private final ObjectProvider<TaskExecutionEngine> engineProvider;
    private final TaskEventPublisher eventPublisher;
    private final ExecutionInstanceIdentity instanceIdentity;
    private final AppProperties properties;
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    private final ReentrantLock recoveryLock = new ReentrantLock();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private volatile ScheduledFuture<?> recoveryFuture;

    public PersistentExecutionDispatcher(
            ExecutionMapper executionMapper,
            TaskMapper taskMapper,
            @Qualifier("taskWorkerExecutor") ThreadPoolTaskExecutor taskWorkerExecutor,
            @Qualifier("backfillTaskExecutor") ThreadPoolTaskExecutor backfillTaskExecutor,
            ThreadPoolTaskScheduler scheduler,
            ObjectProvider<TaskExecutionEngine> engineProvider,
            TaskEventPublisher eventPublisher,
            ExecutionInstanceIdentity instanceIdentity,
            AppProperties properties) {
        this.executionMapper = executionMapper;
        this.taskMapper = taskMapper;
        this.taskWorkerExecutor = taskWorkerExecutor;
        this.backfillTaskExecutor = backfillTaskExecutor;
        this.scheduler = scheduler;
        this.engineProvider = engineProvider;
        this.eventPublisher = eventPublisher;
        this.instanceIdentity = instanceIdentity;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        safeRecoverAndDispatch();
        recoveryFuture = scheduler.scheduleWithFixedDelay(
                this::safeRecoverAndDispatch,
                properties.getScheduler().getExecutionDispatchDelay());
    }

    public void dispatch(long executionId) {
        if (stopping.get() || !inFlight.add(executionId)) {
            return;
        }
        try {
            ExecutionRow execution = executionMapper.findById(executionId).orElse(null);
            if (execution == null || execution.status() != ExecutionStatus.QUEUED) {
                inFlight.remove(executionId);
                return;
            }
            TaskRow task = taskMapper.findById(execution.taskId()).orElse(null);
            if (task == null) {
                inFlight.remove(executionId);
                return;
            }
            executorFor(task).execute(() -> runClaimed(executionId));
        } catch (RejectedExecutionException exception) {
            inFlight.remove(executionId);
            log.debug("Worker queue full; execution remains queued executionId={}", executionId);
        } catch (RuntimeException exception) {
            inFlight.remove(executionId);
            throw exception;
        }
    }

    void recoverAndDispatch() {
        if (stopping.get() || !recoveryLock.tryLock()) {
            return;
        }
        try {
            int batchSize = properties.getScheduler().getExecutionRecoveryBatchSize();
            List<ExecutionRow> recovered = executionMapper.requeueExpiredLeases(batchSize);
            for (ExecutionRow execution : recovered) {
                publishRecovery(execution);
            }
            for (ExecutionRow execution : executionMapper.findQueued(batchSize)) {
                dispatch(execution.executionId());
            }
        } finally {
            recoveryLock.unlock();
        }
    }

    private void safeRecoverAndDispatch() {
        try {
            recoverAndDispatch();
        } catch (RuntimeException exception) {
            log.warn("Persistent execution recovery failed", exception);
        }
    }

    private void runClaimed(long executionId) {
        try {
            if (stopping.get()) {
                return;
            }
            String leaseOwner = instanceIdentity.newLeaseOwner(executionId);
            long leaseSeconds = Math.max(
                    1L, properties.getScheduler().getExecutionLeaseDuration().toSeconds());
            if (executionMapper.claimQueued(
                            executionId,
                            leaseOwner,
                            Thread.currentThread().getName(),
                            leaseSeconds)
                    != 1) {
                return;
            }
            engineProvider.getObject().execute(executionId, leaseOwner);
        } finally {
            inFlight.remove(executionId);
        }
    }

    private ThreadPoolTaskExecutor executorFor(TaskRow task) {
        return task.collectionMode() == CollectionMode.BACKFILL_ONLY
                ? backfillTaskExecutor
                : taskWorkerExecutor;
    }

    private void publishRecovery(ExecutionRow execution) {
        try {
            eventPublisher.publish(
                    execution.taskId(),
                    execution.executionId(),
                    "execution.updated",
                    "任务执行租约过期，已重新排队",
                    Map.of("status", "QUEUED", "code", "EXECUTION_LEASE_EXPIRED"));
        } catch (RuntimeException exception) {
            log.warn("Unable to publish execution recovery event executionId={}",
                    execution.executionId(), exception);
        }
    }

    @PreDestroy
    public void stop() {
        stopping.set(true);
        ScheduledFuture<?> future = recoveryFuture;
        if (future != null) {
            future.cancel(false);
        }
    }
}
