package com.hy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hy.bilicomment.application.execution.TaskExecutionService;
import com.hy.bilicomment.application.execution.ExecutionFencedWriteService;
import com.hy.bilicomment.application.task.CreateTaskCommand;
import com.hy.bilicomment.application.task.TaskService;
import com.hy.bilicomment.domain.comment.CommentRecord;
import com.hy.bilicomment.domain.error.TaskConflictException;
import com.hy.bilicomment.domain.error.DomainException;
import com.hy.bilicomment.domain.execution.TriggerType;
import com.hy.bilicomment.domain.execution.ExecutionPhase;
import com.hy.bilicomment.domain.execution.ExecutionStatus;
import com.hy.bilicomment.domain.task.CollectionMode;
import com.hy.bilicomment.domain.task.DesiredState;
import com.hy.bilicomment.domain.task.ScheduleType;
import com.hy.bilicomment.domain.task.SourceType;
import com.hy.bilicomment.domain.task.TaskType;
import com.hy.bilicomment.infrastructure.persistence.CommentRepository;
import com.hy.bilicomment.infrastructure.persistence.entity.TaskRow;
import com.hy.bilicomment.infrastructure.persistence.entity.ExecutionRow;
import com.hy.bilicomment.infrastructure.persistence.mapper.ExecutionMapper;
import com.hy.bilicomment.infrastructure.persistence.mapper.CommentExportJobMapper;
import com.hy.bilicomment.infrastructure.persistence.mapper.TaskMapper;
import com.hy.bilicomment.interfaces.web.TaskController;
import com.hy.bilicomment.interfaces.web.dto.TaskDtos;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "app.environment=local",
    "app.admin.username=fixture-admin",
    "app.admin.password-hash=$2b$04$3Fes0J/H6vFND/6xJ0w7LOePze2Y2lQSw8V.CTI1T6/u01.aVhVu.",
    "app.crypto.credential-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "app.cursor.signing-key=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
    "app.exports.encryption-key=AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=",
    "app.exports.enabled=false",
    "app.scheduler.enabled=false",
    "app.scheduler.execution-dispatch-delay=1h",
    "app.scheduler.retry-recovery-delay=1h",
    "server.servlet.session.cookie.secure=false"
})
class ApplicationContextPostgresqlSmokeTests {

    @TempDir
    private static java.nio.file.Path exportDirectory;

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("bilicomment_context_test")
            .withUsername("bilicomment_test")
            .withPassword("fixture-password");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl()
                + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema=app");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("app.exports.directory", () -> exportDirectory.toString());
    }

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskController taskController;

    @MockitoBean
    private TaskExecutionService executionService;

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private ExecutionFencedWriteService fencedWriteService;

    @Autowired
    private ExecutionMapper executionMapper;

    @Autowired
    private CommentExportJobMapper commentExportJobMapper;

    @Autowired
    private TaskMapper taskMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @LocalServerPort
    private int port;

    @AfterEach
    void resetExecutionService() {
        reset(executionService);
    }

    @Test
    void startsTheCompleteApplicationAgainstPostgresql17() throws Exception {
        assertThat(applicationContext.getBean(BiliBiliCommentApplication.class)).isNotNull();
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT count(*) FROM app.flyway_schema_history "
                                + "WHERE success AND version IS NOT NULL")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getLong(1)).isEqualTo(10L);
        }
    }

    @Test
    void commentExportMapperClaimsAQueuedJobAndMapsQualifiedReturningColumns() {
        TaskRow task = taskService.create(new CreateTaskCommand(
                "Export mapper fixture",
                TaskType.CONTENT_COMMENTS,
                SourceType.VIDEO,
                "BV1AK411C7mD",
                CollectionMode.FOLLOW_ONLY,
                DesiredState.ACTIVE,
                ScheduleType.ADAPTIVE,
                null,
                "Asia/Shanghai",
                null,
                "integration fixture",
                Map.of()));
        var inserted = commentExportJobMapper.insert(
                task.taskId(),
                "fixture-admin",
                "CSV",
                "[\"RPID\",\"CONTENT\"]",
                "{}",
                "0".repeat(64),
                "CTIME_DESC",
                0L,
                100L,
                1024L,
                Instant.now().plusSeconds(3600));

        String oldOwner = "old-export-worker";
        var claimed = commentExportJobMapper.claimNext(oldOwner, 600L).orElseThrow();

        assertThat(claimed.exportId()).isEqualTo(inserted.exportId());
        assertThat(claimed.status()).isEqualTo("RUNNING");
        assertThat(claimed.workerOwner()).isEqualTo(oldOwner);
        assertThat(claimed.exportColumnsJson()).isEqualTo("[\"RPID\", \"CONTENT\"]");
        assertThat(commentExportJobMapper.updateProgress(
                        claimed.exportId(), 1L, 10L, oldOwner))
                .isEqualTo(1);

        jdbcTemplate.update(
                "UPDATE app.comment_export_job SET lease_until = clock_timestamp() - interval '1 second' "
                        + "WHERE export_id = ?",
                claimed.exportId());
        assertThat(commentExportJobMapper.recoverStale()).isEqualTo(1);
        String newOwner = "new-export-worker";
        var reclaimed = commentExportJobMapper.claimNext(newOwner, 600L).orElseThrow();
        assertThat(reclaimed.exportId()).isEqualTo(claimed.exportId());
        assertThat(reclaimed.workerOwner()).isEqualTo(newOwner);

        assertThat(commentExportJobMapper.updateProgress(
                        claimed.exportId(), 2L, 20L, oldOwner))
                .isZero();
        assertThat(commentExportJobMapper.markSucceeded(
                        claimed.exportId(),
                        2L,
                        20L,
                        "0123456789abcdef0123456789abcdef",
                        "0".repeat(64),
                        oldOwner))
                .isZero();
        assertThat(commentExportJobMapper.updateProgress(
                        claimed.exportId(), 2L, 20L, newOwner))
                .isEqualTo(1);
        commentExportJobMapper.requestCancellation(claimed.exportId());
        assertThat(commentExportJobMapper.markCancelled(claimed.exportId(), oldOwner)).isZero();
        assertThat(commentExportJobMapper.markCancelled(claimed.exportId(), newOwner)).isEqualTo(1);
    }

    @Test
    void contentTaskCreationDefersCommentTableDdlToControlledFunctions() {
        TaskRow task = taskService.create(new CreateTaskCommand(
                "Context fixture task",
                TaskType.CONTENT_COMMENTS,
                SourceType.VIDEO,
                "BV1AB411C7mD",
                CollectionMode.FOLLOW_ONLY,
                DesiredState.ACTIVE,
                ScheduleType.ADAPTIVE,
                null,
                "Asia/Shanghai",
                null,
                "integration fixture",
                Map.of()));
        String relation = "comment_data.comment_task_" + task.taskId();

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT to_regclass(?) IS NULL", Boolean.class, relation))
                .isTrue();
        assertThat(commentRepository.count(task.taskId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT to_regclass(?) IS NOT NULL", Boolean.class, relation))
                .isTrue();
    }

    @Test
    void commentRepositoryMapsPostgresqlTimestampsAndBindsTheNextPageCursor() {
        TaskRow task = taskService.create(new CreateTaskCommand(
                "Comment repository fixture",
                TaskType.CONTENT_COMMENTS,
                SourceType.VIDEO,
                "BV1AH411C7mD",
                CollectionMode.FOLLOW_ONLY,
                DesiredState.ACTIVE,
                ScheduleType.ADAPTIVE,
                null,
                "Asia/Shanghai",
                null,
                null,
                Map.of()));
        Instant newest = Instant.parse("2026-07-14T02:00:00Z");
        Instant oldest = Instant.parse("2026-07-14T01:00:00Z");

        CommentRepository.InsertResult inserted = commentRepository.insert(task.taskId(), List.of(
                new CommentRecord(303L, "fixture-303", "Newest", null, 6, "newest", newest, null),
                new CommentRecord(302L, "fixture-302", "Same time", null, 5, "same", newest, null),
                new CommentRecord(301L, "fixture-301", "Oldest", null, 4, "oldest", oldest, null)));

        assertThat(inserted).isEqualTo(new CommentRepository.InsertResult(3, 3, 0));
        List<CommentRepository.CommentView> firstPage =
                commentRepository.findPage(task.taskId(), null, null, 2);
        assertThat(firstPage)
                .extracting(CommentRepository.CommentView::rpid)
                .containsExactly(303L, 302L);
        assertThat(firstPage)
                .extracting(CommentRepository.CommentView::ctime)
                .containsExactly(newest, newest);
        assertThat(firstPage)
                .allSatisfy(comment -> assertThat(comment.createdAt()).isNotNull());

        CommentRepository.CommentView boundary = firstPage.getLast();
        List<CommentRepository.CommentView> nextPage = commentRepository.findPage(
                task.taskId(), boundary.ctime(), boundary.rpid(), 2);
        assertThat(nextPage).singleElement().satisfies(comment -> {
            assertThat(comment.rpid()).isEqualTo(301L);
            assertThat(comment.ctime()).isEqualTo(oldest);
            assertThat(comment.createdAt()).isNotNull();
        });
    }

    @Test
    void repeatedTaskCreationReturnsAConflictWithoutAbortingThePostgresqlTransaction() {
        CreateTaskCommand command = new CreateTaskCommand(
                "Duplicate context fixture",
                TaskType.CONTENT_COMMENTS,
                SourceType.VIDEO,
                "BV1AC411C7mD",
                CollectionMode.FOLLOW_ONLY,
                DesiredState.ACTIVE,
                ScheduleType.ADAPTIVE,
                null,
                "Asia/Shanghai",
                null,
                null,
                Map.of());
        TaskRow first = taskService.create(command);

        assertThatThrownBy(() -> taskService.create(command))
                .isInstanceOfSatisfying(TaskConflictException.class,
                        exception -> assertThat(exception.getExistingTaskId())
                                .isEqualTo(first.taskId()));
        assertThat(taskService.require(first.taskId())).isEqualTo(first);
    }

    @Test
    void discoveredTaskCreationReusesTheExistingRowInsideOnePostgresqlTransaction() {
        TaskService.DiscoveredTaskResult first = taskService.createDiscovered(
                "Discovered context fixture",
                SourceType.VIDEO,
                "BV1AD411C7mD",
                null,
                Map.of("fixture", true));
        TaskService.DiscoveredTaskResult repeated = taskService.createDiscovered(
                "Discovered context fixture",
                SourceType.VIDEO,
                "BV1AD411C7mD",
                null,
                Map.of("fixture", true));

        assertThat(first.created()).isTrue();
        assertThat(repeated.created()).isFalse();
        assertThat(repeated.task().taskId()).isEqualTo(first.task().taskId());
    }

    @Test
    void failedStartNowRollsBackTaskAndClaimSoTheSameIdempotencyKeyCanRetryOnce() {
        String sourceId = "BV1AE411C7mD";
        String idempotencyKey = "context-start-now-retry";
        TaskController.CreateTaskRequest request = new TaskController.CreateTaskRequest(
                "Start-now transaction fixture",
                TaskType.CONTENT_COMMENTS,
                new TaskController.TaskSourceInput(SourceType.VIDEO, sourceId),
                CollectionMode.FOLLOW_ONLY,
                "1",
                new TaskController.TaskScheduleInput(
                        ScheduleType.ADAPTIVE, null, "Asia/Shanghai"),
                List.of(),
                DesiredState.ACTIVE,
                true,
                null);
        DomainException queueFailure = new DomainException(
                "WORKER_QUEUE_UNAVAILABLE", "fixture queue failure");
        when(executionService.queue(anyLong(), eq(TriggerType.MANUAL)))
                .thenThrow(queueFailure)
                .thenReturn(null);

        assertThatThrownBy(() -> taskController.create(idempotencyKey, request))
                .isSameAs(queueFailure);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM app.task_definition WHERE source_id = ?",
                        Long.class,
                        sourceId))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM app.idempotency_request WHERE idempotency_key = ?",
                        Long.class,
                        idempotencyKey))
                .isZero();

        TaskDtos.TaskDetail created = taskController.create(idempotencyKey, request);
        TaskDtos.TaskDetail replayed = taskController.create(idempotencyKey, request);

        assertThat(replayed.id()).isEqualTo(created.id());
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM app.task_definition WHERE source_id = ?",
                        Long.class,
                        sourceId))
                .isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM app.idempotency_request"
                                + " WHERE idempotency_key = ? AND state = 'COMPLETED'",
                        Long.class,
                        idempotencyKey))
                .isEqualTo(1L);
        verify(executionService, times(2)).queue(anyLong(), eq(TriggerType.MANUAL));
    }

    @Test
    void retryWaitTransitionsPersistRetryCountBackoffAndAttemptInPostgresql() {
        TaskRow task = taskService.create(new CreateTaskCommand(
                "Retry state fixture",
                TaskType.CONTENT_COMMENTS,
                SourceType.VIDEO,
                "BV1AF411C7mD",
                CollectionMode.FOLLOW_ONLY,
                DesiredState.ACTIVE,
                ScheduleType.ADAPTIVE,
                null,
                "Asia/Shanghai",
                null,
                null,
                Map.of()));
        ExecutionRow queued = executionMapper.insertQueued(
                task.taskId(), TriggerType.MANUAL, "retry-context-trace");
        java.time.Instant retryAt = java.time.Instant.parse("2030-01-01T00:00:00Z");
        assertThat(executionMapper.claimQueued(
                        queued.executionId(), "fixture-lease", "fixture-worker", 120L))
                .isEqualTo(1);

        assertThat(executionMapper.markRetryWaiting(
                        queued.executionId(),
                        "fixture-lease",
                        retryAt,
                        "BILIBILI_NETWORK_ERROR",
                        "fixture network"))
                .isEqualTo(1);
        ExecutionRow waiting = executionMapper.findById(queued.executionId()).orElseThrow();
        assertThat(waiting.status()).isEqualTo(ExecutionStatus.RETRY_WAIT);
        assertThat(waiting.retryCount()).isEqualTo(1);
        assertThat(waiting.nextRetryAt()).isEqualTo(retryAt);
        assertThat(executionMapper.resumeRetry(queued.executionId(), retryAt.minusSeconds(1)))
                .isZero();
        assertThat(executionMapper.resumeRetry(queued.executionId(), retryAt))
                .isEqualTo(1);
        ExecutionRow resumed = executionMapper.findById(queued.executionId()).orElseThrow();
        assertThat(resumed.status()).isEqualTo(ExecutionStatus.QUEUED);
        assertThat(resumed.attempt()).isEqualTo(2);
        assertThat(resumed.retryCount()).isEqualTo(1);
        assertThat(resumed.nextRetryAt()).isNull();
    }

    @Test
    void expiredLeaseCannotRenewWriteProgressOrFinishAndIsDurablyRequeued() {
        TaskRow task = taskService.create(new CreateTaskCommand(
                "Execution lease fixture",
                TaskType.CONTENT_COMMENTS,
                SourceType.VIDEO,
                "BV1AL411C7mD",
                CollectionMode.FOLLOW_ONLY,
                DesiredState.ACTIVE,
                ScheduleType.ADAPTIVE,
                null,
                "Asia/Shanghai",
                null,
                null,
                Map.of()));
        ExecutionRow queued = executionMapper.insertQueued(
                task.taskId(), TriggerType.MANUAL, "lease-context-trace");
        assertThat(executionMapper.claimQueued(
                        queued.executionId(), "expired-lease", "fixture-worker", 120L))
                .isEqualTo(1);
        jdbcTemplate.update(
                "UPDATE app.task_execution SET lease_until = clock_timestamp() - interval '1 second'"
                        + " WHERE execution_id = ?",
                queued.executionId());

        assertThat(executionMapper.renewLease(
                        queued.executionId(), "expired-lease", 120L))
                .isZero();
        assertThat(executionMapper.updateProgress(
                        queued.executionId(),
                        "expired-lease",
                        ExecutionPhase.PERSISTING_COMMENTS,
                        1,
                        1,
                        1,
                        0,
                        "{}"))
                .isZero();
        assertThat(executionMapper.markSucceeded(queued.executionId(), "expired-lease"))
                .isZero();
        CommentRecord staleComment = new CommentRecord(
                990031L,
                "lease-fixture",
                "Stale worker",
                null,
                1,
                "must not be inserted",
                Instant.now(),
                null);
        assertThatThrownBy(() -> fencedWriteService.insertComments(
                        task.taskId(),
                        queued.executionId(),
                        "expired-lease",
                        List.of(staleComment)))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("EXECUTION_LEASE_LOST"));
        assertThat(commentRepository.count(task.taskId())).isZero();

        assertThat(executionMapper.requeueExpiredLeases(100))
                .extracting(ExecutionRow::executionId)
                .contains(queued.executionId());
        ExecutionRow recovered = executionMapper.findById(queued.executionId()).orElseThrow();
        assertThat(recovered.status()).isEqualTo(ExecutionStatus.QUEUED);
        assertThat(recovered.attempt()).isEqualTo(2);
        assertThat(recovered.errorCode()).isEqualTo("EXECUTION_LEASE_EXPIRED");

        assertThat(executionMapper.claimQueued(
                        queued.executionId(), "replacement-lease", "fixture-worker", 120L))
                .isEqualTo(1);
        ExecutionRow replacement = executionMapper.findById(queued.executionId()).orElseThrow();
        assertThat(replacement.errorCode()).isNull();
        assertThat(replacement.errorSummary()).isNull();
        assertThat(executionMapper.markFailedOwned(
                        queued.executionId(),
                        "replacement-lease",
                        "FIXTURE_CLEANUP",
                        "fixture cleanup"))
                .isEqualTo(1);
    }

    @Test
    void taskMapperAcceptsNullNextExecutionAndPersistsTheLastExecutionTime() {
        TaskRow task = taskService.create(new CreateTaskCommand(
                "Null next execution fixture",
                TaskType.CONTENT_COMMENTS,
                SourceType.VIDEO,
                "BV1AI411C7mD",
                CollectionMode.FOLLOW_ONLY,
                DesiredState.ACTIVE,
                ScheduleType.ADAPTIVE,
                null,
                "Asia/Shanghai",
                null,
                null,
                Map.of()));
        Instant lastExecutionAt = Instant.parse("2026-07-14T03:00:00Z");

        assertThat(taskMapper.updateSchedule(task.taskId(), lastExecutionAt, null)).isEqualTo(1);

        TaskRow updated = taskMapper.findById(task.taskId()).orElseThrow();
        assertThat(updated.lastExecutionAt()).isEqualTo(lastExecutionAt);
        assertThat(updated.nextExecutionAt()).isNull();
    }

    @Test
    void taskMapperPausesAfterCompletionAndAdvancesTheVersionAtomically() {
        TaskRow task = taskService.create(new CreateTaskCommand(
                "Pause after completion fixture",
                TaskType.CONTENT_COMMENTS,
                SourceType.VIDEO,
                "BV1AJ411C7mD",
                CollectionMode.BACKFILL_ONLY,
                DesiredState.ACTIVE,
                ScheduleType.MANUAL,
                null,
                "Asia/Shanghai",
                null,
                null,
                Map.of()));
        Instant completedAt = Instant.parse("2026-07-14T04:00:00Z");

        assertThat(taskMapper.pauseAfterCompletion(task.taskId(), completedAt)).isEqualTo(1);

        TaskRow paused = taskMapper.findById(task.taskId()).orElseThrow();
        assertThat(paused.desiredState()).isEqualTo(DesiredState.PAUSED);
        assertThat(paused.lastExecutionAt()).isEqualTo(completedAt);
        assertThat(paused.nextExecutionAt()).isNull();
        assertThat(paused.version()).isEqualTo(task.version() + 1);
    }

    @Test
    void taskQueriesFilterCollectionModesConsistentlyInPostgresql() {
        String sourceId = "BV1AG411C7mD";
        taskService.create(new CreateTaskCommand(
                "Mode follow fixture", TaskType.CONTENT_COMMENTS, SourceType.VIDEO, sourceId,
                CollectionMode.FOLLOW_ONLY, DesiredState.ACTIVE, ScheduleType.ADAPTIVE,
                null, "Asia/Shanghai", null, null, Map.of()));
        taskService.create(new CreateTaskCommand(
                "Mode backfill fixture", TaskType.CONTENT_COMMENTS, SourceType.VIDEO, sourceId,
                CollectionMode.BACKFILL_ONLY, DesiredState.ACTIVE, ScheduleType.MANUAL,
                null, "Asia/Shanghai", null, null, Map.of()));

        assertThat(taskMapper.findPage(
                        sourceId, null, SourceType.VIDEO, CollectionMode.FOLLOW_ONLY,
                        null, null, 10, null))
                .singleElement()
                .extracting(TaskRow::collectionMode)
                .isEqualTo(CollectionMode.FOLLOW_ONLY);
        assertThat(taskMapper.countFiltered(
                        sourceId, null, SourceType.VIDEO, CollectionMode.BACKFILL_ONLY,
                        null, null))
                .isEqualTo(1L);
    }

    @Test
    void taskKeysetPageDoesNotDriftWhenAnExecutionUpdatesAnOlderTask() {
        String prefix = "Keyset execution update fixture";
        List<TaskRow> created = new ArrayList<>();
        for (int index = 1; index <= 4; index++) {
            created.add(taskService.create(new CreateTaskCommand(
                    prefix + " " + index,
                    TaskType.CONTENT_COMMENTS,
                    SourceType.VIDEO,
                    String.format("BV1pg%07d", index),
                    CollectionMode.FOLLOW_ONLY,
                    DesiredState.ACTIVE,
                    ScheduleType.ADAPTIVE,
                    null,
                    "Asia/Shanghai",
                    null,
                    null,
                    Map.of())));
        }
        List<Long> expectedIds = created.stream()
                .map(TaskRow::taskId)
                .sorted(Comparator.reverseOrder())
                .toList();

        List<TaskRow> firstPageWithLookahead = taskMapper.findPage(
                prefix, null, SourceType.VIDEO, CollectionMode.FOLLOW_ONLY,
                null, null, 3, null);
        List<TaskRow> firstPage = firstPageWithLookahead.subList(0, 2);
        long boundaryTaskId = firstPage.getLast().taskId();
        long olderTaskId = expectedIds.getLast();
        Instant previousUpdatedAt = taskMapper.findById(olderTaskId).orElseThrow().updatedAt();

        assertThat(taskMapper.updateSchedule(
                        olderTaskId,
                        Instant.parse("2026-07-14T04:30:00Z"),
                        Instant.parse("2026-07-14T04:35:00Z")))
                .isEqualTo(1);
        assertThat(taskMapper.findById(olderTaskId).orElseThrow().updatedAt())
                .isAfter(previousUpdatedAt);

        List<TaskRow> secondPage = taskMapper.findPage(
                prefix, null, SourceType.VIDEO, CollectionMode.FOLLOW_ONLY,
                null, null, 3, boundaryTaskId);
        List<Long> pagedIds = new ArrayList<>();
        pagedIds.addAll(firstPage.stream().map(TaskRow::taskId).toList());
        pagedIds.addAll(secondPage.stream().map(TaskRow::taskId).toList());

        assertThat(pagedIds).containsExactlyElementsOf(expectedIds);
    }

    @Test
    void embeddedTomcatPublishesTraceAndStrictCsrfCookie() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/me"))
                        .header("X-Request-ID", "fixture-real-tomcat-trace")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("X-Trace-ID"))
                .contains("fixture-real-tomcat-trace");
        assertThat(response.headers().allValues("Set-Cookie"))
                .anySatisfy(value -> assertThat(value)
                        .contains("XSRF-TOKEN=", "Path=/", "SameSite=Strict"));
        assertThat(objectMapper.readTree(response.body()).path("traceId").asText())
                .isEqualTo("fixture-real-tomcat-trace");
    }
}
