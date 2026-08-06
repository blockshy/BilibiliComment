package com.hy.bilicomment.application.scheduling;

import com.hy.bilicomment.application.execution.TaskExecutionService;
import com.hy.bilicomment.config.AppProperties;
import com.hy.bilicomment.domain.error.DomainException;
import com.hy.bilicomment.domain.execution.TriggerType;
import com.hy.bilicomment.domain.task.ScheduleType;
import com.hy.bilicomment.infrastructure.persistence.entity.TaskRow;
import com.hy.bilicomment.infrastructure.persistence.mapper.TaskMapper;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class DynamicTaskRegistry {

    private static final Logger log = LoggerFactory.getLogger(DynamicTaskRegistry.class);

    private final TaskMapper taskMapper;
    private final TaskExecutionService executionService;
    private final ThreadPoolTaskScheduler scheduler;
    private final AdaptiveSchedulePolicy schedulePolicy;
    private final AppProperties properties;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ReentrantLock reconcileLock = new ReentrantLock();
    private final Map<Long, Registration> registrations = new HashMap<>();
    private final AtomicBoolean reconcileRequested = new AtomicBoolean();
    private final AtomicBoolean reconcileQueued = new AtomicBoolean();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private ScheduledFuture<?> reconcileFuture;

    public DynamicTaskRegistry(
            TaskMapper taskMapper,
            TaskExecutionService executionService,
            ThreadPoolTaskScheduler scheduler,
            AdaptiveSchedulePolicy schedulePolicy,
            AppProperties properties,
            Clock clock,
            ObjectMapper objectMapper) {
        this.taskMapper = taskMapper;
        this.executionService = executionService;
        this.scheduler = scheduler;
        this.schedulePolicy = schedulePolicy;
        this.properties = properties;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (stopping.get() || !properties.getScheduler().isEnabled()) {
            log.info("Dynamic task scheduling is disabled");
            return;
        }
        safeReconcile();
        reconcileFuture = scheduler.scheduleWithFixedDelay(
                this::safeReconcile,
                properties.getScheduler().getReconcileDelay());
    }

    public void reconcileNow() {
        if (!properties.getScheduler().isEnabled() || stopping.get()) {
            return;
        }
        reconcileRequested.set(true);
        if (reconcileQueued.compareAndSet(false, true)) {
            try {
                scheduler.execute(this::drainReconcileRequests);
            } catch (RuntimeException exception) {
                reconcileQueued.set(false);
                throw exception;
            }
        }
    }

    private void drainReconcileRequests() {
        try {
            do {
                reconcileRequested.set(false);
                safeReconcile();
            } while (reconcileRequested.get() && !stopping.get());
        } finally {
            reconcileQueued.set(false);
            if (reconcileRequested.get() && !stopping.get()) {
                reconcileNow();
            }
        }
    }

    private void safeReconcile() {
        if (stopping.get() || !reconcileLock.tryLock()) {
            return;
        }
        try {
            if (stopping.get()) {
                return;
            }
            List<TaskRow> desired = taskMapper.findSchedulable();
            applyDiff(desired);
        } catch (RuntimeException exception) {
            log.warn("Task reconciliation failed; keeping the last valid schedule", exception);
        } finally {
            reconcileLock.unlock();
        }
    }

    private void applyDiff(List<TaskRow> desired) {
        Map<Long, TaskRow> desiredById = new HashMap<>();
        desired.forEach(task -> desiredById.put(task.taskId(), task));

        registrations.entrySet().removeIf(entry -> {
            if (desiredById.containsKey(entry.getKey())) {
                return false;
            }
            entry.getValue().future().cancel(false);
            return true;
        });

        for (TaskRow task : desired) {
            ScheduleFingerprint fingerprint = ScheduleFingerprint.from(task);
            Registration current = registrations.get(task.taskId());
            if (current != null
                    && current.fingerprint().equals(fingerprint)
                    && !current.future().isDone()
                    && !current.future().isCancelled()) {
                continue;
            }
            if (current != null) {
                current.future().cancel(false);
            }
            ScheduledFuture<?> future = schedule(task);
            if (future != null) {
                registrations.put(task.taskId(), new Registration(fingerprint, future));
            } else {
                registrations.remove(task.taskId());
            }
        }
    }

    private ScheduledFuture<?> schedule(TaskRow task) {
        if (stopping.get()) {
            return null;
        }
        Runnable trigger = () -> trigger(task.taskId());
        if (task.scheduleType() == ScheduleType.CRON) {
            try {
                return scheduler.schedule(trigger, new CronTrigger(task.cronExpression(), scheduleZone(task)));
            } catch (IllegalArgumentException exception) {
                log.warn("Ignoring invalid cron taskId={}", task.taskId());
                return null;
            }
        }
        Instant now = clock.instant();
        Instant next = task.nextExecutionAt();
        if (next == null) {
            next = schedulePolicy.nextDelay(task.taskType(), task.createdAt(), now)
                    .map(now::plus)
                    .orElse(null);
        }
        if (next == null) {
            return null;
        }
        if (!next.isAfter(now)) {
            next = now.plusSeconds(1);
        }
        return scheduler.schedule(trigger, next);
    }

    private ZoneId scheduleZone(TaskRow task) {
        try {
            String zone = objectMapper.readTree(task.scheduleConfigJson()).path("zoneId").asText("Asia/Shanghai");
            return ZoneId.of(zone);
        } catch (Exception ignored) {
            return ZoneId.of("Asia/Shanghai");
        }
    }

    private void trigger(long taskId) {
        if (stopping.get()) {
            return;
        }
        try {
            executionService.queue(taskId, TriggerType.SCHEDULED);
        } catch (DomainException exception) {
            if (!"TASK_ALREADY_RUNNING".equals(exception.getCode())
                    && !"TASK_PAUSED".equals(exception.getCode())) {
                log.warn("Unable to queue scheduled taskId={} code={}", taskId, exception.getCode());
            }
        } finally {
            if (!stopping.get()) {
                scheduler.schedule(this::safeReconcile, clock.instant().plusSeconds(1));
            }
        }
    }

    @PreDestroy
    public void stop() {
        stopping.set(true);
        reconcileRequested.set(false);
        if (reconcileFuture != null) {
            reconcileFuture.cancel(false);
        }
        reconcileLock.lock();
        try {
            registrations.values().forEach(registration -> registration.future().cancel(false));
            registrations.clear();
        } finally {
            reconcileLock.unlock();
        }
    }

    public RegistrySnapshot snapshot() {
        reconcileLock.lock();
        try {
            long active = registrations.values().stream()
                    .filter(registration -> !registration.future().isDone() && !registration.future().isCancelled())
                    .count();
            return new RegistrySnapshot(properties.getScheduler().isEnabled(), active, registrations.size());
        } finally {
            reconcileLock.unlock();
        }
    }

    private record Registration(ScheduleFingerprint fingerprint, ScheduledFuture<?> future) {}

    private record ScheduleFingerprint(
            ScheduleType scheduleType,
            String cronExpression,
            String scheduleConfigJson,
            Instant nextExecutionAt) {

        static ScheduleFingerprint from(TaskRow task) {
            return new ScheduleFingerprint(
                    task.scheduleType(),
                    Objects.toString(task.cronExpression(), ""),
                    Objects.toString(task.scheduleConfigJson(), "{}"),
                    task.nextExecutionAt());
        }
    }

    public record RegistrySnapshot(boolean enabled, long activeRegistrations, long knownRegistrations) {}
}
