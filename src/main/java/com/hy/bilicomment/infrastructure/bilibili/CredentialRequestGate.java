package com.hy.bilicomment.infrastructure.bilibili;

import com.hy.bilicomment.config.AppProperties;
import com.hy.bilicomment.domain.error.DomainException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.LockSupport;
import org.springframework.stereotype.Component;

@Component
class CredentialRequestGate {

    private final ConcurrentHashMap<Long, Gate> gates = new ConcurrentHashMap<>();
    private final long intervalNanos;
    private final int maximumConcurrency;

    CredentialRequestGate(AppProperties properties) {
        Duration interval = properties.getBilibili().getMinimumRequestInterval();
        this.intervalNanos = Math.max(0, interval.toNanos());
        this.maximumConcurrency = Math.max(
                1,
                properties.getBilibili().getMaximumConcurrentRequestsPerCredential());
    }

    Permit acquire(long credentialId) {
        Gate gate = gates.computeIfAbsent(
                credentialId,
                ignored -> new Gate(new Semaphore(maximumConcurrency, true), new AtomicLong()));
        try {
            gate.concurrency().acquire();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DomainException("REQUEST_INTERRUPTED", "Bilibili 请求被中断", exception);
        }
        try {
            if (intervalNanos > 0) {
                while (true) {
                    long now = System.nanoTime();
                    long current = gate.nextAllowedNanos().get();
                    long reserved = Math.max(now, current);
                    if (gate.nextAllowedNanos().compareAndSet(current, reserved + intervalNanos)) {
                        waitUntil(reserved);
                        break;
                    }
                }
            }
            return gate.concurrency()::release;
        } catch (RuntimeException exception) {
            gate.concurrency().release();
            throw exception;
        }
    }

    private void waitUntil(long targetNanos) {
        while (true) {
            long remaining = targetNanos - System.nanoTime();
            if (remaining <= 0) {
                return;
            }
            LockSupport.parkNanos(remaining);
            if (Thread.currentThread().isInterrupted()) {
                throw new DomainException("REQUEST_INTERRUPTED", "Bilibili 请求被中断");
            }
        }
    }

    private record Gate(Semaphore concurrency, AtomicLong nextAllowedNanos) {}

    @FunctionalInterface
    interface Permit extends AutoCloseable {
        @Override
        void close();
    }
}
