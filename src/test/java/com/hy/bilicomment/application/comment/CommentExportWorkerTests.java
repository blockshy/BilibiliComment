package com.hy.bilicomment.application.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import com.hy.bilicomment.application.audit.OperationAuditService;
import com.hy.bilicomment.config.AppProperties;
import com.hy.bilicomment.domain.comment.CommentExportColumn;
import com.hy.bilicomment.domain.comment.CommentFilter;
import com.hy.bilicomment.domain.comment.CommentReplyScope;
import com.hy.bilicomment.infrastructure.export.EncryptedExportFileStore;
import com.hy.bilicomment.infrastructure.persistence.CommentRepository;
import com.hy.bilicomment.infrastructure.persistence.CommentRepository.CommentView;
import com.hy.bilicomment.infrastructure.persistence.entity.CommentExportJobRow;
import com.hy.bilicomment.infrastructure.persistence.mapper.CommentExportJobMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class CommentExportWorkerTests {

    private static final Instant NOW = Instant.parse("2026-07-14T00:00:00Z");

    @TempDir private Path directory;
    @Mock private CommentExportJobMapper mapper;
    @Mock private CommentRepository commentRepository;
    @Mock private CommentExportService exportService;
    @Mock private OperationAuditService auditService;

    private EncryptedExportFileStore fileStore;
    private CommentExportWorker worker;
    private AppProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        properties.getExports().setDirectory(directory.toString());
        properties.getExports().setEncryptionKey(
                "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=");
        properties.getExports().setChunkSize(100);
        fileStore = new EncryptedExportFileStore(properties);
        worker = new CommentExportWorker(
                mapper,
                commentRepository,
                exportService,
                fileStore,
                auditService,
                new ObjectMapper(),
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(mapper.findExpired(any(), anyInt())).thenReturn(List.of());
        lenient().when(mapper.findStoredFileKeys()).thenReturn(List.of());
    }

    @Test
    void writesEncryptedCsvAndStoresOnlyTheOpaqueFileKey() throws Exception {
        CommentExportJobRow job = job(42L);
        when(mapper.claimNext(anyString(), anyLong())).thenReturn(Optional.of(job));
        when(exportService.deserializeFilter(job)).thenReturn(emptyFilter());
        when(exportService.deserializeColumns(job))
                .thenReturn(List.of(CommentExportColumn.RPID, CommentExportColumn.CONTENT));
        when(commentRepository.search(any())).thenReturn(List.of(new CommentView(
                1L,
                "22",
                "fixture",
                null,
                6,
                "=SUM(1,1)",
                NOW.minusSeconds(60),
                99L,
                null,
                NOW)));
        when(mapper.findCancellationRequested(42L, job.workerOwner())).thenReturn(Optional.of(false));
        when(mapper.updateProgress(eq(42L), eq(1L), anyLong(), eq(job.workerOwner()))).thenReturn(1);
        when(mapper.markSucceeded(
                        eq(42L), eq(1L), anyLong(), any(), any(), eq(job.workerOwner())))
                .thenReturn(1);

        worker.processNext();

        ArgumentCaptor<String> fileKey = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> digest = ArgumentCaptor.forClass(String.class);
        verify(mapper).markSucceeded(
                eq(42L),
                eq(1L),
                longThat(value -> value > 0),
                fileKey.capture(),
                digest.capture(),
                eq(job.workerOwner()));
        assertThat(fileKey.getValue()).matches("^[0-9a-f]{32}$");
        assertThat(fileKey.getValue()).doesNotEndWith(".enc");
        assertThat(directory.resolve(fileKey.getValue() + ".enc")).isRegularFile();
        try (var input = fileStore.open(fileKey.getValue(), digest.getValue())) {
            assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("rpid,content\r\n99,\"'=SUM(1,1)\"\r\n");
        }
    }

    @Test
    void cancellationAndGenerationFailuresRemovePartialArtifacts() throws Exception {
        CommentExportJobRow cancelled = job(43L);
        when(mapper.claimNext(anyString(), anyLong())).thenReturn(Optional.of(cancelled));
        when(exportService.deserializeFilter(cancelled)).thenReturn(emptyFilter());
        when(exportService.deserializeColumns(cancelled)).thenReturn(List.of(CommentExportColumn.RPID));
        when(mapper.findCancellationRequested(43L, cancelled.workerOwner()))
                .thenReturn(Optional.of(true));

        worker.processNext();

        verify(mapper).markCancelled(43L, cancelled.workerOwner());
        try (var files = Files.list(directory)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void repositoryFailureMarksJobFailedAndRemovesPartialArtifact() throws Exception {
        CommentExportJobRow failed = job(44L);
        when(mapper.claimNext(anyString(), anyLong())).thenReturn(Optional.of(failed));
        when(exportService.deserializeFilter(failed)).thenReturn(emptyFilter());
        when(exportService.deserializeColumns(failed)).thenReturn(List.of(CommentExportColumn.RPID));
        when(mapper.findCancellationRequested(44L, failed.workerOwner()))
                .thenReturn(Optional.of(false));
        when(commentRepository.search(any())).thenThrow(new IllegalStateException("fixture failure"));

        worker.processNext();

        verify(mapper).markFailed(
                44L,
                "COMMENT_EXPORT_FAILED",
                "生成导出文件失败",
                failed.workerOwner());
        try (var files = Files.list(directory)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void anEmptyTailPageCannotSucceedAfterTheRuntimeLimit() throws Exception {
        properties.getExports().setMaximumRuntime(Duration.ofSeconds(1));
        worker = new CommentExportWorker(
                mapper,
                commentRepository,
                exportService,
                fileStore,
                auditService,
                new ObjectMapper(),
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
        CommentExportJobRow timedOut = job(46L);
        when(mapper.claimNext(anyString(), anyLong())).thenReturn(Optional.of(timedOut));
        when(exportService.deserializeFilter(timedOut)).thenReturn(emptyFilter());
        when(exportService.deserializeColumns(timedOut)).thenReturn(List.of(CommentExportColumn.RPID));
        when(mapper.findCancellationRequested(46L, timedOut.workerOwner()))
                .thenReturn(Optional.of(false));
        when(commentRepository.search(any())).thenAnswer(ignored -> {
            Thread.sleep(1_100);
            return List.of();
        });

        worker.processNext();

        verify(commentRepository).search(any());
        verify(mapper).markFailed(
                46L,
                "COMMENT_EXPORT_TIMEOUT",
                "导出任务超过最长运行时间",
                timedOut.workerOwner());
    }

    @Test
    void maintenanceDeletesAndAuditsExpiredJobsWithoutFilterData() {
        CommentExportJobRow expired = expiredJob(45L);
        when(mapper.findExpired(NOW, 100)).thenReturn(List.of(expired));
        when(mapper.deleteExpired(45L, NOW)).thenReturn(1);
        when(mapper.claimNext(anyString(), anyLong())).thenReturn(Optional.empty());

        worker.processNext();

        verify(mapper).deleteExpired(45L, NOW);
        verify(auditService).record(argThat(entry ->
                "COMMENT_EXPORT_EXPIRED".equals(entry.action())
                        && "COMMENT_EXPORT".equals(entry.targetType())
                        && "45".equals(entry.targetId())
                        && "SUCCESS".equals(entry.outcome())));
    }

    private CommentFilter emptyFilter() {
        return new CommentFilter(
                null, null, null, null, null, false,
                null, null, null, null, CommentReplyScope.ALL);
    }

    private CommentExportJobRow job(long id) {
        return new CommentExportJobRow(
                id,
                17L,
                "admin",
                "CSV",
                "[\"RPID\"]",
                "{}",
                "0".repeat(64),
                "CTIME_DESC",
                100L,
                "RUNNING",
                1000L,
                1024L * 1024L,
                0L,
                0L,
                null,
                null,
                false,
                "worker-owner-" + id,
                NOW.plusSeconds(600),
                null,
                null,
                NOW,
                NOW,
                NOW,
                null,
                NOW.plusSeconds(3600),
                NOW);
    }

    private CommentExportJobRow expiredJob(long id) {
        return new CommentExportJobRow(
                id,
                17L,
                "admin",
                "CSV",
                "[\"RPID\"]",
                "{}",
                "0".repeat(64),
                "CTIME_DESC",
                100L,
                "SUCCEEDED",
                1000L,
                1024L * 1024L,
                1L,
                10L,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                NOW.minusSeconds(3600),
                NOW.minusSeconds(3500),
                NOW.minusSeconds(3400),
                NOW.minusSeconds(3300),
                NOW.minusSeconds(1),
                NOW.minusSeconds(3300));
    }
}
