package com.hy.bilicomment.application.execution;

import com.hy.bilicomment.application.credential.CredentialService;
import com.hy.bilicomment.application.event.TaskEventPublisher;
import com.hy.bilicomment.config.AppProperties;
import com.hy.bilicomment.domain.error.DomainException;
import com.hy.bilicomment.domain.execution.ExecutionPhase;
import com.hy.bilicomment.domain.task.CollectionMode;
import com.hy.bilicomment.domain.task.SourceType;
import com.hy.bilicomment.infrastructure.bilibili.BilibiliClient;
import com.hy.bilicomment.infrastructure.bilibili.BilibiliCredential;
import com.hy.bilicomment.infrastructure.bilibili.CommentPage;
import com.hy.bilicomment.infrastructure.bilibili.ResolvedContent;
import com.hy.bilicomment.infrastructure.persistence.entity.TaskRow;
import com.hy.bilicomment.infrastructure.persistence.mapper.ExecutionMapper;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class CommentCollectionService {

    private final BilibiliClient bilibiliClient;
    private final CredentialService credentialService;
    private final ExecutionFencedWriteService fencedWriteService;
    private final ExecutionMapper executionMapper;
    private final TaskEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final int backfillPageLimit;

    public CommentCollectionService(
            BilibiliClient bilibiliClient,
            CredentialService credentialService,
            ExecutionFencedWriteService fencedWriteService,
            ExecutionMapper executionMapper,
            TaskEventPublisher eventPublisher,
            ObjectMapper objectMapper,
            AppProperties properties) {
        this.bilibiliClient = bilibiliClient;
        this.credentialService = credentialService;
        this.fencedWriteService = fencedWriteService;
        this.executionMapper = executionMapper;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.backfillPageLimit = Math.max(1, properties.getBilibili().getBackfillPageLimit());
    }

    public CollectionResult collect(
            TaskRow task,
            long executionId,
            ExecutionLeaseManager.Lease lease) {
        boolean backfill = task.collectionMode() == CollectionMode.BACKFILL_ONLY;
        lease.assertOwned();
        BilibiliCredential credential = credentialService.resolve(
                task.credentialProfileId(),
                task.sourceType() == SourceType.DYNAMIC);
        ObjectNode metadata = readMetadata(task.sourceMetadataJson());
        lease.assertOwned();
        ResolvedContent resolved = resolvedContent(task, metadata, credential);
        lease.assertOwned();
        persistResolvedMetadata(task.taskId(), executionId, lease, metadata, resolved);

        String cursor = backfill ? nullableText(metadata.path("backfillCursor")) : null;
        Set<String> seenCursors = new HashSet<>();
        long pages = 0;
        long discovered = 0;
        long inserted = 0;
        long duplicates = 0;

        while (true) {
            lease.assertOwned();
            if (executionMapper.isCancellationRequested(executionId)) {
                throw new DomainException("EXECUTION_CANCELLED", "任务已请求停止");
            }
            if (pages >= backfillPageLimit) {
                throw new DomainException("BACKFILL_PAGE_LIMIT", "单次历史回填达到安全页数上限");
            }
            lease.assertOwned();
            CommentPage page = bilibiliClient.fetchCommentPage(resolved, cursor, credential);
            lease.assertOwned();
            var write = fencedWriteService.insertComments(
                    task.taskId(), executionId, lease.leaseOwner(), page.comments());
            lease.assertOwned();
            pages++;
            discovered += write.received();
            inserted += write.inserted();
            duplicates += write.duplicates();

            String nextCursor = page.nextCursor();
            ObjectNode checkpoint = objectMapper.createObjectNode();
            if (nextCursor != null) {
                checkpoint.put("nextCursor", nextCursor);
            }
            if (executionMapper.updateProgress(
                            executionId,
                            lease.leaseOwner(),
                            ExecutionPhase.PERSISTING_COMMENTS,
                            pages,
                            discovered,
                            inserted,
                            duplicates,
                            objectMapper.writeValueAsString(checkpoint))
                    != 1) {
                lease.assertOwned();
                throw new DomainException("EXECUTION_PROGRESS_REJECTED", "无法保存任务执行进度");
            }
            if (write.inserted() > 0) {
                lease.assertOwned();
                eventPublisher.publish(
                        task.taskId(),
                        executionId,
                        "comments.appended",
                        "发现新评论",
                        Map.of(
                                "taskId", Long.toString(task.taskId()),
                                "executionId", Long.toString(executionId),
                                "inserted", write.inserted()));
            }

            if (!backfill || page.end()) {
                metadata.remove("backfillCursor");
                updateMetadata(task.taskId(), executionId, lease, metadata);
                break;
            }
            if (nextCursor == null
                    || nextCursor.equals(cursor)
                    || !seenCursors.add(nextCursor)) {
                throw new DomainException("COMMENT_CURSOR_STALLED", "评论分页游标未前进，已停止任务");
            }
            cursor = nextCursor;
            metadata.put("backfillCursor", cursor);
            updateMetadata(task.taskId(), executionId, lease, metadata);
        }
        return new CollectionResult(pages, discovered, inserted, duplicates);
    }

    private ResolvedContent resolvedContent(
            TaskRow task,
            ObjectNode metadata,
            BilibiliCredential credential) {
        String oid = nullableText(metadata.path("oid"));
        String commentType = nullableText(metadata.path("commentType"));
        if (oid != null && commentType != null) {
            return new ResolvedContent(
                    task.sourceType(),
                    task.sourceId(),
                    oid,
                    commentType,
                    nullableText(metadata.path("title")));
        }
        return switch (task.sourceType()) {
            case VIDEO -> bilibiliClient.resolveVideo(task.sourceId(), credential);
            case DYNAMIC -> bilibiliClient.resolveDynamic(task.sourceId(), credential);
            case CREATOR -> throw new DomainException("TASK_SOURCE_MISMATCH", "UP 主来源不能执行评论采集");
        };
    }

    private void persistResolvedMetadata(
            long taskId,
            long executionId,
            ExecutionLeaseManager.Lease lease,
            ObjectNode metadata,
            ResolvedContent resolved) {
        metadata.put("oid", resolved.oid());
        metadata.put("commentType", resolved.commentType());
        if (resolved.title() != null) {
            metadata.put("title", resolved.title());
        }
        updateMetadata(taskId, executionId, lease, metadata);
    }

    private void updateMetadata(
            long taskId,
            long executionId,
            ExecutionLeaseManager.Lease lease,
            ObjectNode metadata) {
        lease.assertOwned();
        fencedWriteService.updateSourceMetadata(
                taskId,
                executionId,
                lease.leaseOwner(),
                objectMapper.writeValueAsString(metadata));
    }

    private ObjectNode readMetadata(String json) {
        try {
            JsonNode node = objectMapper.readTree(json == null ? "{}" : json);
            if (!node.isObject()) {
                throw new DomainException("TASK_METADATA_INVALID", "任务元数据不是对象");
            }
            return (ObjectNode) node;
        } catch (DomainException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new DomainException("TASK_METADATA_INVALID", "任务元数据无法解析", exception);
        }
    }

    private String nullableText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value.isBlank() ? null : value;
    }

    public record CollectionResult(long pages, long discovered, long inserted, long duplicates) {}
}
