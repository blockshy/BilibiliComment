package com.hy.bilicomment.interfaces.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.hy.bilicomment.application.comment.CommentExportService;
import com.hy.bilicomment.application.idempotency.IdempotencyService;
import com.hy.bilicomment.domain.comment.CommentExportStatus;
import com.hy.bilicomment.domain.error.DomainException;
import com.hy.bilicomment.infrastructure.export.EncryptedExportFileStore;
import com.hy.bilicomment.infrastructure.persistence.entity.CommentExportJobRow;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class CommentExportControllerTests {

    @Mock private CommentExportService exportService;
    @Mock private EncryptedExportFileStore fileStore;
    @Mock private IdempotencyService idempotencyService;

    private CommentExportController controller;

    @BeforeEach
    void setUp() {
        controller = new CommentExportController(
                exportService,
                fileStore,
                idempotencyService,
                new ObjectMapper());
    }

    @Test
    void limitsConcurrentFileValidationAndStreamingToTwoDownloads() throws Exception {
        CommentExportJobRow job = succeededJob();
        when(exportService.require(71L)).thenReturn(job);
        when(exportService.status(job)).thenReturn(CommentExportStatus.SUCCEEDED);
        when(exportService.downloadReady(job)).thenReturn(true);
        when(fileStore.open(job.fileKey(), job.encryptedSha256()))
                .thenAnswer(ignored -> new ByteArrayInputStream(
                        "fixture".getBytes(StandardCharsets.UTF_8)));

        ResponseEntity<StreamingResponseBody> first = controller.download("71");
        ResponseEntity<StreamingResponseBody> second = controller.download("71");

        assertThatThrownBy(() -> controller.download("71"))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("COMMENT_EXPORT_DOWNLOAD_BUSY"));

        first.getBody().writeTo(new ByteArrayOutputStream());
        ResponseEntity<StreamingResponseBody> afterRelease = controller.download("71");
        second.getBody().writeTo(new ByteArrayOutputStream());
        afterRelease.getBody().writeTo(new ByteArrayOutputStream());
    }

    private CommentExportJobRow succeededJob() {
        Instant now = Instant.parse("2026-07-14T00:00:00Z");
        return new CommentExportJobRow(
                71L,
                17L,
                "admin",
                "CSV",
                "[\"RPID\"]",
                "{}",
                "0".repeat(64),
                "CTIME_DESC",
                100L,
                "SUCCEEDED",
                1_000L,
                1_024L,
                1L,
                7L,
                "0123456789abcdef0123456789abcdef",
                "0".repeat(64),
                false,
                null,
                null,
                null,
                null,
                now,
                now,
                now,
                now,
                now.plusSeconds(3_600),
                now);
    }
}
