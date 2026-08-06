package com.hy.bilicomment.application.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hy.bilicomment.application.event.TaskEventPublisher;
import com.hy.bilicomment.config.AppProperties;
import com.hy.bilicomment.domain.error.DomainException;
import com.hy.bilicomment.domain.execution.ExecutionStatus;
import com.hy.bilicomment.domain.execution.TriggerType;
import com.hy.bilicomment.domain.task.CollectionMode;
import com.hy.bilicomment.domain.task.DesiredState;
import com.hy.bilicomment.domain.task.ScheduleType;
import com.hy.bilicomment.domain.task.SourceType;
import com.hy.bilicomment.domain.task.TaskType;
import com.hy.bilicomment.infrastructure.persistence.entity.ExecutionRow;
import com.hy.bilicomment.infrastructure.persistence.entity.TaskRow;
import com.hy.bilicomment.infrastructure.persistence.mapper.ExecutionMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@ExtendWith(MockitoExtension.class)
class ExecutionRetryCoordinatorTests {

    private static final Instant NOW = Instant.parse("2026-07-14T00:00:00Z");
    private static final String LEASE_OWNER = "fixture-instance:31:claim";

    @Mock private ExecutionMapper executionMapper;
    @Mock private PersistentExecutionDispatcher dispatcher;
    @Mock private ExecutionCompletionService completionService;
    @Mock private ThreadPoolTaskScheduler scheduler;
    @Mock private TaskEventPublisher eventPublisher;

    private AppProperties properties;
    private ExecutionRetryCoordinator coordinator;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        properties.getScheduler().setEnabled(false);
        properties.getBilibili().setMaximumExecutionRetries(2);
        properties.getBilibili().setExecutionRetryBaseDelay(Duration.ofSeconds(10));
        coordinator = new ExecutionRetryCoordinator(
                executionMapper,
                dispatcher,
                completionService,
                scheduler,
                eventPublisher,
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void persistsRetryEvenWhenDynamicTaskSchedulingIsDisabled() {
        when(executionMapper.markRetryWaiting(
                        31L,
                        LEASE_OWNER,
                        NOW.plusSeconds(10),
                        "BILIBILI_NETWORK_ERROR",
                        "network unavailable"))
                .thenReturn(1);

        boolean accepted = coordinator.retryIfEligible(
                execution(ExecutionStatus.RUNNING, 0, null, false),
                task(),
                new DomainException("BILIBILI_NETWORK_ERROR", "network unavailable"),
                "network unavailable",
                LEASE_OWNER);

        assertThat(accepted).isTrue();
        verify(eventPublisher).publish(
                anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void stopsRetryingAfterTheConfiguredFiniteBudget() {
        boolean accepted = coordinator.retryIfEligible(
                execution(ExecutionStatus.RUNNING, 2, null, false),
                task(),
                new DomainException("BILIBILI_RATE_LIMITED", "limited"),
                "limited",
                LEASE_OWNER);

        assertThat(accepted).isFalse();
        verify(executionMapper, never()).markRetryWaiting(anyLong(), any(), any(), any(), any());
    }

    @Test
    void periodicallyResumesDueRowsThroughTheDurableDispatcher() {
        ExecutionRow due = execution(ExecutionStatus.RETRY_WAIT, 1, NOW, false);
        when(executionMapper.findRetryWaitingDue(NOW, 100)).thenReturn(List.of(due));
        when(executionMapper.resumeRetry(31L, NOW)).thenReturn(1);

        coordinator.recover();

        verify(executionMapper).resumeRetry(31L, NOW);
        verify(dispatcher).dispatch(31L);
    }

    @Test
    void periodicallyCompletesCancelledRetryWaitRows() {
        ExecutionRow cancelled = execution(ExecutionStatus.RETRY_WAIT, 1, NOW, true);
        when(executionMapper.findRetryWaitingDue(NOW, 100)).thenReturn(List.of(cancelled));

        coordinator.recover();

        verify(completionService).cancelWaitingRetry(31L);
        verify(executionMapper, never()).resumeRetry(anyLong(), any());
        verify(dispatcher, never()).dispatch(anyLong());
    }

    private ExecutionRow execution(
            ExecutionStatus status, int retryCount, Instant nextRetryAt, boolean cancellationRequested) {
        return new ExecutionRow(
                31L, 7L, TriggerType.MANUAL, status, null, 1,
                0, 0, 0, 0, retryCount, "{}", cancellationRequested,
                null, null, "trace", null, NOW, NOW, NOW,
                nextRetryAt, null, NOW, NOW);
    }

    private TaskRow task() {
        return new TaskRow(
                7L, "Fixture", TaskType.CONTENT_COMMENTS, SourceType.VIDEO, "BVFixture",
                CollectionMode.FOLLOW_ONLY, DesiredState.ACTIVE, ScheduleType.ADAPTIVE,
                null, null, "{}", "{}", null, null, NOW.plusSeconds(30), 0, NOW, NOW);
    }
}
