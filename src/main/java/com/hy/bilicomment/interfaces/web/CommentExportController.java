package com.hy.bilicomment.interfaces.web;

import com.hy.bilicomment.application.comment.CommentExportService;
import com.hy.bilicomment.application.idempotency.IdempotencyService;
import com.hy.bilicomment.domain.comment.CommentExportColumn;
import com.hy.bilicomment.domain.comment.CommentExportFormat;
import com.hy.bilicomment.domain.comment.CommentExportStatus;
import com.hy.bilicomment.domain.comment.CommentSort;
import com.hy.bilicomment.domain.error.DomainException;
import com.hy.bilicomment.infrastructure.export.EncryptedExportFileStore;
import com.hy.bilicomment.infrastructure.persistence.entity.CommentExportJobRow;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1")
public class CommentExportController {

    private static final Logger log = LoggerFactory.getLogger(CommentExportController.class);

    private final CommentExportService exportService;
    private final EncryptedExportFileStore fileStore;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final Semaphore downloadPermits = new Semaphore(2, true);

    public CommentExportController(
            CommentExportService exportService,
            EncryptedExportFileStore fileStore,
            IdempotencyService idempotencyService,
            ObjectMapper objectMapper) {
        this.exportService = exportService;
        this.fileStore = fileStore;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/tasks/{taskId}/comment-exports")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Transactional
    public CommentExportJob create(
            @PathVariable String taskId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateCommentExportRequest request,
            Principal principal) {
        long parsedTaskId = TaskController.parseId(taskId, "taskId");
        String canonical = canonical(parsedTaskId, request);
        IdempotencyService.Claim claim = idempotencyService.claim(
                "CREATE_COMMENT_EXPORT",
                idempotencyKey,
                canonical);
        if (claim.replay()) {
            CommentExportJobRow replayed = exportService.require(
                    TaskController.parseId(claim.resourceId(), "resourceId"));
            if (replayed.taskId() != parsedTaskId) {
                throw new DomainException("IDEMPOTENCY_RESOURCE_MISMATCH", "幂等请求对应的导出任务不匹配");
            }
            return view(replayed);
        }
        boolean synchronizedTransaction = TransactionSynchronizationManager.isSynchronizationActive();
        if (synchronizedTransaction) {
            registerRollbackRelease(claim.id());
        }
        try {
            CommentExportJobRow job = exportService.create(
                    parsedTaskId,
                    principal == null ? null : principal.getName(),
                    request.filter().toDomain(),
                    request.sort(),
                    request.format(),
                    request.columns());
            idempotencyService.complete(
                    claim.id(),
                    "COMMENT_EXPORT",
                    Long.toString(job.exportId()));
            return view(job);
        } catch (RuntimeException exception) {
            if (!synchronizedTransaction) {
                idempotencyService.fail(claim.id());
            }
            throw exception;
        }
    }

    @GetMapping("/tasks/{taskId}/comment-exports")
    public List<CommentExportJob> list(
            @PathVariable String taskId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return exportService.list(TaskController.parseId(taskId, "taskId"), limit).stream()
                .map(this::view)
                .toList();
    }

    @GetMapping("/comment-exports/{exportId}")
    public CommentExportJob get(@PathVariable String exportId) {
        return view(exportService.require(TaskController.parseId(exportId, "exportId")));
    }

    @GetMapping("/comment-exports/{exportId}/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable String exportId) {
        CommentExportJobRow job = exportService.require(TaskController.parseId(exportId, "exportId"));
        if (exportService.status(job) == CommentExportStatus.EXPIRED) {
            throw new DomainException("COMMENT_EXPORT_EXPIRED", "评论导出任务已过期");
        }
        if (!exportService.downloadReady(job)) {
            throw new DomainException("COMMENT_EXPORT_NOT_READY", "评论导出文件尚未生成");
        }
        if (!downloadPermits.tryAcquire()) {
            throw new DomainException("COMMENT_EXPORT_DOWNLOAD_BUSY", "并行下载已达上限，请稍后重试");
        }
        final InputStream input;
        try {
            // Keep the validated descriptor open so expiry cleanup cannot race the response stream.
            input = fileStore.open(job.fileKey(), job.encryptedSha256());
        } catch (java.io.IOException exception) {
            downloadPermits.release();
            throw new DomainException("COMMENT_EXPORT_FILE_UNAVAILABLE", "评论导出文件无法读取", exception);
        } catch (RuntimeException exception) {
            downloadPermits.release();
            throw exception;
        }

        CommentExportFormat format = CommentExportFormat.valueOf(job.exportFormat());
        String extension = format == CommentExportFormat.CSV ? "csv" : "jsonl";
        String filename = "bilibili-comments-task-" + job.taskId()
                + "-export-" + job.exportId() + "." + extension;
        StreamingResponseBody stream = output -> {
            try (input) {
                input.transferTo(output);
            } finally {
                downloadPermits.release();
            }
        };
        MediaType mediaType = format == CommentExportFormat.CSV
                ? new MediaType("text", "csv", StandardCharsets.UTF_8)
                : new MediaType("application", "x-ndjson", StandardCharsets.UTF_8);
        try {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .contentType(mediaType)
                    .contentLength(job.bytesWritten())
                    .header(
                            HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment()
                                    .filename(filename, StandardCharsets.UTF_8)
                                    .build()
                                    .toString())
                    .header("X-Content-Type-Options", "nosniff")
                    .body(stream);
        } catch (RuntimeException exception) {
            try {
                input.close();
            } catch (java.io.IOException closeFailure) {
                exception.addSuppressed(closeFailure);
            } finally {
                downloadPermits.release();
            }
            throw exception;
        }
    }

    @DeleteMapping("/comment-exports/{exportId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable String exportId) {
        exportService.cancel(TaskController.parseId(exportId, "exportId"));
    }

    private CommentExportJob view(CommentExportJobRow job) {
        return new CommentExportJob(
                Long.toString(job.exportId()),
                Long.toString(job.taskId()),
                exportService.status(job),
                CommentExportFormat.valueOf(job.exportFormat()),
                exportService.deserializeColumns(job),
                job.rowsWritten(),
                job.bytesWritten(),
                exportService.downloadReady(job),
                job.errorCode(),
                job.errorSummary(),
                job.createdAt(),
                job.startedAt(),
                job.finishedAt(),
                job.expiresAt());
    }

    private String canonical(long taskId, CreateCommentExportRequest request) {
        try {
            return taskId + ":" + objectMapper.writeValueAsString(request);
        } catch (Exception exception) {
            throw new DomainException("REQUEST_SERIALIZATION_FAILED", "请求无法序列化", exception);
        }
    }

    private void registerRollbackRelease(long claimId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) {
                    return;
                }
                try {
                    idempotencyService.fail(claimId);
                } catch (RuntimeException exception) {
                    log.error("Failed to release rolled-back export idempotency claim {}", claimId);
                }
            }
        });
    }

    public record CreateCommentExportRequest(
            @NotNull @Valid CommentController.CommentFilterRequest filter,
            CommentSort sort,
            @NotNull CommentExportFormat format,
            List<@NotNull CommentExportColumn> columns) {}

    public record CommentExportJob(
            String id,
            String taskId,
            CommentExportStatus status,
            CommentExportFormat format,
            List<CommentExportColumn> columns,
            long rowsWritten,
            long bytesWritten,
            boolean downloadReady,
            String errorCode,
            String errorSummary,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt,
            Instant expiresAt) {

        public CommentExportJob {
            columns = List.copyOf(columns);
        }
    }
}
