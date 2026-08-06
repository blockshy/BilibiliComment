package com.hy.bilicomment.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class PostgresqlMigrationSmokeTests {

    private static final String RUNTIME_ROLE = "bilicomment_runtime_fixture";
    private static final String RUNTIME_PASSWORD = "runtime-fixture-password";

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("bilicomment_migration_test")
            .withUsername("bilicomment_test")
            .withPassword("fixture-password");

    @BeforeAll
    static void migrateEmptyDatabase() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .schemas("app", "comment_data")
                .defaultSchema("app")
                .createSchemas(true)
                .load()
                .migrate();
    }

    @Test
    void appliesAllTenMigrationsToPostgresql17() throws SQLException {
        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            assertThat(singleString(statement, "SHOW server_version")).startsWith("17.");
            assertThat(singleLong(statement,
                            "SELECT count(*) FROM app.flyway_schema_history "
                                    + "WHERE success AND version IS NOT NULL"))
                    .isEqualTo(10L);
            assertThat(singleLong(statement,
                            "SELECT count(*) FROM app.flyway_schema_history WHERE NOT success"))
                    .isZero();
            assertThat(singleBoolean(statement,
                            "SELECT to_regclass('app.flyway_schema_history') IS NOT NULL "
                                    + "AND to_regclass('public.flyway_schema_history') IS NULL "
                                    + "AND to_regclass('comment_data.flyway_schema_history') IS NULL"))
                    .isTrue();
            assertThat(singleBoolean(statement,
                            "SELECT to_regnamespace('app') IS NOT NULL "
                                    + "AND to_regnamespace('comment_data') IS NOT NULL"))
                    .isTrue();
            assertThat(singleBoolean(statement,
                            "SELECT to_regclass('app.credential_profile') IS NOT NULL "
                                    + "AND to_regclass('app.task_definition') IS NOT NULL "
                                    + "AND to_regclass('app.task_execution') IS NOT NULL "
                                    + "AND to_regclass('app.task_discovery_relation') IS NOT NULL"))
                    .isTrue();
            assertThat(singleBoolean(statement,
                            "SELECT to_regprocedure('app.count_task_comments(bigint)') IS NOT NULL"))
                    .isTrue();
            assertThat(singleBoolean(statement,
                            "SELECT NOT enabled AND encrypted_cookie IS NULL "
                                    + "AND validation_status = 'UNCONFIGURED' "
                                    + "FROM app.credential_profile WHERE credential_key = 'default'"))
                    .isTrue();
        }
    }

    @Test
    void acceptsCreatorWatchRelationsToVideoAndDynamicCommentTasks() throws SQLException {
        try (Connection connection = connection()) {
            long parentTaskId = createCreatorTask(connection);
            long videoTaskId = createContentTask(connection, "VIDEO");
            long dynamicTaskId = createContentTask(connection, "DYNAMIC");

            insertDiscoveryRelation(connection, parentTaskId, videoTaskId, "MANAGED");
            insertDiscoveryRelation(connection, parentTaskId, dynamicTaskId, "REFERENCED");

            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT child_task_id, relation_mode "
                            + "FROM app.task_discovery_relation "
                            + "WHERE parent_task_id = ? ORDER BY child_task_id")) {
                statement.setLong(1, parentTaskId);
                try (ResultSet rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong("child_task_id")).isEqualTo(videoTaskId);
                    assertThat(rows.getString("relation_mode")).isEqualTo("MANAGED");
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong("child_task_id")).isEqualTo(dynamicTaskId);
                    assertThat(rows.getString("relation_mode")).isEqualTo("REFERENCED");
                    assertThat(rows.next()).isFalse();
                }
            }
        }
    }

    @Test
    void rejectsDiscoveryRelationsWithWrongParentOrChildTaskTypes() throws SQLException {
        try (Connection connection = connection()) {
            long creatorParentTaskId = createCreatorTask(connection);
            long creatorChildTaskId = createCreatorTask(connection);
            long contentParentTaskId = createContentTask(connection, "VIDEO");
            long contentChildTaskId = createContentTask(connection, "DYNAMIC");

            assertDiscoveryEndpointViolation(() -> insertDiscoveryRelation(
                    connection, contentParentTaskId, contentChildTaskId, "MANAGED"));
            assertDiscoveryEndpointViolation(() -> insertDiscoveryRelation(
                    connection, creatorParentTaskId, creatorChildTaskId, "MANAGED"));

            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT count(*) FROM app.task_discovery_relation "
                            + "WHERE parent_task_id IN (?, ?)")) {
                statement.setLong(1, contentParentTaskId);
                statement.setLong(2, creatorParentTaskId);
                try (ResultSet row = statement.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getLong(1)).isZero();
                }
            }
        }
    }

    @Test
    void rejectsSourceTypeChangesThatWouldInvalidateDiscoveryRelations() throws SQLException {
        try (Connection connection = connection()) {
            long parentTaskId = createCreatorTask(connection);
            long childTaskId = createContentTask(connection, "VIDEO");
            insertDiscoveryRelation(connection, parentTaskId, childTaskId, "MANAGED");

            assertDiscoveryEndpointViolation(() -> executeUpdate(connection,
                    "UPDATE app.task_definition SET source_type = 'CREATOR' WHERE task_id = "
                            + childTaskId));

            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT source_type FROM app.task_definition WHERE task_id = ?")) {
                statement.setLong(1, childTaskId);
                try (ResultSet row = statement.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString("source_type")).isEqualTo("VIDEO");
                }
            }
        }
    }

    @Test
    void deduplicatesRpidAndPagesByCtimeThenRpid() throws SQLException {
        try (Connection connection = connection()) {
            long taskId = createCommentTask(connection);
            String comments = """
                    [
                      {"rpid": 102, "mid": "fixture-102", "ctime": "2026-01-02T00:00:00Z"},
                      {"rpid": 101, "mid": "fixture-101", "ctime": "2026-01-02T00:00:00Z"},
                      {"rpid": 100, "mid": "fixture-100", "ctime": "2026-01-01T00:00:00Z"}
                    ]
                    """;

            assertInsertCounts(connection, taskId, comments, 3L, 3L, 0L);
            assertInsertCounts(
                    connection,
                    taskId,
                    "[{\"rpid\":102,\"mid\":\"fixture-102\",\"ctime\":\"2026-01-02T00:00:00Z\"}]",
                    1L,
                    0L,
                    1L);

            try (PreparedStatement firstPage = connection.prepareStatement(
                    "SELECT rpid FROM app.query_comments_page(?, NULL, NULL, 1)")) {
                firstPage.setLong(1, taskId);
                try (ResultSet rows = firstPage.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong("rpid")).isEqualTo(102L);
                    assertThat(rows.next()).isFalse();
                }
            }

            try (PreparedStatement nextPage = connection.prepareStatement(
                    "SELECT rpid FROM app.query_comments_page(?, CAST(? AS timestamptz), ?, 2)")) {
                nextPage.setLong(1, taskId);
                nextPage.setString(2, "2026-01-02T00:00:00Z");
                nextPage.setLong(3, 102L);
                try (ResultSet rows = nextPage.executeQuery()) {
                    assertThat(readRpids(rows)).containsExactly(101L, 100L);
                }
            }

            try (PreparedStatement count = connection.prepareStatement(
                    "SELECT app.count_task_comments(?)")) {
                count.setLong(1, taskId);
                try (ResultSet row = count.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getLong(1)).isEqualTo(3L);
                    assertThat(row.next()).isFalse();
                }
            }
        }
    }

    @Test
    void searchesAStableSnapshotEscapesLikeWildcardsAndBuildsPhysicalIndexes()
            throws SQLException {
        try (Connection connection = connection()) {
            long taskId = createCommentTask(connection);
            assertInsertCounts(connection, taskId, """
                    [
                      {"rpid":301,"mid":"31","uname":"alpha","content":"literal 100% coverage","ctime":"2026-01-03T00:00:00Z"},
                      {"rpid":302,"mid":"32","uname":"literal_visitor","content":"ordinary","ctime":"2026-01-02T00:00:00Z"},
                      {"rpid":303,"mid":"33","uname":"关键字用户","content":"ordinary","ctime":"2026-01-01T00:00:00Z"}
                    ]
                    """, 3L, 3L, 0L);
            long snapshot;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT app.capture_comment_snapshot(?)")) {
                statement.setLong(1, taskId);
                try (ResultSet row = statement.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    snapshot = row.getLong(1);
                }
            }
            assertInsertCounts(connection, taskId, """
                    [{"rpid":304,"mid":"34","uname":"迟到关键字用户","content":"100% late","ctime":"2026-01-04T00:00:00Z"}]
                    """, 1L, 1L, 0L);

            assertThat(searchRpids(connection, taskId, snapshot, "%", null))
                    .containsExactly(301L);
            assertThat(searchRpids(connection, taskId, snapshot, "关键字用户", null))
                    .containsExactly(303L);
            assertThat(searchRpids(connection, taskId, snapshot, null, "_"))
                    .containsExactly(302L);

            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT app.count_filtered_comments(
                        ?, ?, ?, NULL, NULL, false, NULL, NULL,
                        NULL, NULL, NULL, NULL, 'ALL')
                    """)) {
                statement.setLong(1, taskId);
                statement.setLong(2, snapshot);
                statement.setString(3, "关键字用户");
                try (ResultSet row = statement.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getLong(1)).isEqualTo(1L);
                }
            }

            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT count(*)
                      FROM pg_catalog.pg_indexes
                     WHERE schemaname = 'comment_data'
                       AND tablename = ?
                       AND (indexdef LIKE '%gin_trgm_ops%'
                            OR indexdef LIKE '%parent_rpid%'
                            OR indexdef LIKE '%mid%')
                    """)) {
                statement.setString(1, "comment_task_" + taskId);
                try (ResultSet row = statement.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getLong(1)).isGreaterThanOrEqualTo(4L);
                }
            }
        }
    }

    @Test
    void runtimeRoleUsesBusinessDmlAndControlledFunctionsWithoutCommentSchemaDdl()
            throws SQLException {
        provisionRuntimeRole();

        try (Connection runtime = runtimeConnection()) {
            assertThatThrownBy(() -> execute(runtime,
                            "CREATE TABLE comment_data.runtime_must_not_create_tables (id bigint)"))
                    .isInstanceOfSatisfying(SQLException.class,
                            exception -> assertThat(exception.getSQLState()).isEqualTo("42501"));

            long taskId = createCommentTask(runtime);
            assertThat(executeUpdate(runtime,
                            "UPDATE app.task_definition SET remarks = 'runtime fixture' "
                                    + "WHERE task_id = " + taskId))
                    .isEqualTo(1);

            assertInsertCounts(
                    runtime,
                    taskId,
                    "[{\"rpid\":201,\"mid\":\"runtime-user\","
                            + "\"ctime\":\"2026-07-14T00:00:00Z\"}]",
                    1L,
                    1L,
                    0L);
            try (PreparedStatement count = runtime.prepareStatement(
                    "SELECT app.count_task_comments(?)")) {
                count.setLong(1, taskId);
                try (ResultSet row = count.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getLong(1)).isEqualTo(1L);
                    assertThat(row.next()).isFalse();
                }
            }
            try (PreparedStatement page = runtime.prepareStatement(
                    "SELECT rpid FROM app.query_comments_page(?, NULL, NULL, 10)")) {
                page.setLong(1, taskId);
                try (ResultSet rows = page.executeQuery()) {
                    assertThat(readRpids(rows)).containsExactly(201L);
                }
            }
            long snapshot;
            try (PreparedStatement capture = runtime.prepareStatement(
                    "SELECT app.capture_comment_snapshot(?)")) {
                capture.setLong(1, taskId);
                try (ResultSet row = capture.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    snapshot = row.getLong(1);
                }
            }
            assertThat(searchRpids(runtime, taskId, snapshot, null, null))
                    .containsExactly(201L);

            assertThatThrownBy(() -> execute(runtime,
                            "INSERT INTO comment_data.comment_task_" + taskId
                                    + " (mid, ctime, rpid) "
                                    + "VALUES ('forbidden', clock_timestamp(), 202)"))
                    .isInstanceOfSatisfying(SQLException.class,
                            exception -> assertThat(exception.getSQLState()).isEqualTo("42501"));
        }

        try (Connection owner = connection(); Statement statement = owner.createStatement()) {
            assertThat(singleBoolean(statement,
                            "SELECT has_schema_privilege('" + RUNTIME_ROLE + "', 'app', 'USAGE') "
                                    + "AND NOT has_schema_privilege('" + RUNTIME_ROLE
                                    + "', 'app', 'CREATE') "
                                    + "AND NOT has_schema_privilege('" + RUNTIME_ROLE
                                    + "', 'comment_data', 'CREATE')"))
                    .isTrue();
            assertThat(singleBoolean(statement,
                            "SELECT has_table_privilege('" + RUNTIME_ROLE
                                    + "', 'app.task_definition', 'SELECT') "
                                    + "AND has_table_privilege('" + RUNTIME_ROLE
                                    + "', 'app.task_definition', 'INSERT') "
                                    + "AND has_table_privilege('" + RUNTIME_ROLE
                                    + "', 'app.task_definition', 'UPDATE') "
                                    + "AND has_table_privilege('" + RUNTIME_ROLE
                                    + "', 'app.task_discovery_relation', 'SELECT') "
                                    + "AND has_table_privilege('" + RUNTIME_ROLE
                                    + "', 'app.task_discovery_relation', 'INSERT') "
                                    + "AND has_table_privilege('" + RUNTIME_ROLE
                                    + "', 'app.task_discovery_relation', 'UPDATE') "
                                    + "AND has_table_privilege('" + RUNTIME_ROLE
                                    + "', 'app.comment_export_job', 'SELECT') "
                                    + "AND has_table_privilege('" + RUNTIME_ROLE
                                    + "', 'app.comment_export_job', 'INSERT') "
                                    + "AND has_table_privilege('" + RUNTIME_ROLE
                                    + "', 'app.comment_export_job', 'UPDATE') "
                                    + "AND has_table_privilege('" + RUNTIME_ROLE
                                    + "', 'app.comment_export_job', 'DELETE') "
                                    + "AND NOT has_table_privilege('" + RUNTIME_ROLE
                                    + "', 'app.flyway_schema_history', 'SELECT') "
                                    + "AND NOT has_table_privilege('" + RUNTIME_ROLE
                                    + "', 'comment_data.comment_table_template', 'INSERT')"))
                    .isTrue();
            assertThat(singleBoolean(statement,
                            "SELECT has_function_privilege('" + RUNTIME_ROLE
                                    + "', 'app.insert_comments(bigint,jsonb)', 'EXECUTE') "
                                    + "AND has_function_privilege('" + RUNTIME_ROLE
                                    + "', 'app.query_comments_page(bigint,timestamptz,bigint,integer)',"
                                    + " 'EXECUTE') "
                                    + "AND has_function_privilege('" + RUNTIME_ROLE
                                    + "', 'app.count_task_comments(bigint)', 'EXECUTE') "
                                    + "AND has_function_privilege('" + RUNTIME_ROLE
                                    + "', 'app.capture_comment_snapshot(bigint)', 'EXECUTE') "
                                    + "AND has_function_privilege('" + RUNTIME_ROLE
                                    + "', 'app.search_comments_page(bigint,bigint,text,text,text,boolean,smallint,smallint,timestamptz,timestamptz,bigint,bigint,character varying,character varying,timestamptz,bigint,integer)', 'EXECUTE') "
                                    + "AND has_function_privilege('" + RUNTIME_ROLE
                                    + "', 'app.count_filtered_comments(bigint,bigint,text,text,text,boolean,smallint,smallint,timestamptz,timestamptz,bigint,bigint,character varying)', 'EXECUTE') "
                                    + "AND NOT has_function_privilege('" + RUNTIME_ROLE
                                    + "', 'app.ensure_comment_table(bigint)', 'EXECUTE')"))
                    .isTrue();
        }
    }

    @Test
    void permitsOnlyOneOfThirtyTwoConcurrentActiveExecutionsPerTask() throws Exception {
        final long taskId;
        try (Connection connection = connection()) {
            taskId = createCommentTask(connection);
        }
        int contenderCount = 32;
        CountDownLatch ready = new CountDownLatch(contenderCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> outcomes = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int contender = 0; contender < contenderCount; contender++) {
                outcomes.add(executor.submit(() -> {
                    try (Connection candidate = connection()) {
                        ready.countDown();
                        if (!start.await(20, TimeUnit.SECONDS)) {
                            return "START_TIMEOUT";
                        }
                        try {
                            insertExecution(candidate, taskId, "QUEUED");
                            return "SUCCESS";
                        } catch (SQLException exception) {
                            return exception.getSQLState();
                        }
                    }
                }));
            }
            assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> outcome : outcomes) {
                results.add(outcome.get(20, TimeUnit.SECONDS));
            }
            assertThat(results).filteredOn("SUCCESS"::equals).hasSize(1);
            assertThat(results).filteredOn(result -> !"SUCCESS".equals(result))
                    .containsOnly("23505");
        }
        try (Connection connection = connection(); PreparedStatement count = connection.prepareStatement(
                "SELECT count(*) FROM app.task_execution "
                        + "WHERE task_id = ? AND status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT')")) {
            count.setLong(1, taskId);
            try (ResultSet row = count.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getLong(1)).isEqualTo(1L);
            }
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static Connection runtimeConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), RUNTIME_ROLE, RUNTIME_PASSWORD);
    }

    private static void provisionRuntimeRole() throws SQLException {
        try (Connection owner = connection(); Statement statement = owner.createStatement()) {
            statement.execute("CREATE ROLE " + RUNTIME_ROLE
                    + " LOGIN PASSWORD '" + RUNTIME_PASSWORD + "'");
            statement.execute("GRANT CONNECT ON DATABASE bilicomment_migration_test TO "
                    + RUNTIME_ROLE);
            statement.execute("GRANT USAGE ON SCHEMA app TO " + RUNTIME_ROLE);
            statement.execute("GRANT SELECT, UPDATE ON app.credential_profile TO " + RUNTIME_ROLE);
            statement.execute("GRANT SELECT, INSERT, UPDATE ON app.task_definition TO "
                    + RUNTIME_ROLE);
            statement.execute("GRANT SELECT, INSERT, UPDATE ON app.task_execution TO "
                    + RUNTIME_ROLE);
            statement.execute("GRANT SELECT, INSERT, UPDATE ON app.task_discovery_relation TO "
                    + RUNTIME_ROLE);
            statement.execute("GRANT SELECT, INSERT ON app.task_event TO " + RUNTIME_ROLE);
            statement.execute("GRANT INSERT ON app.operation_audit TO " + RUNTIME_ROLE);
            statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON app.idempotency_request TO "
                    + RUNTIME_ROLE);
            statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON app.comment_export_job TO "
                    + RUNTIME_ROLE);
            statement.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA app TO "
                    + RUNTIME_ROLE);
            statement.execute("GRANT EXECUTE ON FUNCTION app.insert_comments(bigint, jsonb) TO "
                    + RUNTIME_ROLE);
            statement.execute("GRANT EXECUTE ON FUNCTION "
                    + "app.query_comments_page(bigint, timestamptz, bigint, integer) TO "
                    + RUNTIME_ROLE);
            statement.execute("GRANT EXECUTE ON FUNCTION app.count_task_comments(bigint) TO "
                    + RUNTIME_ROLE);
            statement.execute("GRANT EXECUTE ON FUNCTION app.capture_comment_snapshot(bigint) TO "
                    + RUNTIME_ROLE);
            statement.execute("GRANT EXECUTE ON FUNCTION app.search_comments_page("
                    + "bigint,bigint,text,text,text,boolean,smallint,smallint,timestamptz,"
                    + "timestamptz,bigint,bigint,varchar,varchar,timestamptz,bigint,integer) TO "
                    + RUNTIME_ROLE);
            statement.execute("GRANT EXECUTE ON FUNCTION app.count_filtered_comments("
                    + "bigint,bigint,text,text,text,boolean,smallint,smallint,timestamptz,"
                    + "timestamptz,bigint,bigint,varchar) TO " + RUNTIME_ROLE);
        }
    }

    private static long createCommentTask(Connection connection) throws SQLException {
        return createContentTask(connection, "VIDEO");
    }

    private static long createCreatorTask(Connection connection) throws SQLException {
        return createTask(connection, "CREATOR_WATCH", "CREATOR");
    }

    private static long createContentTask(Connection connection, String sourceType)
            throws SQLException {
        return createTask(connection, "CONTENT_COMMENTS", sourceType);
    }

    private static long createTask(Connection connection, String taskType, String sourceType)
            throws SQLException {
        String sql = """
                INSERT INTO app.task_definition (
                    task_name, task_type, source_type, source_id,
                    collection_mode, desired_state, schedule_type
                ) VALUES (?, ?, ?, ?, 'FOLLOW_ONLY', 'ACTIVE', 'ADAPTIVE')
                RETURNING task_id
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            String fixtureId = UUID.randomUUID().toString();
            statement.setString(1, "Migration fixture " + taskType + " " + fixtureId);
            statement.setString(2, taskType);
            statement.setString(3, sourceType);
            statement.setString(4, sourceType + "-fixture-" + fixtureId);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong("task_id");
            }
        }
    }

    private static void insertDiscoveryRelation(
            Connection connection,
            long parentTaskId,
            long childTaskId,
            String relationMode) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO app.task_discovery_relation (
                    parent_task_id, child_task_id, relation_mode
                ) VALUES (?, ?, ?)
                """)) {
            statement.setLong(1, parentTaskId);
            statement.setLong(2, childTaskId);
            statement.setString(3, relationMode);
            statement.executeUpdate();
        }
    }

    private static void assertDiscoveryEndpointViolation(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation)
                .isInstanceOfSatisfying(PSQLException.class, exception -> {
                    assertThat(exception.getSQLState()).isEqualTo("23514");
                    assertThat(exception.getServerErrorMessage()).isNotNull();
                    assertThat(exception.getServerErrorMessage().getConstraint())
                            .isEqualTo("ck_task_discovery_relation_endpoint_types");
                    assertThat(exception.getServerErrorMessage().getMessage())
                            .startsWith("invalid task discovery relation");
                });
    }

    private static void assertInsertCounts(
            Connection connection,
            long taskId,
            String comments,
            long received,
            long inserted,
            long duplicate) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT received_count, inserted_count, duplicate_count "
                        + "FROM app.insert_comments(?, CAST(? AS jsonb))")) {
            statement.setLong(1, taskId);
            statement.setString(2, comments);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getLong("received_count")).isEqualTo(received);
                assertThat(row.getLong("inserted_count")).isEqualTo(inserted);
                assertThat(row.getLong("duplicate_count")).isEqualTo(duplicate);
                assertThat(row.next()).isFalse();
            }
        }
    }

    private static void insertExecution(Connection connection, long taskId, String status)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO app.task_execution ("
                        + "task_id, trigger_type, status, lease_owner, lease_until) "
                        + "VALUES (?, 'MANUAL', ?, "
                        + "CASE WHEN ? = 'RUNNING' THEN 'migration-test-worker' END, "
                        + "CASE WHEN ? = 'RUNNING' THEN clock_timestamp() + interval '1 minute' END)")) {
            statement.setLong(1, taskId);
            statement.setString(2, status);
            statement.setString(3, status);
            statement.setString(4, status);
            statement.executeUpdate();
        }
    }

    private static List<Long> searchRpids(
            Connection connection,
            long taskId,
            long snapshot,
            String keyword,
            String uname) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT rpid
                  FROM app.search_comments_page(
                      ?, ?, ?, NULL, ?, false, NULL, NULL,
                      NULL, NULL, NULL, NULL, 'ALL', 'CTIME_DESC', NULL, NULL, 100)
                """)) {
            statement.setLong(1, taskId);
            statement.setLong(2, snapshot);
            statement.setString(3, keyword);
            statement.setString(4, uname);
            try (ResultSet rows = statement.executeQuery()) {
                return readRpids(rows);
            }
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static int executeUpdate(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }

    private static List<Long> readRpids(ResultSet rows) throws SQLException {
        java.util.ArrayList<Long> rpids = new java.util.ArrayList<>();
        while (rows.next()) {
            rpids.add(rows.getLong("rpid"));
        }
        return List.copyOf(rpids);
    }

    private static String singleString(Statement statement, String sql) throws SQLException {
        try (ResultSet row = statement.executeQuery(sql)) {
            assertThat(row.next()).isTrue();
            return row.getString(1);
        }
    }

    private static long singleLong(Statement statement, String sql) throws SQLException {
        try (ResultSet row = statement.executeQuery(sql)) {
            assertThat(row.next()).isTrue();
            return row.getLong(1);
        }
    }

    private static boolean singleBoolean(Statement statement, String sql) throws SQLException {
        try (ResultSet row = statement.executeQuery(sql)) {
            assertThat(row.next()).isTrue();
            return row.getBoolean(1);
        }
    }
}
