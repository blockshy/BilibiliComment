package com.hy.bilicomment.application.scheduling;

import com.hy.bilicomment.domain.task.TaskType;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class AdaptiveSchedulePolicy {

    private static final Duration LONG_RUNNING_DELAY = Duration.ofMinutes(5);

    public Optional<Duration> nextDelay(TaskType taskType, Instant createdAt, Instant now) {
        if (taskType == null || createdAt == null || now == null) {
            throw new IllegalArgumentException("taskType, createdAt and now are required");
        }
        Duration age = Duration.between(createdAt, now);
        if (age.isNegative()) {
            age = Duration.ZERO;
        }
        long seconds = age.getSeconds();
        if (seconds <= hours(1)) {
            return Optional.of(Duration.ofSeconds(5));
        }
        if (seconds <= hours(6)) {
            return Optional.of(Duration.ofSeconds(10));
        }
        if (seconds <= hours(12)) {
            return Optional.of(Duration.ofSeconds(15));
        }
        if (seconds <= hours(18)) {
            return Optional.of(Duration.ofSeconds(20));
        }
        if (seconds <= hours(24)) {
            return Optional.of(Duration.ofSeconds(25));
        }
        if (seconds <= hours(30)) {
            return Optional.of(Duration.ofSeconds(30));
        }
        if (seconds <= hours(36)) {
            return Optional.of(Duration.ofSeconds(40));
        }
        if (seconds <= hours(48)) {
            return Optional.of(Duration.ofMinutes(1));
        }
        if (seconds <= hours(72)) {
            return Optional.of(LONG_RUNNING_DELAY);
        }
        return taskType == TaskType.CREATOR_WATCH
                ? Optional.of(LONG_RUNNING_DELAY)
                : Optional.empty();
    }

    private long hours(long hours) {
        return Duration.ofHours(hours).getSeconds();
    }
}
