package com.hy.bilicomment.infrastructure.persistence.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExecutionMapperSqlTests {

    private Configuration configuration;

    @BeforeEach
    void setUp() {
        configuration = new Configuration();
        configuration.addMapper(ExecutionMapper.class);
    }

    @Test
    void rendersPostgresqlLatestQueriesForAllRequestedTaskIds() {
        BoundSql latest = boundSql("findLatestByTaskIds", List.of(11L, 22L));
        BoundSql lastSucceeded = boundSql("findLastSucceededByTaskIds", List.of(11L, 22L));

        assertThat(normalize(latest.getSql()))
                .contains(
                        "SELECT DISTINCT ON (task_id)",
                        "WHERE task_id IN ( ? , ? )",
                        "ORDER BY task_id, execution_id DESC")
                .doesNotContain("status = 'SUCCEEDED'");
        assertThat(latest.getParameterMappings()).hasSize(2);
        assertThat(normalize(lastSucceeded.getSql()))
                .contains(
                        "SELECT DISTINCT ON (task_id)",
                        "WHERE task_id IN ( ? , ? ) AND status = 'SUCCEEDED'",
                        "ORDER BY task_id, execution_id DESC");
        assertThat(lastSucceeded.getParameterMappings()).hasSize(2);
    }

    @Test
    void rendersNoRowsPredicateForEmptyOrNullTaskIds() {
        for (String method : List.of("findLatestByTaskIds", "findLastSucceededByTaskIds")) {
            BoundSql empty = boundSql(method, List.of());
            BoundSql absent = boundSql(method, null);

            assertThat(normalize(empty.getSql())).contains("WHERE 1 = 0").doesNotContain("IN ()");
            assertThat(empty.getParameterMappings()).isEmpty();
            assertThat(normalize(absent.getSql())).contains("WHERE 1 = 0").doesNotContain("IN ()");
            assertThat(absent.getParameterMappings()).isEmpty();
        }
    }

    @Test
    void leaseOwnedWritesRejectExpiredClaimsAndNewClaimsClearRecoveryErrors() {
        Map<String, Object> owned = new HashMap<>();
        owned.put("executionId", 31L);
        owned.put("taskId", 7L);
        owned.put("leaseOwner", "fixture-lease");
        owned.put("leaseSeconds", 120L);
        owned.put("workerId", "fixture-worker");
        owned.put("phase", null);
        owned.put("pagesProcessed", 1L);
        owned.put("commentsDiscovered", 1L);
        owned.put("commentsInserted", 1L);
        owned.put("commentsDuplicate", 0L);
        owned.put("cursorStateJson", "{}");
        owned.put("nextRetryAt", Instant.parse("2030-01-01T00:01:00Z"));
        owned.put("errorCode", "FIXTURE");
        owned.put("errorSummary", "fixture");
        owned.put("summary", "fixture");
        owned.put("now", Instant.parse("2030-01-01T00:00:00Z"));
        owned.put("limit", 10);

        assertThat(normalize(mappedSql("claimQueued", owned)))
                .contains(
                        "lease_until = clock_timestamp() + (? * interval '1 second')",
                        "error_code = NULL",
                        "error_summary = NULL");
        assertThat(normalize(mappedSql("renewLease", owned)))
                .contains("lease_until = clock_timestamp() + (? * interval '1 second')");
        assertThat(normalize(mappedSql("ownsLease", owned)))
                .contains("lease_until > clock_timestamp()");
        assertThat(normalize(mappedSql("requeueExpiredLeases", owned)))
                .contains(
                        "lease_until <= clock_timestamp()",
                        "RETURNING execution.*",
                        "SELECT execution_id",
                        "FROM requeued")
                .doesNotContain("RETURNINGexecution");
        assertThat(normalize(mappedSql("lockOwnedLease", owned)))
                .contains(
                        "lease_owner = ?",
                        "lease_until > clock_timestamp()",
                        "FOR UPDATE");
        for (String method : List.of(
                "renewLease",
                "updateProgress",
                "markSucceeded",
                "markRetryWaiting",
                "markFailedOwned",
                "markCancelledOwned")) {
            assertThat(normalize(mappedSql(method, owned)))
                    .as(method)
                    .contains("lease_owner = ?", "lease_until > clock_timestamp()");
        }
    }

    @Test
    void initialExecutionInsertIsIdempotentAgainstHistoryAndTheActiveExecutionIndex() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("taskId", 7L);
        parameters.put("triggerType", com.hy.bilicomment.domain.execution.TriggerType.CREATOR_DISCOVERY);
        parameters.put("traceId", "fixture-trace");

        assertThat(normalize(mappedSql("insertInitialQueued", parameters)))
                .contains(
                        "WHERE NOT EXISTS (SELECT 1 FROM app.task_execution WHERE task_id = ?)",
                        "ON CONFLICT (task_id) WHERE status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT')",
                        "DO NOTHING RETURNING execution_id");
    }

    @Test
    void globalInsertedCommentMetricExcludesCreatorDiscoveryCounters() {
        Map<String, Object> parameters = Map.of(
                "since", Instant.parse("2030-01-01T00:00:00Z"));

        assertThat(normalize(mappedSql("sumInsertedSince", parameters)))
                .contains(
                        "FROM app.task_execution execution",
                        "JOIN app.task_definition task_definition ON task_definition.task_id = execution.task_id",
                        "execution.finished_at >= ?",
                        "task_definition.task_type = 'CONTENT_COMMENTS'");
    }

    private BoundSql boundSql(String method, List<Long> taskIds) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("taskIds", taskIds);
        return configuration
                .getMappedStatement(ExecutionMapper.class.getName() + "." + method)
                .getBoundSql(parameters);
    }

    private String mappedSql(String method, Map<String, Object> parameters) {
        return configuration
                .getMappedStatement(ExecutionMapper.class.getName() + "." + method)
                .getBoundSql(parameters)
                .getSql();
    }

    private String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }
}
