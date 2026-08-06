package com.hy.bilicomment.infrastructure.bilibili;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class BilibiliRequestMetrics {

    private final Clock clock;
    private final AtomicReference<Instant> lastSuccess = new AtomicReference<>();
    private final AtomicReference<Instant> lastFailure = new AtomicReference<>();
    private final AtomicLong rateLimited = new AtomicLong();
    private final AtomicLong consecutiveFailures = new AtomicLong();

    public BilibiliRequestMetrics(Clock clock) {
        this.clock = clock;
    }

    void success() {
        lastSuccess.set(clock.instant());
        consecutiveFailures.set(0);
    }

    void failure() {
        lastFailure.set(clock.instant());
        consecutiveFailures.incrementAndGet();
    }

    void rateLimited() {
        rateLimited.incrementAndGet();
        failure();
    }

    public Snapshot snapshot() {
        return new Snapshot(
                lastSuccess.get(),
                lastFailure.get(),
                rateLimited.get(),
                consecutiveFailures.get());
    }

    public record Snapshot(
            Instant lastSuccess,
            Instant lastFailure,
            long rateLimitedCount,
            long consecutiveFailures) {}
}
