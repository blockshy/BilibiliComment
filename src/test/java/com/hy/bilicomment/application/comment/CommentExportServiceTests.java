package com.hy.bilicomment.application.comment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hy.bilicomment.application.task.TaskService;
import com.hy.bilicomment.config.AppProperties;
import com.hy.bilicomment.domain.comment.CommentExportColumn;
import com.hy.bilicomment.domain.comment.CommentExportFormat;
import com.hy.bilicomment.domain.comment.CommentFilter;
import com.hy.bilicomment.domain.comment.CommentReplyScope;
import com.hy.bilicomment.domain.comment.CommentSort;
import com.hy.bilicomment.domain.error.DomainException;
import com.hy.bilicomment.domain.task.TaskType;
import com.hy.bilicomment.infrastructure.persistence.CommentRepository;
import com.hy.bilicomment.infrastructure.persistence.entity.CommentExportJobRow;
import com.hy.bilicomment.infrastructure.persistence.entity.TaskRow;
import com.hy.bilicomment.infrastructure.persistence.mapper.CommentExportJobMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class CommentExportServiceTests {

    private static final Instant NOW = Instant.parse("2026-07-14T00:00:00Z");

    @Mock private TaskService taskService;
    @Mock private CommentRepository commentRepository;
    @Mock private CommentCursorCodec cursorCodec;
    @Mock private CommentExportJobMapper mapper;

    private CommentExportService service;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        properties.getExports().setMaximumQueued(3);
        service = new CommentExportService(
                taskService,
                commentRepository,
                new CommentFilterService(),
                cursorCodec,
                mapper,
                properties,
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void permitsThreeQueuedJobsInAdditionToAnyRunningJob() {
        TaskRow task = mock(TaskRow.class);
        when(task.taskType()).thenReturn(TaskType.CONTENT_COMMENTS);
        when(taskService.require(17L)).thenReturn(task);
        when(mapper.countQueued()).thenReturn(3L);

        assertThatThrownBy(() -> service.create(
                        17L,
                        "admin",
                        emptyFilter(),
                        CommentSort.CTIME_DESC,
                        CommentExportFormat.CSV,
                        null))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.getCode())
                                .isEqualTo("COMMENT_EXPORT_QUEUE_FULL"));

        verify(mapper).lockQueue();
        verify(commentRepository, never()).captureSnapshot(17L);
    }

    @Test
    void cancellationIsIdempotentAndOnlyMutatesActiveJobs() {
        when(mapper.findById(41L)).thenReturn(Optional.of(job(41L, "QUEUED", false)));
        when(mapper.findById(42L)).thenReturn(Optional.of(job(42L, "SUCCEEDED", false)));

        service.cancel(41L);
        service.cancel(42L);

        verify(mapper).requestCancellation(41L);
        verify(mapper, never()).requestCancellation(42L);
    }

    private CommentFilter emptyFilter() {
        return new CommentFilter(
                null, null, null, null, null, false,
                null, null, null, null, CommentReplyScope.ALL);
    }

    private CommentExportJobRow job(long id, String status, boolean cancellationRequested) {
        return new CommentExportJobRow(
                id,
                17L,
                "admin",
                "CSV",
                "[\"RPID\",\"CONTENT\"]",
                "{}",
                "0".repeat(64),
                "CTIME_DESC",
                9L,
                status,
                100L,
                1024L,
                0L,
                0L,
                null,
                null,
                cancellationRequested,
                null,
                null,
                null,
                null,
                NOW,
                null,
                null,
                "SUCCEEDED".equals(status) ? NOW : null,
                NOW.plusSeconds(3600),
                NOW);
    }
}
