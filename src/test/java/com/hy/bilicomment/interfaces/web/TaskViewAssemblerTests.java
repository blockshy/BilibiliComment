package com.hy.bilicomment.interfaces.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hy.bilicomment.application.task.TaskDiscoveryQueryService;
import com.hy.bilicomment.application.task.TaskDiscoveryQueryService.ParentContext;
import com.hy.bilicomment.domain.execution.ExecutionStatus;
import com.hy.bilicomment.domain.execution.TriggerType;
import com.hy.bilicomment.domain.task.CollectionMode;
import com.hy.bilicomment.domain.task.DesiredState;
import com.hy.bilicomment.domain.task.ScheduleType;
import com.hy.bilicomment.domain.task.SourceType;
import com.hy.bilicomment.domain.task.TaskDiscoveryRelationMode;
import com.hy.bilicomment.domain.task.TaskType;
import com.hy.bilicomment.infrastructure.persistence.entity.CredentialRow;
import com.hy.bilicomment.infrastructure.persistence.entity.ExecutionRow;
import com.hy.bilicomment.infrastructure.persistence.entity.TaskRow;
import com.hy.bilicomment.infrastructure.persistence.mapper.CredentialMapper;
import com.hy.bilicomment.infrastructure.persistence.mapper.ExecutionMapper;
import com.hy.bilicomment.interfaces.web.dto.TaskDtos;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class TaskViewAssemblerTests {

    private static final Instant NOW = Instant.parse("2026-07-14T00:00:00Z");

    @Mock
    private ExecutionMapper executionMapper;

    @Mock
    private CredentialMapper credentialMapper;

    @Mock
    private TaskDiscoveryQueryService discoveryQueryService;

    private TaskViewAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new TaskViewAssembler(
                executionMapper,
                credentialMapper,
                discoveryQueryService,
                new ObjectMapper());
    }

    @Test
    void summariesFetchExecutionStateInTwoBatchQueriesAndMapByTaskId() {
        TaskRow firstTask = task(1L, 11L);
        TaskRow secondTask = task(2L, null);
        ExecutionRow firstLatest = execution(103L, 1L, ExecutionStatus.FAILED, 17L, NOW.minusSeconds(5));
        ExecutionRow secondLatest = execution(202L, 2L, ExecutionStatus.RUNNING, 8L, null);
        ExecutionRow firstSuccess = execution(101L, 1L, ExecutionStatus.SUCCEEDED, 12L, NOW.minusSeconds(60));
        when(credentialMapper.findAll()).thenReturn(List.of(credential(11L, "主凭据")));
        when(executionMapper.findLatestByTaskIds(List.of(1L, 2L)))
                .thenReturn(List.of(secondLatest, firstLatest));
        when(executionMapper.findLastSucceededByTaskIds(List.of(1L, 2L)))
                .thenReturn(List.of(firstSuccess));

        List<TaskDtos.TaskSummary> summaries = assembler.summaries(List.of(firstTask, secondTask));

        assertThat(summaries).extracting(TaskDtos.TaskSummary::id).containsExactly("1", "2");
        TaskDtos.TaskSummary first = summaries.get(0);
        assertThat(first.runtimeState()).isEqualTo("ERROR");
        assertThat(first.health()).isEqualTo("ERROR");
        assertThat(first.latestInsertedCount()).isEqualTo(17L);
        assertThat(first.lastSuccessAt()).isEqualTo(NOW.minusSeconds(60));
        assertThat(first.credentialName()).isEqualTo("主凭据");
        TaskDtos.TaskSummary second = summaries.get(1);
        assertThat(second.runtimeState()).isEqualTo("RUNNING");
        assertThat(second.health()).isEqualTo("HEALTHY");
        assertThat(second.latestInsertedCount()).isEqualTo(8L);
        assertThat(second.lastSuccessAt()).isNull();
        assertThat(second.credentialName()).isEqualTo("未选择");
        verify(executionMapper, never()).findByTask(anyLong(), anyInt());
        verify(executionMapper, never()).findLastSucceeded(anyLong());
    }

    @Test
    void emptySummaryPageDoesNotInvokeAnyMapper() {
        assertThat(assembler.summaries(List.of())).isEmpty();

        verifyNoInteractions(executionMapper, credentialMapper, discoveryQueryService);
    }

    @Test
    void detailRetainsSingleTaskExecutionQueries() {
        TaskRow task = task(1L, null);
        ExecutionRow latest = execution(103L, 1L, ExecutionStatus.SUCCEEDED, 17L, NOW);
        when(credentialMapper.findAll()).thenReturn(List.of());
        when(executionMapper.findByTask(1L, 1)).thenReturn(List.of(latest));
        when(executionMapper.findLastSucceeded(1L)).thenReturn(Optional.of(latest));

        TaskDtos.TaskDetail detail = assembler.detail(task);

        assertThat(detail.runtimeState()).isEqualTo("COMPLETED");
        assertThat(detail.lastSuccessAt()).isEqualTo(NOW);
        assertThat(detail.latestInsertedCount()).isEqualTo(17L);
        verify(executionMapper, never()).findLatestByTaskIds(anyList());
        verify(executionMapper, never()).findLastSucceededByTaskIds(anyList());
    }

    @Test
    void discoveredOriginUsesGlobalManagedRelationWhileDisplayUsesRequestedParent() {
        TaskRow child = task(1L, null);
        ParentContext managedParent = new ParentContext(
                10L, "First creator", TaskDiscoveryRelationMode.MANAGED);
        ParentContext requestedParent = new ParentContext(
                20L, "Current creator", TaskDiscoveryRelationMode.REFERENCED);
        when(discoveryQueryService.preferredParents(List.of(1L)))
                .thenReturn(java.util.Map.of(1L, managedParent));
        when(credentialMapper.findAll()).thenReturn(List.of());
        when(executionMapper.findLatestByTaskIds(List.of(1L))).thenReturn(List.of());
        when(executionMapper.findLastSucceededByTaskIds(List.of(1L))).thenReturn(List.of());

        TaskDtos.TaskSummary summary = assembler
                .summaries(List.of(child), java.util.Map.of(1L, requestedParent))
                .get(0);

        assertThat(summary.origin()).isEqualTo("DISCOVERED");
        assertThat(summary.relationMode()).isEqualTo("REFERENCED");
        assertThat(summary.parentTask())
                .isEqualTo(new TaskDtos.ParentTask("20", "Current creator"));
    }

    private static TaskRow task(long taskId, Long credentialProfileId) {
        return new TaskRow(
                taskId,
                "Fixture task " + taskId,
                TaskType.CONTENT_COMMENTS,
                SourceType.VIDEO,
                "BVFixture" + taskId,
                CollectionMode.FOLLOW_ONLY,
                DesiredState.ACTIVE,
                ScheduleType.ADAPTIVE,
                null,
                credentialProfileId,
                "{\"title\":\"Fixture video " + taskId + "\"}",
                "{}",
                "fixture",
                NOW.minusSeconds(30),
                NOW.plusSeconds(30),
                3L,
                NOW.minusSeconds(3600),
                NOW);
    }

    private static ExecutionRow execution(
            long executionId,
            long taskId,
            ExecutionStatus status,
            long inserted,
            Instant finishedAt) {
        return new ExecutionRow(
                executionId,
                taskId,
                TriggerType.MANUAL,
                status,
                null,
                1,
                2L,
                inserted,
                inserted,
                0L,
                0,
                "{}",
                false,
                status == ExecutionStatus.FAILED ? "FIXTURE_FAILURE" : null,
                status == ExecutionStatus.FAILED ? "fixture failure" : null,
                "trace-fixture",
                null,
                NOW.minusSeconds(90),
                NOW.minusSeconds(80),
                NOW.minusSeconds(5),
                null,
                finishedAt,
                NOW.minusSeconds(90),
                NOW.minusSeconds(5));
    }

    private static CredentialRow credential(long credentialId, String displayName) {
        return new CredentialRow(
                credentialId,
                "fixture-credential",
                displayName,
                true,
                "encrypted-fixture",
                "VALID",
                NOW,
                null,
                1,
                1L,
                NOW.minusSeconds(3600),
                NOW);
    }
}
