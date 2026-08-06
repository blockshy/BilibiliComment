package com.hy.bilicomment.application.execution;

import com.hy.bilicomment.config.AppProperties;
import com.hy.bilicomment.domain.error.DomainException;
import com.hy.bilicomment.infrastructure.persistence.mapper.ExecutionMapper;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

@Component
public class ExecutionLeaseManager {

    public static final String LEASE_LOST_CODE = "EXECUTION_LEASE_LOST";

    private static final Logger log = LoggerFactory.getLogger(ExecutionLeaseManager.class);

    private final ExecutionMapper executionMapper;
    private final ThreadPoolTaskScheduler scheduler;
    private final long leaseSeconds;
    private final Duration heartbeatDelay;

    public ExecutionLeaseManager(
            ExecutionMapper executionMapper,
            ThreadPoolTaskScheduler scheduler,
            AppProperties properties) {
        this.executionMapper = executionMapper;
        this.scheduler = scheduler;
        this.leaseSeconds = Math.max(
                1L, properties.getScheduler().getExecutionLeaseDuration().toSeconds());
        this.heartbeatDelay = properties.getScheduler().getExecutionHeartbeatDelay();
    }

    public Lease monitor(long executionId, String leaseOwner) {
        Lease lease = new Lease(executionId, leaseOwner, Thread.currentThread());
        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(lease::heartbeat, heartbeatDelay);
        if (future == null) {
            throw new IllegalStateException("Unable to schedule execution lease heartbeat");
        }
        lease.attach(future);
        return lease;
    }

    public final class Lease implements AutoCloseable {

        private final long executionId;
        private final String leaseOwner;
        private final Thread workerThread;
        private final AtomicBoolean lost = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile ScheduledFuture<?> heartbeatFuture;

        private Lease(long executionId, String leaseOwner, Thread workerThread) {
            this.executionId = executionId;
            this.leaseOwner = leaseOwner;
            this.workerThread = workerThread;
        }

        public String leaseOwner() {
            return leaseOwner;
        }

        public boolean isLost() {
            return lost.get();
        }

        public void assertOwned() {
            if (lost.get()
                    || !executionMapper.ownsLease(executionId, leaseOwner)) {
                loseLease();
                throw new DomainException(LEASE_LOST_CODE, "执行租约已失效");
            }
        }

        private void attach(ScheduledFuture<?> future) {
            this.heartbeatFuture = future;
        }

        private void heartbeat() {
            if (closed.get()) {
                return;
            }
            try {
                if (executionMapper.renewLease(
                                executionId,
                                leaseOwner,
                                leaseSeconds)
                        != 1) {
                    loseLease();
                }
            } catch (RuntimeException exception) {
                log.warn("Unable to renew execution lease executionId={}", executionId, exception);
            }
        }

        private void loseLease() {
            if (lost.compareAndSet(false, true) && workerThread != Thread.currentThread()) {
                workerThread.interrupt();
            }
        }

        @Override
        public void close() {
            closed.set(true);
            ScheduledFuture<?> future = heartbeatFuture;
            if (future != null) {
                future.cancel(false);
            }
        }
    }
}
