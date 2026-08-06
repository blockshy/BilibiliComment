package com.hy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ConnectException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class DatabaseMigrationApplicationTests {

    private static final String UNREACHABLE_JDBC_URL =
            "jdbc:postgresql://127.0.0.1:1/unreachable?connectTimeout=1&socketTimeout=1";

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("bilicomment_entrypoint_test")
            .withUsername("bilicomment_migrator_test")
            .withPassword("fixture-password");

    @Test
    void migrationModeOverridesDisabledFlywayAppliesAllMigrationsAndIsIdempotentWithoutSecrets()
            throws SQLException {
        String[] arguments = migrationArguments(POSTGRES.getJdbcUrl());

        assertThatCode(() -> BiliBiliCommentApplication.main(arguments))
                .doesNotThrowAnyException();
        MigrationSnapshot firstRun = migrationSnapshot();
        assertThat(firstRun.appliedVersions())
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
        assertThat(firstRun.failedCount()).isZero();

        assertThatCode(() -> BiliBiliCommentApplication.main(arguments))
                .doesNotThrowAnyException();
        assertThat(migrationSnapshot()).isEqualTo(firstRun);
    }

    @Test
    void migrationModePropagatesConnectionFailuresToCaller() {
        assertThatThrownBy(() ->
                        BiliBiliCommentApplication.main(migrationArguments(UNREACHABLE_JDBC_URL)))
                .isInstanceOf(RuntimeException.class)
                .hasRootCauseInstanceOf(ConnectException.class);
    }

    private static String[] migrationArguments(String jdbcUrl) {
        return new String[] {
            "--app.mode=migrate",
            "--spring.datasource.url=" + jdbcUrl,
            "--spring.datasource.username=" + POSTGRES.getUsername(),
            "--spring.datasource.password=" + POSTGRES.getPassword(),
            "--spring.flyway.user=" + POSTGRES.getUsername(),
            "--spring.flyway.password=" + POSTGRES.getPassword(),
            "--spring.flyway.enabled=false",
            "--spring.main.banner-mode=off",
            "--logging.level.root=ERROR"
        };
    }

    private static MigrationSnapshot migrationSnapshot() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            List<String> versions = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery(
                    "SELECT version FROM app.flyway_schema_history "
                            + "WHERE success AND version IS NOT NULL ORDER BY installed_rank")) {
                while (rows.next()) {
                    versions.add(rows.getString("version"));
                }
            }

            try (ResultSet row = statement.executeQuery(
                    "SELECT count(*) AS total_count, "
                            + "count(*) FILTER (WHERE NOT success) AS failed_count "
                            + "FROM app.flyway_schema_history")) {
                assertThat(row.next()).isTrue();
                return new MigrationSnapshot(
                        List.copyOf(versions),
                        row.getLong("total_count"),
                        row.getLong("failed_count"));
            }
        }
    }

    private record MigrationSnapshot(
            List<String> appliedVersions, long historyCount, long failedCount) {}
}
