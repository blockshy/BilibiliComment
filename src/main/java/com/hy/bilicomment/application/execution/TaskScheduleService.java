package com.hy.bilicomment.application.execution;

import com.hy.bilicomment.application.scheduling.AdaptiveSchedulePolicy;
import com.hy.bilicomment.domain.task.CollectionMode;
import com.hy.bilicomment.domain.task.DesiredState;
import com.hy.bilicomment.domain.task.ScheduleType;
import com.hy.bilicomment.infrastructure.persistence.entity.TaskRow;
import com.hy.bilicomment.infrastructure.persistence.mapper.TaskMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class TaskScheduleService {

    private final TaskMapper taskMapper;
    private final AdaptiveSchedulePolicy adaptiveSchedulePolicy;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public TaskScheduleService(
            TaskMapper taskMapper,
            AdaptiveSchedulePolicy adaptiveSchedulePolicy,
            Clock clock,
            ObjectMapper objectMapper) {
        this.taskMapper = taskMapper;
        this.adaptiveSchedulePolicy = adaptiveSchedulePolicy;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    public void afterExecution(TaskRow task, boolean succeeded) {
        Instant now = clock.instant();
        if (task.desiredState() == DesiredState.PAUSED) {
            taskMapper.updateSchedule(task.taskId(), now, null);
            return;
        }
        if (succeeded && task.collectionMode() == CollectionMode.BACKFILL_ONLY) {
            taskMapper.pauseAfterCompletion(task.taskId(), now);
            return;
        }
        Instant next = switch (task.scheduleType()) {
            case MANUAL -> null;
            case ADAPTIVE -> adaptiveSchedulePolicy.nextDelay(task.taskType(), task.createdAt(), now)
                    .map(now::plus)
                    .orElse(null);
            case CRON -> {
                var nextTime = CronExpression.parse(task.cronExpression()).next(now.atZone(scheduleZone(task)));
                yield nextTime == null ? null : nextTime.toInstant();
            }
        };
        if (task.scheduleType() == ScheduleType.ADAPTIVE && next == null) {
            taskMapper.pauseAfterCompletion(task.taskId(), now);
        } else {
            taskMapper.updateSchedule(task.taskId(), now, next);
        }
    }

    private ZoneId scheduleZone(TaskRow task) {
        try {
            String zone = objectMapper.readTree(task.scheduleConfigJson()).path("zoneId").asText("Asia/Shanghai");
            return ZoneId.of(zone);
        } catch (Exception ignored) {
            return ZoneId.of("Asia/Shanghai");
        }
    }
}
