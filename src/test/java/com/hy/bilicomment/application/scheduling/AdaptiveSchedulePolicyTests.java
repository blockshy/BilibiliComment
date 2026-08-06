package com.hy.bilicomment.application.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.hy.bilicomment.domain.task.TaskType;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AdaptiveSchedulePolicyTests {

    private final AdaptiveSchedulePolicy policy = new AdaptiveSchedulePolicy();
    private final Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void preservesAllLegacyAgeBandsWithRealDurations() {
        assertDelay(Duration.ofHours(1), Duration.ofSeconds(5));
        assertDelay(Duration.ofHours(6), Duration.ofSeconds(10));
        assertDelay(Duration.ofHours(12), Duration.ofSeconds(15));
        assertDelay(Duration.ofHours(18), Duration.ofSeconds(20));
        assertDelay(Duration.ofHours(24), Duration.ofSeconds(25));
        assertDelay(Duration.ofHours(30), Duration.ofSeconds(30));
        assertDelay(Duration.ofHours(36), Duration.ofSeconds(40));
        assertDelay(Duration.ofHours(48), Duration.ofMinutes(1));
        assertDelay(Duration.ofHours(72), Duration.ofMinutes(5));
        assertThat(policy.nextDelay(
                        TaskType.CONTENT_COMMENTS,
                        createdAt,
                        createdAt.plus(Duration.ofHours(72)).plusSeconds(1)))
                .isEmpty();
    }

    @Test
    void futureCreatedAtDoesNotCreateATimezoneShift() {
        assertThat(policy.nextDelay(TaskType.CONTENT_COMMENTS, createdAt.plusSeconds(30), createdAt))
                .contains(Duration.ofSeconds(5));
    }

    @Test
    void creatorWatchKeepsTheLastCadenceAfterTheContentLifecycleEnds() {
        assertThat(policy.nextDelay(
                        TaskType.CREATOR_WATCH,
                        createdAt,
                        createdAt.plus(Duration.ofDays(30))))
                .contains(Duration.ofMinutes(5));
    }

    private void assertDelay(Duration age, Duration expected) {
        assertThat(policy.nextDelay(TaskType.CONTENT_COMMENTS, createdAt, createdAt.plus(age)))
                .contains(expected);
    }
}
