package com.hy.bilicomment.application.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hy.bilicomment.application.execution.TaskExecutionService;
import com.hy.bilicomment.config.AppProperties;
import com.hy.bilicomment.domain.task.CollectionMode;
import com.hy.bilicomment.domain.task.DesiredState;
import com.hy.bilicomment.domain.task.ScheduleType;
import com.hy.bilicomment.domain.task.SourceType;
import com.hy.bilicomment.domain.task.TaskType;
import com.hy.bilicomment.infrastructure.persistence.entity.TaskRow;
import com.hy.bilicomment.infrastructure.persistence.mapper.TaskMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class DynamicTaskRegistryTests {

    private static final Instant NOW = Instant.parse("2026-07-14T00:00:00Z");

    @Mock
    private TaskMapper taskMapper;

    @Mock
    private TaskExecutionService executionService;

    @Mock
    private ThreadPoolTaskScheduler scheduler;

    @Mock
    private AdaptiveSchedulePolicy schedulePolicy;

    private DynamicTaskRegistry registry;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        properties.getScheduler().setEnabled(true);
        lenient().doAnswer(invocation -> {
                    invocation.<Runnable>getArgument(0).run();
                    return null;
                })
                .when(scheduler)
                .execute(any(Runnable.class));
        registry = new DynamicTaskRegistry(
                taskMapper,
                executionService,
                scheduler,
                schedulePolicy,
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new ObjectMapper());
    }

    @Test
    void repeatedReconcileKeepsEquivalentRegistration() {
        TaskRow initial = cronTask(7L, "0 */5 * * * *", 0L);
        TaskRow metadataOnlyChange = cronTask(7L, "0 */5 * * * *", 1L);
        TestScheduledFuture future = new TestScheduledFuture();
        doReturn(List.of(initial), List.of(metadataOnlyChange))
                .when(taskMapper)
                .findSchedulable();
        doReturn(future).when(scheduler).schedule(any(Runnable.class), any(Trigger.class));

        reconcileTwice();

        verify(scheduler, times(1)).schedule(any(Runnable.class), any(Trigger.class));
        assertThat(future.cancellationCount()).isZero();
        assertThat(registry.snapshot())
                .isEqualTo(new DynamicTaskRegistry.RegistrySnapshot(true, 1L, 1L));
    }

    @Test
    void changedScheduleReplacesExistingRegistrationOnce() {
        TaskRow original = cronTask(7L, "0 */5 * * * *", 0L);
        TaskRow changed = cronTask(7L, "0 */10 * * * *", 1L);
        TestScheduledFuture originalFuture = new TestScheduledFuture();
        TestScheduledFuture replacementFuture = new TestScheduledFuture();
        doReturn(List.of(original), List.of(changed)).when(taskMapper).findSchedulable();
        doReturn(originalFuture, replacementFuture)
                .when(scheduler)
                .schedule(any(Runnable.class), any(Trigger.class));

        reconcileTwice();

        verify(scheduler, times(2)).schedule(any(Runnable.class), any(Trigger.class));
        assertThat(originalFuture.cancellationCount()).isEqualTo(1);
        assertThat(replacementFuture.cancellationCount()).isZero();
        assertThat(registry.snapshot())
                .isEqualTo(new DynamicTaskRegistry.RegistrySnapshot(true, 1L, 1L));
    }

    @Test
    void pausedTaskDisappearingFromDesiredSetCancelsRegistration() {
        TestScheduledFuture future = new TestScheduledFuture();
        doReturn(List.of(cronTask(7L, "0 */5 * * * *", 0L)), List.of())
                .when(taskMapper)
                .findSchedulable();
        doReturn(future).when(scheduler).schedule(any(Runnable.class), any(Trigger.class));

        reconcileTwice();

        assertThat(future.cancellationCount()).isEqualTo(1);
        assertThat(registry.snapshot())
                .isEqualTo(new DynamicTaskRegistry.RegistrySnapshot(true, 0L, 0L));
    }

    @Test
    void invalidCronIsIgnoredWithoutPollutingRegistry() {
        doReturn(List.of(cronTask(7L, "definitely-not-a-cron", 0L)))
                .when(taskMapper)
                .findSchedulable();

        assertThatCode(registry::reconcileNow).doesNotThrowAnyException();

        verify(scheduler, never()).schedule(any(Runnable.class), any(Trigger.class));
        assertThat(registry.snapshot())
                .isEqualTo(new DynamicTaskRegistry.RegistrySnapshot(true, 0L, 0L));
    }

    @Test
    void invalidCronReplacementCancelsLastRegistrationAndLeavesNoStaleEntry() {
        TestScheduledFuture originalFuture = new TestScheduledFuture();
        doReturn(
                        List.of(cronTask(7L, "0 */5 * * * *", 0L)),
                        List.of(cronTask(7L, "definitely-not-a-cron", 1L)))
                .when(taskMapper)
                .findSchedulable();
        doReturn(originalFuture)
                .when(scheduler)
                .schedule(any(Runnable.class), any(Trigger.class));

        reconcileTwice();

        verify(scheduler, times(1)).schedule(any(Runnable.class), any(Trigger.class));
        assertThat(originalFuture.cancellationCount()).isEqualTo(1);
        assertThat(registry.snapshot())
                .isEqualTo(new DynamicTaskRegistry.RegistrySnapshot(true, 0L, 0L));
    }

    @Test
    void creatorWatchOlderThan72HoursUsesTypedFallbackWhenNextExecutionIsMissing() {
        TaskRow task = adaptiveTask(TaskType.CREATOR_WATCH, NOW.minus(Duration.ofDays(30)));
        TestScheduledFuture future = new TestScheduledFuture();
        doReturn(List.of(task)).when(taskMapper).findSchedulable();
        doReturn(Optional.of(Duration.ofMinutes(5)))
                .when(schedulePolicy)
                .nextDelay(TaskType.CREATOR_WATCH, task.createdAt(), NOW);
        ArgumentCaptor<Instant> scheduledAt = ArgumentCaptor.forClass(Instant.class);
        doReturn(future)
                .when(scheduler)
                .schedule(any(Runnable.class), scheduledAt.capture());

        registry.reconcileNow();

        assertThat(scheduledAt.getValue()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        assertThat(registry.snapshot())
                .isEqualTo(new DynamicTaskRegistry.RegistrySnapshot(true, 1L, 1L));
    }

    @Test
    void contentTaskKeepsItsExistingExhaustedAdaptiveLifecycle() {
        TaskRow task = adaptiveTask(TaskType.CONTENT_COMMENTS, NOW.minus(Duration.ofDays(30)));
        doReturn(List.of(task)).when(taskMapper).findSchedulable();
        doReturn(Optional.empty())
                .when(schedulePolicy)
                .nextDelay(TaskType.CONTENT_COMMENTS, task.createdAt(), NOW);

        registry.reconcileNow();

        verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));
        assertThat(registry.snapshot())
                .isEqualTo(new DynamicTaskRegistry.RegistrySnapshot(true, 0L, 0L));
    }

    @Test
    void deletedTaskIsRemovedWithoutReplacingRemainingTask() {
        TaskRow deleted = cronTask(7L, "0 */5 * * * *", 0L);
        TaskRow retained = cronTask(8L, "0 */10 * * * *", 0L);
        TestScheduledFuture deletedFuture = new TestScheduledFuture();
        TestScheduledFuture retainedFuture = new TestScheduledFuture();
        doReturn(List.of(deleted, retained), List.of(retained))
                .when(taskMapper)
                .findSchedulable();
        doReturn(deletedFuture, retainedFuture)
                .when(scheduler)
                .schedule(any(Runnable.class), any(Trigger.class));

        reconcileTwice();

        verify(scheduler, times(2)).schedule(any(Runnable.class), any(Trigger.class));
        assertThat(deletedFuture.cancellationCount()).isEqualTo(1);
        assertThat(retainedFuture.cancellationCount()).isZero();
        assertThat(registry.snapshot())
                .isEqualTo(new DynamicTaskRegistry.RegistrySnapshot(true, 1L, 1L));
    }

    @Test
    void repeatedPendingRequestsOnlyQueueOneReconcileRunnable() {
        doAnswer(invocation -> null)
                .when(scheduler)
                .execute(any(Runnable.class));

        registry.reconcileNow();
        registry.reconcileNow();

        verify(scheduler, times(1)).execute(any(Runnable.class));
    }

    @Test
    void stopPreventsLaterReconciliationFromRegisteringTasks() {
        TestScheduledFuture future = new TestScheduledFuture();
        doReturn(List.of(cronTask(7L, "0 */5 * * * *", 0L)))
                .when(taskMapper)
                .findSchedulable();
        doReturn(future).when(scheduler).schedule(any(Runnable.class), any(Trigger.class));

        registry.reconcileNow();
        registry.stop();
        registry.reconcileNow();

        verify(scheduler, times(1)).execute(any(Runnable.class));
        assertThat(future.cancellationCount()).isEqualTo(1);
        assertThat(registry.snapshot())
                .isEqualTo(new DynamicTaskRegistry.RegistrySnapshot(true, 0L, 0L));
    }

    @Test
    void registeredTriggerDoesNotQueueWorkAfterStop() {
        TestScheduledFuture future = new TestScheduledFuture();
        doReturn(List.of(cronTask(7L, "0 */5 * * * *", 0L)))
                .when(taskMapper)
                .findSchedulable();
        ArgumentCaptor<Runnable> trigger = ArgumentCaptor.forClass(Runnable.class);
        doReturn(future).when(scheduler).schedule(trigger.capture(), any(Trigger.class));

        registry.reconcileNow();
        registry.stop();
        trigger.getValue().run();

        verify(executionService, never()).queue(anyLong(), any());
    }

    private void reconcileTwice() {
        registry.reconcileNow();
        registry.reconcileNow();
    }

    private static TaskRow cronTask(long taskId, String cronExpression, long version) {
        return new TaskRow(
                taskId,
                "Fixture task v" + version,
                TaskType.CONTENT_COMMENTS,
                SourceType.VIDEO,
                "BVFixture" + taskId,
                CollectionMode.FOLLOW_ONLY,
                DesiredState.ACTIVE,
                ScheduleType.CRON,
                cronExpression,
                null,
                "{}",
                "{\"zoneId\":\"Asia/Shanghai\"}",
                null,
                null,
                null,
                version,
                NOW.minusSeconds(3600),
                NOW.plusSeconds(version));
    }

    private static TaskRow adaptiveTask(TaskType taskType, Instant createdAt) {
        return new TaskRow(
                7L,
                "Adaptive fixture",
                taskType,
                taskType == TaskType.CREATOR_WATCH ? SourceType.CREATOR : SourceType.VIDEO,
                taskType == TaskType.CREATOR_WATCH ? "42" : "BVFixture",
                CollectionMode.FOLLOW_ONLY,
                DesiredState.ACTIVE,
                ScheduleType.ADAPTIVE,
                null,
                null,
                "{}",
                "{\"zoneId\":\"Asia/Shanghai\"}",
                null,
                null,
                null,
                0L,
                createdAt,
                NOW.minusSeconds(60));
    }

    private static final class TestScheduledFuture implements ScheduledFuture<Void> {

        private int cancellationCount;
        private boolean cancelled;

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancellationCount++;
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return cancelled;
        }

        @Override
        public Void get() {
            return null;
        }

        @Override
        public Void get(long timeout, TimeUnit unit) {
            return null;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return 0L;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }

        int cancellationCount() {
            return cancellationCount;
        }
    }
}
