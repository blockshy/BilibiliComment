package com.hy.bilicomment.application.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hy.bilicomment.application.credential.CredentialService;
import com.hy.bilicomment.application.event.TaskEventPublisher;
import com.hy.bilicomment.application.task.TaskService;
import com.hy.bilicomment.domain.error.DomainException;
import com.hy.bilicomment.domain.task.CollectionMode;
import com.hy.bilicomment.domain.task.DesiredState;
import com.hy.bilicomment.domain.task.ScheduleType;
import com.hy.bilicomment.domain.task.SourceType;
import com.hy.bilicomment.domain.task.TaskType;
import com.hy.bilicomment.infrastructure.bilibili.BilibiliClient;
import com.hy.bilicomment.infrastructure.bilibili.BilibiliCredential;
import com.hy.bilicomment.infrastructure.bilibili.DiscoveredContent;
import com.hy.bilicomment.infrastructure.persistence.entity.TaskRow;
import com.hy.bilicomment.infrastructure.persistence.mapper.ExecutionMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class CreatorDiscoveryServiceTests {

    private static final BilibiliCredential CREDENTIAL =
            new BilibiliCredential(9L, "SESSDATA=fixture");

    @Mock private BilibiliClient bilibiliClient;
    @Mock private CredentialService credentialService;
    @Mock private ExecutionFencedWriteService fencedWriteService;
    @Mock private ExecutionMapper executionMapper;
    @Mock private TaskEventPublisher eventPublisher;
    @Mock private ExecutionLeaseManager.Lease lease;

    private CreatorDiscoveryService service;

    @BeforeEach
    void setUp() {
        service = new CreatorDiscoveryService(
                bilibiliClient,
                credentialService,
                fencedWriteService,
                executionMapper,
                eventPublisher,
                new ObjectMapper());
        when(credentialService.resolve(9L, true)).thenReturn(CREDENTIAL);
        when(lease.leaseOwner()).thenReturn("fixture-lease");
        when(executionMapper.updateProgress(
                        anyLong(), anyString(), any(), anyLong(), anyLong(), anyLong(), anyLong(), anyString()))
                .thenReturn(1);
    }

    @Test
    void ignoresUnknownWatchedTypesWithoutDiscardingAValidSibling() {
        TaskRow parent = parent("{\"watchedContentTypes\":[\"UNKNOWN\",\"VIDEO\"]}");
        DiscoveredContent video = content(SourceType.VIDEO, "BVVideo");
        DiscoveredContent dynamic = content(SourceType.DYNAMIC, "123456");
        when(bilibiliClient.discoverCreatorContent("42", CREDENTIAL))
                .thenReturn(List.of(video, dynamic));
        when(fencedWriteService.createDiscovered(
                        eq(7L), eq(31L), eq("fixture-lease"),
                        eq(video.title()), eq(SourceType.VIDEO), eq(video.sourceId()), eq(9L), anyMap()))
                .thenReturn(new TaskService.DiscoveredTaskResult(child(video), true));

        CreatorDiscoveryService.DiscoveryResult result = service.discover(parent, 31L, lease);

        assertThat(result).isEqualTo(new CreatorDiscoveryService.DiscoveryResult(1, 1, 0, 0));
        verify(fencedWriteService, never()).createDiscovered(
                eq(7L), eq(31L), eq("fixture-lease"),
                eq(dynamic.title()), eq(SourceType.DYNAMIC), eq(dynamic.sourceId()), any(), anyMap());
        verify(credentialService).resolve(9L, true);
        verify(bilibiliClient).discoverCreatorContent("42", CREDENTIAL);
    }

    @Test
    void emptyWatchedTypeSelectionFallsBackToBothSupportedContentTypes() {
        TaskRow parent = parent("{\"watchedContentTypes\":[]}");
        DiscoveredContent video = content(SourceType.VIDEO, "BVVideo");
        DiscoveredContent dynamic = content(SourceType.DYNAMIC, "123456");
        when(bilibiliClient.discoverCreatorContent("42", CREDENTIAL))
                .thenReturn(List.of(video, dynamic));
        when(fencedWriteService.createDiscovered(
                        eq(7L), eq(31L), eq("fixture-lease"),
                        anyString(), any(), anyString(), eq(9L), anyMap()))
                .thenAnswer(invocation -> {
                    SourceType type = invocation.getArgument(4);
                    String sourceId = invocation.getArgument(5);
                    return new TaskService.DiscoveredTaskResult(
                            child(content(type, sourceId)), true);
                });

        CreatorDiscoveryService.DiscoveryResult result = service.discover(parent, 31L, lease);

        assertThat(result.discovered()).isEqualTo(2);
        assertThat(result.created()).isEqualTo(2);
    }

    @Test
    void isolatesOneChildValidationFailureAndCountsAnExistingChildAsReused() {
        TaskRow parent = parent("{}");
        List<DiscoveredContent> contents = List.of(
                content(SourceType.VIDEO, "BVBad"),
                content(SourceType.VIDEO, "BVExisting"),
                content(SourceType.DYNAMIC, "987654"));
        when(bilibiliClient.discoverCreatorContent("42", CREDENTIAL)).thenReturn(contents);
        when(fencedWriteService.createDiscovered(
                        eq(7L), eq(31L), eq("fixture-lease"),
                        anyString(), any(), anyString(), eq(9L), anyMap()))
                .thenThrow(new DomainException("TASK_SOURCE_INVALID", "bad child"))
                .thenReturn(new TaskService.DiscoveredTaskResult(child(contents.get(1)), false))
                .thenReturn(new TaskService.DiscoveredTaskResult(child(contents.get(2)), true));

        CreatorDiscoveryService.DiscoveryResult result = service.discover(parent, 31L, lease);

        assertThat(result).isEqualTo(new CreatorDiscoveryService.DiscoveryResult(3, 1, 1, 1));
        verify(executionMapper).updateProgress(
                31L,
                "fixture-lease",
                com.hy.bilicomment.domain.execution.ExecutionPhase.SCHEDULING_NEXT,
                1,
                3,
                1,
                1,
                "{}");
        verify(eventPublisher).publish(
                eq(7L),
                eq(31L),
                eq("task.updated"),
                eq("UP 主内容监控完成，部分内容未能建立采集任务"),
                eq(Map.of("discovered", 3, "created", 1L, "reused", 1L, "failed", 1L)));
    }

    @Test
    void failsTheParentExecutionWhenNoDiscoveredChildCanBeCreatedOrReused() {
        TaskRow parent = parent("{}");
        DiscoveredContent content = content(SourceType.VIDEO, "BVBad");
        when(bilibiliClient.discoverCreatorContent("42", CREDENTIAL)).thenReturn(List.of(content));
        when(fencedWriteService.createDiscovered(
                        eq(7L),
                        eq(31L),
                        eq("fixture-lease"),
                        anyString(),
                        any(),
                        anyString(),
                        eq(9L),
                        anyMap()))
                .thenThrow(new DomainException("TASK_SOURCE_INVALID", "bad child"));

        assertThatThrownBy(() -> service.discover(parent, 31L, lease))
                .isInstanceOfSatisfying(
                        DomainException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("CREATOR_DISCOVERY_ALL_CHILDREN_FAILED"));

        verify(executionMapper).updateProgress(
                31L,
                "fixture-lease",
                com.hy.bilicomment.domain.execution.ExecutionPhase.SCHEDULING_NEXT,
                1,
                1,
                0,
                0,
                "{}");
        verify(eventPublisher).publish(
                eq(7L),
                eq(31L),
                eq("task.updated"),
                eq("UP 主内容监控失败，未能建立采集任务"),
                eq(Map.of("discovered", 1, "created", 0L, "reused", 0L, "failed", 1L)));
    }

    private TaskRow parent(String metadata) {
        Instant now = Instant.parse("2026-07-14T00:00:00Z");
        return new TaskRow(
                7L, "Creator", TaskType.CREATOR_WATCH, SourceType.CREATOR, "42",
                CollectionMode.FOLLOW_ONLY, DesiredState.ACTIVE, ScheduleType.ADAPTIVE,
                null, 9L, metadata, "{}", null, null, now, 0, now, now);
    }

    private TaskRow child(DiscoveredContent content) {
        Instant now = Instant.parse("2026-07-14T00:00:00Z");
        return new TaskRow(
                8L, content.title(), TaskType.CONTENT_COMMENTS, content.sourceType(), content.sourceId(),
                CollectionMode.FOLLOW_ONLY, DesiredState.ACTIVE, ScheduleType.ADAPTIVE,
                null, 9L, "{}", "{}", null, null, now, 0, now, now);
    }

    private DiscoveredContent content(SourceType type, String sourceId) {
        return new DiscoveredContent(type, sourceId, "Title " + sourceId, "987", "1");
    }
}
