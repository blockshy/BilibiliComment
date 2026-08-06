package com.hy.bilicomment.infrastructure.persistence.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.hy.bilicomment.domain.task.SourceType;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskDiscoveryRelationMapperSqlTests {

    private Configuration configuration;

    @BeforeEach
    void setUp() {
        configuration = new Configuration();
        configuration.addMapper(TaskDiscoveryRelationMapper.class);
        configuration.addMapper(TaskMapper.class);
    }

    @Test
    void upsertNeverDowngradesManagedRelationAndRefreshesLastDiscovery() {
        String sql = normalize(boundSql(
                        TaskDiscoveryRelationMapper.class,
                        "upsert",
                        Map.of(
                                "parentTaskId", 7L,
                                "childTaskId", 8L,
                                "executionId", 31L,
                                "relationMode", "REFERENCED"))
                .getSql());

        assertThat(sql)
                .contains(
                        "ON CONFLICT (parent_task_id, child_task_id) DO UPDATE",
                        "app.task_discovery_relation.relation_mode = 'MANAGED'",
                        "last_discovered_at = clock_timestamp()",
                        "last_discovered_execution_id = EXCLUDED.last_discovered_execution_id")
                .doesNotContain("first_discovered_at =");
    }

    @Test
    void discoveredPageRendersAllSupportedFiltersAndStableOrdering() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("parentTaskId", 7L);
        parameters.put("query", "fixture");
        parameters.put("sourceType", SourceType.VIDEO);
        parameters.put("runtimeState", "RUNNING");
        parameters.put("health", "HEALTHY");
        parameters.put("limit", 51);
        parameters.put("cursorFirstDiscoveredAt", Instant.parse("2026-07-14T00:00:00Z"));
        parameters.put("cursorChildTaskId", 100L);

        String sql = normalize(boundSql(
                        TaskDiscoveryRelationMapper.class, "findPageByParent", parameters)
                .getSql());

        assertThat(sql).contains(
                "relation.parent_task_id = ?",
                "child.task_name ILIKE CONCAT('%', ?, '%')",
                "child.source_type = ?",
                "= ?",
                "(relation.first_discovered_at, relation.child_task_id) < (?, ?)",
                "ORDER BY relation.first_discovered_at DESC, relation.child_task_id DESC",
                "LIMIT ?")
                .doesNotContain("OFFSET");
    }

    @Test
    void preferredParentQueryHandlesEmptyIdsWithoutInvalidInClause() {
        String empty = normalize(boundSql(
                        TaskDiscoveryRelationMapper.class,
                        "findPreferredParentsByChildIds",
                        Map.of("childTaskIds", List.of()))
                .getSql());

        assertThat(empty).contains("WHERE 1 = 0").doesNotContain("IN ()");
    }

    @Test
    void primaryAndManagedScopesUseManagedRelationsOnly() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("query", null);
        parameters.put("desiredState", null);
        parameters.put("sourceType", null);
        parameters.put("collectionMode", null);
        parameters.put("runtimeState", null);
        parameters.put("health", null);
        parameters.put("scope", "PRIMARY");
        parameters.put("limit", 50);
        parameters.put("beforeTaskId", 100L);

        String sql = normalize(boundSql(TaskMapper.class, "findPageScoped", parameters).getSql());

        assertThat(sql).contains(
                "task_id < ?",
                "? = 'PRIMARY' AND NOT EXISTS",
                "? = 'MANAGED' AND EXISTS",
                "discovery_relation.relation_mode = 'MANAGED'",
                "ORDER BY task_id DESC LIMIT ?")
                .doesNotContain("updated_at DESC", "OFFSET");
    }

    @Test
    void summaryUsesLatestExecutionAndOnlyRecentInsertedComments() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("parentTaskIds", List.of(7L, 9L));
        parameters.put("since", Instant.parse("2026-07-13T00:00:00Z"));

        String sql = normalize(boundSql(
                        TaskDiscoveryRelationMapper.class,
                        "findSummariesByParentIds",
                        parameters)
                .getSql());

        assertThat(sql).contains(
                "LEFT JOIN LATERAL",
                "ORDER BY execution.execution_id DESC",
                "recent.finished_at >= ?",
                "relation.parent_task_id IN ( ? , ? )",
                "GROUP BY relation.parent_task_id");
    }

    @Test
    void commentMetricsAggregateTheCurrentPageInOneQuery() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("childTaskIds", List.of(8L, 9L));
        parameters.put("since", Instant.parse("2026-07-13T00:00:00Z"));

        String sql = normalize(boundSql(
                        TaskDiscoveryRelationMapper.class,
                        "findCommentMetricsByChildIds",
                        parameters)
                .getSql());

        assertThat(sql).contains(
                "sum(execution.comments_inserted)",
                "FILTER ( WHERE execution.finished_at >= ?)",
                "execution.task_id IN ( ? , ? )",
                "GROUP BY execution.task_id");
    }

    @Test
    void commentMetricsHandleAnEmptyPageWithoutAnInvalidInClause() {
        String sql = normalize(boundSql(
                        TaskDiscoveryRelationMapper.class,
                        "findCommentMetricsByChildIds",
                        Map.of(
                                "childTaskIds", List.of(),
                                "since", Instant.parse("2026-07-13T00:00:00Z")))
                .getSql());

        assertThat(sql).contains("WHERE 1 = 0").doesNotContain("IN ()");
    }

    private BoundSql boundSql(
            Class<?> mapperType,
            String method,
            Map<String, ?> parameters) {
        return configuration
                .getMappedStatement(mapperType.getName() + "." + method)
                .getBoundSql(parameters);
    }

    private String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }
}
