package com.hy.bilicomment.application.comment;

import com.hy.bilicomment.application.audit.OperationAuditService;
import com.hy.bilicomment.application.audit.OperationAuditService.AuditEntry;
import com.hy.bilicomment.config.AppProperties;
import com.hy.bilicomment.domain.comment.CommentExportColumn;
import com.hy.bilicomment.domain.comment.CommentExportFormat;
import com.hy.bilicomment.domain.comment.CommentFilter;
import com.hy.bilicomment.domain.comment.CommentSort;
import com.hy.bilicomment.domain.error.DomainException;
import com.hy.bilicomment.infrastructure.export.EncryptedExportFileStore;
import com.hy.bilicomment.infrastructure.export.EncryptedExportFileStore.Artifact;
import com.hy.bilicomment.infrastructure.export.EncryptedExportFileStore.EncryptedWriter;
import com.hy.bilicomment.infrastructure.export.EncryptedExportFileStore.ExportSizeLimitException;
import com.hy.bilicomment.infrastructure.persistence.CommentRepository;
import com.hy.bilicomment.infrastructure.persistence.CommentRepository.CommentView;
import com.hy.bilicomment.infrastructure.persistence.CommentRepository.SearchSpec;
import com.hy.bilicomment.infrastructure.persistence.entity.CommentExportJobRow;
import com.hy.bilicomment.infrastructure.persistence.mapper.CommentExportJobMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class CommentExportWorker {

    private static final Logger log = LoggerFactory.getLogger(CommentExportWorker.class);
    private static final Duration MAINTENANCE_INTERVAL = Duration.ofMinutes(1);
    private static final Duration LEASE_COMPLETION_GRACE = Duration.ofSeconds(30);

    private final CommentExportJobMapper mapper;
    private final CommentRepository commentRepository;
    private final CommentExportService exportService;
    private final EncryptedExportFileStore fileStore;
    private final OperationAuditService auditService;
    private final ObjectMapper objectMapper;
    private final AppProperties.Exports properties;
    private final Clock clock;
    private final AtomicBoolean active = new AtomicBoolean();
    private final String workerInstance = "comment-export:" + UUID.randomUUID();
    private volatile boolean stopping;
    private volatile Instant nextMaintenance = Instant.EPOCH;

    public CommentExportWorker(
            CommentExportJobMapper mapper,
            CommentRepository commentRepository,
            CommentExportService exportService,
            EncryptedExportFileStore fileStore,
            OperationAuditService auditService,
            ObjectMapper objectMapper,
            AppProperties appProperties,
            Clock clock) {
        this.mapper = mapper;
        this.commentRepository = commentRepository;
        this.exportService = exportService;
        this.fileStore = fileStore;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.properties = appProperties.getExports();
        this.clock = clock;
    }

    @Scheduled(
            scheduler = "commentExportScheduler",
            fixedDelayString = "${app.exports.poll-delay:2s}")
    public void processNext() {
        if (stopping || !properties.isEnabled() || !active.compareAndSet(false, true)) {
            return;
        }
        try {
            maintainIfDue();
            String owner = workerInstance + ':' + UUID.randomUUID();
            long leaseSeconds = Math.max(
                    1L,
                    properties.getMaximumRuntime().plus(LEASE_COMPLETION_GRACE).toSeconds());
            mapper.claimNext(owner, leaseSeconds).ifPresent(this::process);
        } catch (RuntimeException exception) {
            log.error("Comment export worker cycle failed", exception);
        } finally {
            active.set(false);
        }
    }

    @PreDestroy
    void stop() {
        stopping = true;
    }

    private void process(CommentExportJobRow job) {
        String fileKey = UUID.randomUUID().toString().replace("-", "");
        String workerOwner = job.workerOwner();
        try {
            if (workerOwner == null || workerOwner.isBlank()) {
                throw new ExportLeaseLostException();
            }
            CommentFilter filter = exportService.deserializeFilter(job);
            List<CommentExportColumn> columns = exportService.deserializeColumns(job);
            CommentExportFormat format = CommentExportFormat.valueOf(job.exportFormat());
            CommentSort sort = CommentSort.valueOf(job.sortOrder());
            long rowsWritten = 0;
            Instant afterTime = null;
            Long afterRpid = null;
            long startedNanos = System.nanoTime();

            try (EncryptedWriter encrypted = fileStore.create(fileKey, job.byteLimit())) {
                CommentExportEncoder encoder = new CommentExportEncoder(
                        format,
                        columns,
                        encrypted.outputStream(),
                        objectMapper);
                while (true) {
                    checkCanContinue(job, workerOwner, startedNanos);
                    long remaining = job.rowLimit() - rowsWritten;
                    int requested = (int) Math.min(
                            properties.getChunkSize(),
                            Math.max(1L, remaining + 1L));
                    List<CommentView> page = commentRepository.search(new SearchSpec(
                            job.taskId(),
                            filter,
                            sort,
                            job.snapshotMaxCommentId(),
                            afterTime,
                            afterRpid,
                            requested));
                    checkCanContinue(job, workerOwner, startedNanos);
                    if (page.size() > remaining) {
                        throw new ExportRowLimitException();
                    }
                    if (page.isEmpty()) {
                        break;
                    }
                    for (CommentView comment : page) {
                        encoder.write(comment);
                        rowsWritten++;
                    }
                    encoder.flush();
                    if (mapper.updateProgress(
                                    job.exportId(),
                                    rowsWritten,
                                    encrypted.plaintextBytes(),
                                    workerOwner)
                            != 1) {
                        throw new ExportLeaseLostException();
                    }
                    checkCanContinue(job, workerOwner, startedNanos);
                    CommentView boundary = page.getLast();
                    afterTime = boundary.ctime();
                    afterRpid = boundary.rpid();
                    if (page.size() < requested) {
                        break;
                    }
                }
                encoder.close();
                checkCanContinue(job, workerOwner, startedNanos);
                Artifact artifact = encrypted.finish();
                if (mapper.markSucceeded(
                                job.exportId(),
                                rowsWritten,
                                artifact.plaintextBytes(),
                                fileKey,
                                artifact.encryptedSha256(),
                                workerOwner)
                        != 1) {
                    checkCanContinue(job, workerOwner, startedNanos);
                    throw new ExportLeaseLostException();
                }
                audit(job, "COMMENT_EXPORT_SUCCEEDED", "SUCCESS");
            }
        } catch (Exception exception) {
            handleFailure(job, workerOwner, fileKey, exception);
        }
    }

    private void checkCanContinue(
            CommentExportJobRow job,
            String workerOwner,
            long startedNanos) {
        if (stopping) {
            throw new ExportLeaseLostException();
        }
        boolean cancellationRequested = mapper.findCancellationRequested(job.exportId(), workerOwner)
                .orElseThrow(ExportLeaseLostException::new);
        if (cancellationRequested) {
            throw new ExportCancelledException();
        }
        long maximumNanos = properties.getMaximumRuntime().toNanos();
        if (System.nanoTime() - startedNanos >= maximumNanos) {
            throw new ExportRuntimeLimitException();
        }
    }

    private void handleFailure(
            CommentExportJobRow job,
            String workerOwner,
            String fileKey,
            Exception exception) {
        try {
            fileStore.delete(fileKey);
        } catch (IOException cleanupFailure) {
            log.warn("Unable to remove failed export artifact exportId={}", job.exportId());
        }
        if (exception instanceof ExportLeaseLostException) {
            return;
        }
        var cancellationState = mapper.findCancellationRequested(job.exportId(), workerOwner);
        if (cancellationState.isEmpty()) {
            return;
        }
        if (exception instanceof ExportCancelledException || cancellationState.orElse(false)) {
            if (mapper.markCancelled(job.exportId(), workerOwner) == 1) {
                audit(job, "COMMENT_EXPORT_CANCELLED", "SUCCESS");
            }
            return;
        }
        String errorCode;
        String errorSummary;
        if (contains(exception, ExportSizeLimitException.class)) {
            errorCode = "COMMENT_EXPORT_BYTE_LIMIT";
            errorSummary = "导出文件超过大小限制";
        } else if (exception instanceof ExportRowLimitException) {
            errorCode = "COMMENT_EXPORT_ROW_LIMIT";
            errorSummary = "导出数据超过行数限制";
        } else if (exception instanceof ExportRuntimeLimitException) {
            errorCode = "COMMENT_EXPORT_TIMEOUT";
            errorSummary = "导出任务超过最长运行时间";
        } else if (exception instanceof DomainException domainException) {
            errorCode = safeCode(domainException.getCode());
            errorSummary = "导出任务配置无效";
        } else {
            errorCode = "COMMENT_EXPORT_FAILED";
            errorSummary = "生成导出文件失败";
        }
        if (mapper.markFailed(job.exportId(), errorCode, errorSummary, workerOwner) == 1) {
            audit(job, "COMMENT_EXPORT_FAILED", "FAILED");
        }
        log.warn("Comment export failed exportId={} errorType={}",
                job.exportId(), exception.getClass().getSimpleName());
    }

    private void maintainIfDue() {
        Instant now = clock.instant();
        if (now.isBefore(nextMaintenance)) {
            return;
        }
        nextMaintenance = now.plus(MAINTENANCE_INTERVAL);
        Instant staleCutoff = now.minus(properties.getMaximumRuntime().multipliedBy(2));
        mapper.recoverStale();
        for (CommentExportJobRow expired : mapper.findExpired(now, 100)) {
            try {
                fileStore.delete(expired.fileKey());
                if (mapper.deleteExpired(expired.exportId(), now) == 1) {
                    audit(expired, "COMMENT_EXPORT_EXPIRED", "SUCCESS");
                }
            } catch (IOException exception) {
                log.warn("Unable to clean expired export artifact exportId={}", expired.exportId());
            }
        }
        try {
            Set<String> referenced = Set.copyOf(mapper.findStoredFileKeys());
            fileStore.deleteOrphans(referenced, staleCutoff);
        } catch (IOException exception) {
            log.warn("Unable to clean orphaned export artifacts");
        }
    }

    private boolean contains(Throwable exception, Class<? extends Throwable> type) {
        Throwable current = exception;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String safeCode(String value) {
        return value != null && value.matches("^[A-Z0-9_]{1,64}$")
                ? value
                : "COMMENT_EXPORT_FAILED";
    }

    private void audit(CommentExportJobRow job, String action, String outcome) {
        auditService.record(new AuditEntry(
                job.requestedBy(),
                action,
                "COMMENT_EXPORT",
                Long.toString(job.exportId()),
                outcome,
                null,
                null));
    }

    private static final class ExportCancelledException extends RuntimeException {}

    private static final class ExportLeaseLostException extends RuntimeException {}

    private static final class ExportRuntimeLimitException extends RuntimeException {}

    private static final class ExportRowLimitException extends RuntimeException {}
}
