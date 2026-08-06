package com.hy.bilicomment.interfaces.web.dto;

import java.time.Instant;
import java.util.List;

public final class TaskDtos {

    private TaskDtos() {}

    public record TaskSource(String type, String id, String title) {}

    public record ParentTask(String taskId, String name) {}

    public record DiscoveredSummary(
            long total,
            long neverStarted,
            long queuedOrRunning,
            long retryWaiting,
            long errors,
            long commentsInserted24h,
            Instant newestDiscoveredAt) {}

    public record TaskSummary(
            String id,
            long version,
            String name,
            String kind,
            TaskSource source,
            String collectionMode,
            String desiredState,
            String runtimeState,
            String health,
            String scheduleLabel,
            String credentialName,
            Instant lastSuccessAt,
            Instant nextRunAt,
            long latestInsertedCount,
            Instant updatedAt,
            String origin,
            String relationMode,
            ParentTask parentTask,
            DiscoveredSummary discoveredSummary) {}

    public record TaskDetail(
            String id,
            long version,
            String name,
            String kind,
            TaskSource source,
            String collectionMode,
            String desiredState,
            String runtimeState,
            String health,
            String scheduleLabel,
            String credentialName,
            Instant lastSuccessAt,
            Instant nextRunAt,
            long latestInsertedCount,
            Instant updatedAt,
            String origin,
            String relationMode,
            ParentTask parentTask,
            DiscoveredSummary discoveredSummary,
            String remark,
            Instant createdAt,
            List<String> watchedContentTypes) {}

    public record DiscoveredTaskItem(
            TaskSummary task,
            long commentsTotal,
            long commentsInserted24h,
            Instant firstDiscoveredAt,
            Instant lastDiscoveredAt,
            String firstDiscoveredExecutionId,
            String lastDiscoveredExecutionId) {}

    public record TaskExecution(
            String id,
            String taskId,
            String trigger,
            String status,
            String phase,
            long pagesFetched,
            long commentsDiscovered,
            long commentsInserted,
            long duplicatesSkipped,
            int retryCount,
            String cursor,
            String errorSummary,
            Instant startedAt,
            Instant finishedAt,
            Instant lastHeartbeatAt) {}

    public record Comment(
            String id,
            String rpid,
            String parentRpid,
            String mid,
            String uname,
            String avatar,
            Integer currentLevel,
            String content,
            Instant ctime) {}
}
