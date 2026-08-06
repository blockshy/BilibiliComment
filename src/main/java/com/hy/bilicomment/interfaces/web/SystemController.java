package com.hy.bilicomment.interfaces.web;

import com.hy.bilicomment.application.event.SseEventService;
import com.hy.bilicomment.application.scheduling.DynamicTaskRegistry;
import com.hy.bilicomment.infrastructure.bilibili.BilibiliRequestMetrics;
import com.hy.bilicomment.infrastructure.persistence.mapper.ExecutionMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

    private final JdbcTemplate jdbcTemplate;
    private final ExecutionMapper executionMapper;
    private final DynamicTaskRegistry taskRegistry;
    private final ThreadPoolTaskExecutor workerExecutor;
    private final SseEventService eventService;
    private final BilibiliRequestMetrics bilibiliMetrics;
    private final Clock clock;

    public SystemController(
            JdbcTemplate jdbcTemplate,
            ExecutionMapper executionMapper,
            DynamicTaskRegistry taskRegistry,
            ThreadPoolTaskExecutor workerExecutor,
            SseEventService eventService,
            BilibiliRequestMetrics bilibiliMetrics,
            Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.executionMapper = executionMapper;
        this.taskRegistry = taskRegistry;
        this.workerExecutor = workerExecutor;
        this.eventService = eventService;
        this.bilibiliMetrics = bilibiliMetrics;
        this.clock = clock;
    }

    @GetMapping("/summary")
    public SystemSummary summary() {
        Instant measuredAt = clock.instant();
        boolean databaseUp = databaseUp();
        long recentFailures = databaseUp ? recentFailures(measuredAt) : 0;
        BilibiliRequestMetrics.Snapshot bilibili = bilibiliMetrics.snapshot();
        String bilibiliStatus = bilibiliStatus(bilibili);
        DynamicTaskRegistry.RegistrySnapshot scheduler = taskRegistry.snapshot();
        ThreadPoolExecutor worker = workerExecutor.getThreadPoolExecutor();
        String apiStatus = databaseUp && !"DOWN".equals(bilibiliStatus) ? "UP" : "DEGRADED";
        return new SystemSummary(
                apiStatus,
                databaseUp ? "UP" : "DOWN",
                bilibiliStatus,
                scheduler.enabled(),
                scheduler.activeRegistrations(),
                workerExecutor.getActiveCount(),
                workerExecutor.getMaxPoolSize(),
                worker.getQueue().size(),
                eventService.connectionCount(),
                recentFailures,
                bilibili.rateLimitedCount(),
                measuredAt);
    }

    private boolean databaseUp() {
        try {
            Integer value = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return value != null && value == 1;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private long recentFailures(Instant now) {
        try {
            return executionMapper.countFailuresSince(now.minus(Duration.ofHours(24)));
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private String bilibiliStatus(BilibiliRequestMetrics.Snapshot snapshot) {
        if (snapshot.lastSuccess() == null && snapshot.lastFailure() == null) {
            return "DEGRADED";
        }
        if (snapshot.consecutiveFailures() >= 3) {
            return "DOWN";
        }
        if (snapshot.consecutiveFailures() > 0) {
            return "DEGRADED";
        }
        return "UP";
    }

    public record SystemSummary(
            String api,
            String database,
            String bilibili,
            boolean schedulerEnabled,
            long schedulerActive,
            int workerActive,
            int workerPoolSize,
            int workerQueueSize,
            int sseConnections,
            long recentFailureCount,
            long rateLimitedCount,
            Instant measuredAt) {}
}
