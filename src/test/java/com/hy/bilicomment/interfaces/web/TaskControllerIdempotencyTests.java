package com.hy.bilicomment.interfaces.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hy.bilicomment.application.execution.TaskExecutionService;
import com.hy.bilicomment.application.idempotency.IdempotencyService;
import com.hy.bilicomment.application.scheduling.DynamicTaskRegistry;
import com.hy.bilicomment.application.source.SourceNormalizer;
import com.hy.bilicomment.application.task.CreateTaskCommand;
import com.hy.bilicomment.application.task.TaskDiscoveryQueryService;
import com.hy.bilicomment.application.task.TaskService;
import com.hy.bilicomment.domain.error.DomainException;
import com.hy.bilicomment.domain.execution.TriggerType;
import com.hy.bilicomment.domain.task.CollectionMode;
import com.hy.bilicomment.domain.task.DesiredState;
import com.hy.bilicomment.domain.task.ScheduleType;
import com.hy.bilicomment.domain.task.SourceType;
import com.hy.bilicomment.domain.task.TaskType;
import com.hy.bilicomment.infrastructure.persistence.entity.TaskRow;
import com.hy.bilicomment.infrastructure.persistence.mapper.ExecutionMapper;
import com.hy.bilicomment.infrastructure.persistence.mapper.TaskMapper;
import com.hy.bilicomment.interfaces.web.dto.TaskDtos;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class TaskControllerIdempotencyTests {

    @Mock
    private TaskService taskService;

    @Mock
    private TaskExecutionService executionService;

    @Mock
    private TaskMapper taskMapper;

    @Mock
    private ExecutionMapper executionMapper;

    @Mock
    private TaskViewAssembler assembler;

    @Mock
    private SourceNormalizer sourceNormalizer;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private DynamicTaskRegistry taskRegistry;

    @Mock
    private TaskDiscoveryQueryService discoveryQueryService;

    private TaskController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new TaskController(
                taskService,
                executionService,
                taskMapper,
                executionMapper,
                assembler,
                sourceNormalizer,
                idempotencyService,
                taskRegistry,
                discoveryQueryService,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-07-14T00:00:00Z"), ZoneOffset.UTC));
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void replaysTheOriginalResourceWithoutCreatingOrDispatchingAgain() {
        TaskRow task = task();
        TaskDtos.TaskDetail detail = detail();
        when(idempotencyService.claim(eq("CREATE_TASK"), eq("fixture-key"), anyString()))
                .thenReturn(new IdempotencyService.Claim(11L, true, "73"));
        when(taskService.require(73L)).thenReturn(task);
        when(assembler.detail(task)).thenReturn(detail);

        assertThat(controller.create("fixture-key", request(true))).isSameAs(detail);

        verify(taskService, never()).create(any(CreateTaskCommand.class));
        verify(executionService, never()).queue(anyLong(), any());
        verify(idempotencyService, never()).complete(anyLong(), anyString());
        verify(idempotencyService, never()).fail(anyLong());
        verify(taskRegistry, never()).reconcileNow();
    }

    @Test
    void completesTheClaimOnlyAfterCreationAndOptionalDispatch() {
        TaskRow task = task();
        TaskDtos.TaskDetail detail = detail();
        when(idempotencyService.claim(eq("CREATE_TASK"), eq("fixture-key"), anyString()))
                .thenReturn(new IdempotencyService.Claim(11L, false, null));
        when(taskService.create(any(CreateTaskCommand.class))).thenReturn(task);
        when(taskService.require(73L)).thenReturn(task);
        when(assembler.detail(task)).thenReturn(detail);

        assertThat(controller.create("fixture-key", request(true))).isSameAs(detail);

        InOrder order = inOrder(executionService, idempotencyService);
        order.verify(executionService).queue(73L, TriggerType.MANUAL);
        order.verify(idempotencyService).complete(11L, "73");
        verify(taskRegistry).reconcileNow();
    }

    @Test
    void releasesTheClaimWhenCreationFails() {
        DomainException failure = new DomainException("TASK_SOURCE_MISMATCH", "Fixture failure");
        when(idempotencyService.claim(eq("CREATE_TASK"), eq("fixture-key"), anyString()))
                .thenReturn(new IdempotencyService.Claim(11L, false, null));
        when(taskService.create(any(CreateTaskCommand.class))).thenThrow(failure);

        assertThatThrownBy(() -> controller.create("fixture-key", request(false)))
                .isSameAs(failure);

        verify(idempotencyService).fail(11L);
        verify(idempotencyService, never()).complete(anyLong(), anyString());
        verify(taskRegistry, never()).reconcileNow();
    }

    @Test
    void releasesTheClaimAndDoesNotCompleteItWhenStartNowQueueingFails() {
        TaskRow task = task();
        DomainException failure = new DomainException("TASK_ALREADY_RUNNING", "Fixture queue failure");
        when(idempotencyService.claim(eq("CREATE_TASK"), eq("fixture-key"), anyString()))
                .thenReturn(new IdempotencyService.Claim(11L, false, null));
        when(taskService.create(any(CreateTaskCommand.class))).thenReturn(task);
        when(executionService.queue(73L, TriggerType.MANUAL)).thenThrow(failure);

        assertThatThrownBy(() -> controller.create("fixture-key", request(true)))
                .isSameAs(failure);

        verify(idempotencyService).fail(11L);
        verify(idempotencyService, never()).complete(anyLong(), anyString());
        verify(taskService, never()).require(anyLong());
        verify(taskRegistry, never()).reconcileNow();
    }

    @Test
    void rejectsCreateRequestWhenRequiredStartNowFieldIsMissing() throws Exception {
        mockMvc.perform(post("/api/v1/tasks")
                        .header("Idempotency-Key", "fixture-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Fixture task",
                                  "kind": "CONTENT_COMMENTS",
                                  "source": {"type": "VIDEO", "input": "BVFixture"},
                                  "collectionMode": "FOLLOW_ONLY",
                                  "credentialProfileId": "1",
                                  "schedule": {"strategy": "ADAPTIVE", "zoneId": "Asia/Shanghai"},
                                  "watchedContentTypes": [],
                                  "desiredState": "ACTIVE"
                                }
                                """))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.startNow").exists());

        verifyNoInteractions(idempotencyService);
    }

    private TaskController.CreateTaskRequest request(boolean startNow) {
        return new TaskController.CreateTaskRequest(
                "Fixture task",
                TaskType.CONTENT_COMMENTS,
                new TaskController.TaskSourceInput(SourceType.VIDEO, "BVFixture"),
                CollectionMode.FOLLOW_ONLY,
                "1",
                new TaskController.TaskScheduleInput(
                        ScheduleType.ADAPTIVE,
                        null,
                        "Asia/Shanghai"),
                List.of(),
                DesiredState.ACTIVE,
                startNow,
                null);
    }

    private TaskRow task() {
        Instant now = Instant.parse("2026-07-14T00:00:00Z");
        return new TaskRow(
                73L,
                "Fixture task",
                TaskType.CONTENT_COMMENTS,
                SourceType.VIDEO,
                "BVFixture",
                CollectionMode.FOLLOW_ONLY,
                DesiredState.ACTIVE,
                ScheduleType.ADAPTIVE,
                null,
                1L,
                "{}",
                "{}",
                null,
                null,
                now.plusSeconds(30),
                0,
                now,
                now);
    }

    private TaskDtos.TaskDetail detail() {
        Instant now = Instant.parse("2026-07-14T00:00:00Z");
        return new TaskDtos.TaskDetail(
                "73",
                0,
                "Fixture task",
                "CONTENT_COMMENTS",
                new TaskDtos.TaskSource("VIDEO", "BVFixture", "Fixture task"),
                "FOLLOW_ONLY",
                "ACTIVE",
                "IDLE",
                "UNKNOWN",
                "自适应",
                "Fixture credential",
                null,
                now.plusSeconds(30),
                0,
                now,
                "MANUAL",
                null,
                null,
                null,
                null,
                now,
                List.of());
    }
}
