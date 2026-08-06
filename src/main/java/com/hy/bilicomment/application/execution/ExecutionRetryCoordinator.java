package com.hy.bilicomment.application.execution;

import com.hy.bilicomment.application.event.TaskEventPublisher;
import com.hy.bilicomment.config.AppProperties;
import com.hy.bilicomment.domain.error.DomainException;
import com.hy.bilicomment.infrastructure.persistence.entity.ExecutionRow;
import com.hy.bilicomment.infrastructure.persistence.entity.TaskRow;
import com.hy.bilicomment.infrastructure.persistence.mapper.ExecutionMapper;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ExecutionRetryCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ExecutionRetryCoordinator.class);
    private static final Set<String> RETRYABLE_CODES = Set.of(
            "BILIBILI_NETWORK_ERROR",
            "BILIBILI_RATE_LIMITED",
            "BILIBILI_UPSTREAM_UNAVAILABLE");

    private final ExecutionMapper executionMapper;
    private final PersistentExecutionDispatcher dispatcher;
    private final ExecutionCompletionService completionService;
    private final ThreadPoolTaskScheduler scheduler;
    private final TaskEventPublisher eventPublisher;
    private final AppProperties properties;
    private final Clock clock;
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final ReentrantLock recoveryLock = new ReentrantLock();
    private volatile ScheduledFuture<?> recoveryFuture;

    public ExecutionRetryCoordinator(
            ExecutionMapper executionMapper,
            PersistentExecutionDispatcher dispatcher,
            ExecutionCompletionService completionService,
            ThreadPoolTaskScheduler scheduler,
            TaskEventPublisher eventPublisher,
            AppProperties properties,
            Clock clock) {
        this.executionMapper = executionMapper;
        this.dispatcher = dispatcher;
        this.completionService = completionService;
        this.scheduler = scheduler;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public boolean retryIfEligible(
            ExecutionRow execution,
            TaskRow task,
            DomainException failure,
            String summary,
            String leaseOwner) {
        int retryLimit = properties.getBilibili().getMaximumExecutionRetries();
        if (!RETRYABLE_CODES.contains(failure.getCode())
                || execution.retryCount() >= retryLimit) {
            return false;
        }
        Instant nextRetryAt = clock.instant().plus(retryDelay(execution.retryCount()));
        if (executionMapper.markRetryWaiting(
                        execution.executionId(),
                        leaseOwner,
                        nextRetryAt,
                        failure.getCode(),
                        summary)
                != 1) {
            return false;
        }
        eventPublisher.publish(
                task.taskId(),
                execution.executionId(),
                "execution.updated",
                "上游请求暂时失败，任务将在稍后重试",
                Map.of(
                        "status", "RETRY_WAIT",
                        "code", failure.getCode(),
                        "retryCount", execution.retryCount() + 1,
                        "nextRetryAt", nextRetryAt.toString()));
        return true;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        safeRecover();
        recoveryFuture = scheduler.scheduleWithFixedDelay(
                this::safeRecover,
                properties.getScheduler().getRetryRecoveryDelay());
    }

    public void recover() {
        if (stopping.get() || !recoveryLock.tryLock()) {
            return;
        }
        try {
            int batchSize = properties.getScheduler().getExecutionRecoveryBatchSize();
            for (ExecutionRow execution : executionMapper.findRetryWaitingDue(clock.instant(), batchSize)) {
                if (execution.cancellationRequested()) {
                    completionService.cancelWaitingRetry(execution.executionId());
                } else if (executionMapper.resumeRetry(execution.executionId(), clock.instant()) == 1) {
                    dispatcher.dispatch(execution.executionId());
                }
            }
        } finally {
            recoveryLock.unlock();
        }
    }

    private void safeRecover() {
        try {
            recover();
        } catch (RuntimeException exception) {
            log.warn("Persisted execution retry recovery failed", exception);
        }
    }

    private Duration retryDelay(int completedRetries) {
        long multiplier = 1L << Math.min(completedRetries, 10);
        Duration base = properties.getBilibili().getExecutionRetryBaseDelay();
        try {
            return base.multipliedBy(multiplier);
        } catch (ArithmeticException exception) {
            return Duration.ofHours(24);
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
