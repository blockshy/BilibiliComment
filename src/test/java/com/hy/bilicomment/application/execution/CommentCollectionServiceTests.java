package com.hy.bilicomment.application.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hy.bilicomment.application.credential.CredentialService;
import com.hy.bilicomment.application.event.TaskEventPublisher;
import com.hy.bilicomment.config.AppProperties;
import com.hy.bilicomment.domain.error.DomainException;
import com.hy.bilicomment.domain.task.CollectionMode;
import com.hy.bilicomment.domain.task.DesiredState;
import com.hy.bilicomment.domain.task.ScheduleType;
import com.hy.bilicomment.domain.task.SourceType;
import com.hy.bilicomment.domain.task.TaskType;
import com.hy.bilicomment.infrastructure.bilibili.BilibiliClient;
import com.hy.bilicomment.infrastructure.bilibili.BilibiliCredential;
import com.hy.bilicomment.infrastructure.bilibili.CommentPage;
import com.hy.bilicomment.infrastructure.bilibili.ResolvedContent;
import com.hy.bilicomment.infrastructure.persistence.CommentRepository;
import com.hy.bilicomment.infrastructure.persistence.entity.TaskRow;
import com.hy.bilicomment.infrastructure.persistence.mapper.ExecutionMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class CommentCollectionServiceTests {

    private static final BilibiliCredential CREDENTIAL =
            new BilibiliCredential(9L, "SESSDATA=fixture");

    @Mock private BilibiliClient bilibiliClient;
    @Mock private CredentialService credentialService;
    @Mock private ExecutionFencedWriteService fencedWriteService;
    @Mock private ExecutionMapper executionMapper;
    @Mock private TaskEventPublisher eventPublisher;
    @Mock private ExecutionLeaseManager.Lease lease;

    private AppProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        properties.getBilibili().setBackfillPageLimit(10);
        lenient().when(credentialService.resolve(9L, false)).thenReturn(CREDENTIAL);
        lenient().when(lease.leaseOwner()).thenReturn("fixture-lease");
        lenient().when(executionMapper.updateProgress(
                        anyLong(), anyString(), any(), anyLong(), anyLong(), anyLong(), anyLong(), anyString()))
                .thenReturn(1);
    }

    @Test
    void incrementalCollectionFetchesExactlyOnePageAndUsesTheResolvedCredential() {
        TaskRow task = task(CollectionMode.FOLLOW_ONLY, SourceType.VIDEO, resolvedMetadata(null));
        ResolvedContent resolved = resolved(SourceType.VIDEO);
        when(bilibiliClient.fetchCommentPage(resolved, null, CREDENTIAL))
                .thenReturn(new CommentPage(List.of(), "ignored-next-page", false));
        when(fencedWriteService.insertComments(
                        eq(7L), eq(31L), eq("fixture-lease"), anyList()))
                .thenReturn(new CommentRepository.InsertResult(2, 1, 1));

        CommentCollectionService.CollectionResult result = service().collect(task, 31L, lease);

        assertThat(result).isEqualTo(new CommentCollectionService.CollectionResult(1, 2, 1, 1));
        verify(credentialService).resolve(9L, false);
        verify(bilibiliClient).fetchCommentPage(resolved, null, CREDENTIAL);
        verify(executionMapper).updateProgress(
                eq(31L), eq("fixture-lease"), any(), eq(1L), eq(2L), eq(1L), eq(1L), anyString());
    }

    @Test
    void backfillRestoresItsPersistedCursorAndStopsAtTheEndPage() {
        TaskRow task = task(
                CollectionMode.BACKFILL_ONLY,
                SourceType.VIDEO,
                resolvedMetadata("resume-cursor"));
        ResolvedContent resolved = resolved(SourceType.VIDEO);
        when(bilibiliClient.fetchCommentPage(resolved, "resume-cursor", CREDENTIAL))
                .thenReturn(new CommentPage(List.of(), "second-page", false));
        when(bilibiliClient.fetchCommentPage(resolved, "second-page", CREDENTIAL))
                .thenReturn(new CommentPage(List.of(), null, true));
        when(fencedWriteService.insertComments(
                        eq(7L), eq(31L), eq("fixture-lease"), anyList()))
                .thenReturn(new CommentRepository.InsertResult(0, 0, 0));

        CommentCollectionService.CollectionResult result = service().collect(task, 31L, lease);

        assertThat(result.pages()).isEqualTo(2);
        InOrder order = inOrder(bilibiliClient);
        order.verify(bilibiliClient).fetchCommentPage(resolved, "resume-cursor", CREDENTIAL);
        order.verify(bilibiliClient).fetchCommentPage(resolved, "second-page", CREDENTIAL);
        verify(fencedWriteService, org.mockito.Mockito.atLeastOnce())
                .updateSourceMetadata(eq(7L), eq(31L), eq("fixture-lease"), anyString());
    }

    @Test
    void backfillStopsImmediatelyWhenTheCursorDoesNotAdvance() {
        TaskRow task = task(
                CollectionMode.BACKFILL_ONLY,
                SourceType.VIDEO,
                resolvedMetadata("stalled"));
        ResolvedContent resolved = resolved(SourceType.VIDEO);
        when(bilibiliClient.fetchCommentPage(resolved, "stalled", CREDENTIAL))
                .thenReturn(new CommentPage(List.of(), "stalled", false));
        when(fencedWriteService.insertComments(
                        eq(7L), eq(31L), eq("fixture-lease"), anyList()))
                .thenReturn(new CommentRepository.InsertResult(0, 0, 0));

        assertThatThrownBy(() -> service().collect(task, 31L, lease))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("COMMENT_CURSOR_STALLED"));

        verify(bilibiliClient).fetchCommentPage(resolved, "stalled", CREDENTIAL);
    }

    @Test
    void backfillEnforcesTheConfiguredPageLimitBeforeAnotherRequest() {
        properties.getBilibili().setBackfillPageLimit(1);
        TaskRow task = task(CollectionMode.BACKFILL_ONLY, SourceType.VIDEO, resolvedMetadata(null));
        ResolvedContent resolved = resolved(SourceType.VIDEO);
        when(bilibiliClient.fetchCommentPage(resolved, null, CREDENTIAL))
                .thenReturn(new CommentPage(List.of(), "second-page", false));
        when(fencedWriteService.insertComments(
                        eq(7L), eq(31L), eq("fixture-lease"), anyList()))
                .thenReturn(new CommentRepository.InsertResult(0, 0, 0));

        assertThatThrownBy(() -> service().collect(task, 31L, lease))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("BACKFILL_PAGE_LIMIT"));

        verify(bilibiliClient).fetchCommentPage(resolved, null, CREDENTIAL);
    }

    @Test
    void cancellationIsCheckedBeforeAnyUpstreamPageRequest() {
        TaskRow task = task(CollectionMode.FOLLOW_ONLY, SourceType.VIDEO, resolvedMetadata(null));
        when(executionMapper.isCancellationRequested(31L)).thenReturn(true);

        assertThatThrownBy(() -> service().collect(task, 31L, lease))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("EXECUTION_CANCELLED"));

        verify(bilibiliClient, never()).fetchCommentPage(any(), any(), any());
        verify(fencedWriteService, never()).insertComments(anyLong(), anyLong(), anyString(), anyList());
    }

    @Test
    void dynamicCollectionRequiresAndPassesTheSameCredentialToResolutionAndFetch() {
        when(credentialService.resolve(9L, true)).thenReturn(CREDENTIAL);
        TaskRow task = task(CollectionMode.FOLLOW_ONLY, SourceType.DYNAMIC, "{}");
        ResolvedContent resolved = resolved(SourceType.DYNAMIC);
        when(bilibiliClient.resolveDynamic("123456", CREDENTIAL)).thenReturn(resolved);
        when(bilibiliClient.fetchCommentPage(resolved, null, CREDENTIAL))
                .thenReturn(new CommentPage(List.of(), null, true));
        when(fencedWriteService.insertComments(
                        eq(7L), eq(31L), eq("fixture-lease"), anyList()))
                .thenReturn(new CommentRepository.InsertResult(0, 0, 0));

        service().collect(task, 31L, lease);

        verify(credentialService).resolve(9L, true);
        verify(bilibiliClient).resolveDynamic("123456", CREDENTIAL);
        verify(bilibiliClient).fetchCommentPage(resolved, null, CREDENTIAL);
    }

    private CommentCollectionService service() {
        return new CommentCollectionService(
                bilibiliClient,
                credentialService,
                fencedWriteService,
                executionMapper,
                eventPublisher,
                new ObjectMapper(),
                properties);
    }

    private String resolvedMetadata(String backfillCursor) {
        return backfillCursor == null
                ? "{\"oid\":\"987\",\"commentType\":\"1\"}"
                : "{\"oid\":\"987\",\"commentType\":\"1\",\"backfillCursor\":\""
                        + backfillCursor + "\"}";
    }

    private ResolvedContent resolved(SourceType sourceType) {
        return new ResolvedContent(sourceType, sourceType == SourceType.VIDEO ? "BVFixture" : "123456",
                "987", "1", null);
    }

    private TaskRow task(CollectionMode mode, SourceType sourceType, String metadata) {
        Instant now = Instant.parse("2026-07-14T00:00:00Z");
        return new TaskRow(
                7L, "Fixture", TaskType.CONTENT_COMMENTS, sourceType,
                sourceType == SourceType.VIDEO ? "BVFixture" : "123456",
                mode, DesiredState.ACTIVE,
                mode == CollectionMode.BACKFILL_ONLY ? ScheduleType.MANUAL : ScheduleType.ADAPTIVE,
                null, 9L, metadata, "{}", null, null, null, 0, now, now);
    }
}
