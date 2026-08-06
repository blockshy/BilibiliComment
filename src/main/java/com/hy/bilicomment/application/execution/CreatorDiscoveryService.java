package com.hy.bilicomment.application.execution;

import com.hy.bilicomment.application.credential.CredentialService;
import com.hy.bilicomment.application.event.TaskEventPublisher;
import com.hy.bilicomment.application.task.TaskService;
import com.hy.bilicomment.domain.error.DomainException;
import com.hy.bilicomment.domain.execution.ExecutionPhase;
import com.hy.bilicomment.domain.task.SourceType;
import com.hy.bilicomment.infrastructure.bilibili.BilibiliClient;
import com.hy.bilicomment.infrastructure.bilibili.BilibiliCredential;
import com.hy.bilicomment.infrastructure.bilibili.DiscoveredContent;
import com.hy.bilicomment.infrastructure.persistence.entity.TaskRow;
import com.hy.bilicomment.infrastructure.persistence.mapper.ExecutionMapper;
import java.util.LinkedHashMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class CreatorDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(CreatorDiscoveryService.class);

    private final BilibiliClient bilibiliClient;
    private final CredentialService credentialService;
    private final ExecutionFencedWriteService fencedWriteService;
    private final ExecutionMapper executionMapper;
    private final TaskEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public CreatorDiscoveryService(
            BilibiliClient bilibiliClient,
            CredentialService credentialService,
            ExecutionFencedWriteService fencedWriteService,
            ExecutionMapper executionMapper,
            TaskEventPublisher eventPublisher,
            ObjectMapper objectMapper) {
        this.bilibiliClient = bilibiliClient;
        this.credentialService = credentialService;
        this.fencedWriteService = fencedWriteService;
        this.executionMapper = executionMapper;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    public DiscoveryResult discover(
            TaskRow task,
            long executionId,
            ExecutionLeaseManager.Lease lease) {
        lease.assertOwned();
        BilibiliCredential credential = credentialService.resolve(task.credentialProfileId(), true);
        EnumSet<SourceType> watchedTypes = watchedTypes(task.sourceMetadataJson());
        lease.assertOwned();
        List<DiscoveredContent> contents = bilibiliClient.discoverCreatorContent(task.sourceId(), credential).stream()
                .filter(content -> watchedTypes.contains(content.sourceType()))
                .toList();
        lease.assertOwned();
        long created = 0;
        long reused = 0;
        long failed = 0;
        for (DiscoveredContent content : contents) {
            lease.assertOwned();
            if (executionMapper.isCancellationRequested(executionId)) {
                throw new DomainException("EXECUTION_CANCELLED", "任务已请求停止");
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("oid", content.oid());
            metadata.put("commentType", content.commentType());
            metadata.put("title", content.title());
            metadata.put("discoveredByTaskId", Long.toString(task.taskId()));
            try {
                TaskService.DiscoveredTaskResult child = fencedWriteService.createDiscovered(
                        task.taskId(),
                        executionId,
                        lease.leaseOwner(),
                        content.title(),
                        content.sourceType(),
                        content.sourceId(),
                        task.credentialProfileId(),
                        metadata);
                if (child.created()) {
                    created++;
                } else {
                    reused++;
                }
            } catch (DomainException exception) {
                failed++;
                log.warn("Unable to create discovered child task parentTaskId={} sourceType={} code={}",
                        task.taskId(), content.sourceType(), exception.getCode());
            }
        }
        // These legacy progress slots represent discovered contents, created tasks and reused
        // tasks for CREATOR_WATCH executions. API presentation labels them by task kind.
        if (executionMapper.updateProgress(
                        executionId,
                        lease.leaseOwner(),
                        ExecutionPhase.SCHEDULING_NEXT,
                        1,
                        contents.size(),
                        created,
                        reused,
                        "{}")
                != 1) {
            lease.assertOwned();
            throw new DomainException("EXECUTION_PROGRESS_REJECTED", "无法保存任务执行进度");
        }
        lease.assertOwned();
        String completionMessage;
        if (failed == 0) {
            completionMessage = "UP 主内容监控完成";
        } else if (created == 0 && reused == 0) {
            completionMessage = "UP 主内容监控失败，未能建立采集任务";
        } else {
            completionMessage = "UP 主内容监控完成，部分内容未能建立采集任务";
        }
        eventPublisher.publish(
                task.taskId(),
                executionId,
                "task.updated",
                completionMessage,
                Map.of(
                        "discovered", contents.size(),
                        "created", created,
                        "reused", reused,
                        "failed", failed));
        if (failed > 0 && created == 0 && reused == 0) {
            throw new DomainException(
                    "CREATOR_DISCOVERY_ALL_CHILDREN_FAILED",
                    "发现内容均未能建立采集任务");
        }
        return new DiscoveryResult(contents.size(), created, reused, failed);
    }

    private EnumSet<SourceType> watchedTypes(String metadataJson) {
        EnumSet<SourceType> selected = EnumSet.noneOf(SourceType.class);
        try {
            JsonNode configured = objectMapper.readTree(metadataJson == null ? "{}" : metadataJson)
                    .path("watchedContentTypes");
            if (configured.isArray()) {
                for (JsonNode value : configured) {
                    try {
                        SourceType type = SourceType.valueOf(value.asText());
                        if (type == SourceType.VIDEO || type == SourceType.DYNAMIC) {
                            selected.add(type);
                        }
                    } catch (IllegalArgumentException ignored) {
                        // Ignore one unknown persisted value without discarding valid siblings.
                    }
                }
            }
        } catch (Exception ignored) {
            // Malformed optional metadata falls back to the legacy behavior below.
        }
        return selected.isEmpty()
                ? EnumSet.of(SourceType.VIDEO, SourceType.DYNAMIC)
                : selected;
    }

    public record DiscoveryResult(long discovered, long created, long reused, long failed) {}
}
