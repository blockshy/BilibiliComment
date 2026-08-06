package com.hy.bilicomment.infrastructure.persistence.mapper;

import com.hy.bilicomment.domain.task.TaskDiscoveryRelationMode;
import com.hy.bilicomment.domain.task.SourceType;
import com.hy.bilicomment.infrastructure.persistence.entity.TaskDiscoveryParentRow;
import com.hy.bilicomment.infrastructure.persistence.entity.TaskDiscoveryRelationRow;
import com.hy.bilicomment.infrastructure.persistence.entity.TaskDiscoverySummaryRow;
import com.hy.bilicomment.infrastructure.persistence.entity.TaskExecutionCommentMetricsRow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface TaskDiscoveryRelationMapper {

    String COLUMNS = """
            parent_task_id, child_task_id, relation_mode,
            first_discovered_at, last_discovered_at,
            first_discovered_execution_id, last_discovered_execution_id
            """;

    @Select("""
            INSERT INTO app.task_discovery_relation (
                parent_task_id,
                child_task_id,
                relation_mode,
                first_discovered_execution_id,
                last_discovered_execution_id
            ) VALUES (
                #{parentTaskId},
                #{childTaskId},
                #{relationMode},
                #{executionId},
                #{executionId}
            )
            ON CONFLICT (parent_task_id, child_task_id) DO UPDATE
               SET relation_mode = CASE
                       WHEN app.task_discovery_relation.relation_mode = 'MANAGED'
                           OR EXCLUDED.relation_mode = 'MANAGED'
                       THEN 'MANAGED'
                       ELSE 'REFERENCED'
                   END,
                   last_discovered_at = clock_timestamp(),
                   last_discovered_execution_id = EXCLUDED.last_discovered_execution_id
            RETURNING
            """ + COLUMNS)
    Optional<TaskDiscoveryRelationRow> upsert(
            @Param("parentTaskId") long parentTaskId,
            @Param("childTaskId") long childTaskId,
            @Param("executionId") long executionId,
            @Param("relationMode") TaskDiscoveryRelationMode relationMode);

    @Select("""
            SELECT relation_mode
              FROM app.task_discovery_relation
             WHERE child_task_id = #{childTaskId}
             ORDER BY CASE relation_mode WHEN 'MANAGED' THEN 0 ELSE 1 END,
                      first_discovered_at,
                      parent_task_id
             LIMIT 1
            """)
    Optional<TaskDiscoveryRelationMode> findModeForChild(long childTaskId);

    @Select("""
            SELECT EXISTS (
                SELECT 1
                  FROM app.task_discovery_relation
                 WHERE child_task_id = #{childTaskId}
                   AND relation_mode = 'MANAGED'
            )
            """)
    boolean existsManagedByChild(long childTaskId);

    @Select("""
            SELECT child.task_id
              FROM app.task_definition child
             WHERE child.desired_state = 'ACTIVE'
               AND EXISTS (
                   SELECT 1
                     FROM app.task_discovery_relation relation
                    WHERE relation.child_task_id = child.task_id
                      AND relation.relation_mode = 'MANAGED'
               )
               AND NOT EXISTS (
                   SELECT 1
                     FROM app.task_execution execution
                    WHERE execution.task_id = child.task_id
               )
             ORDER BY child.task_id
             LIMIT #{limit}
            """)
    List<Long> findManagedChildrenWithoutExecution(@Param("limit") int limit);

    @Select({
        "<script>",
        "SELECT DISTINCT ON (relation.child_task_id)",
        "       relation.child_task_id,",
        "       parent.task_id AS parent_task_id,",
        "       parent.task_name AS parent_task_name,",
        "       relation.relation_mode",
        "  FROM app.task_discovery_relation relation",
        "  JOIN app.task_definition parent ON parent.task_id = relation.parent_task_id",
        "<choose>",
        "<when test='childTaskIds != null and !childTaskIds.isEmpty()'>",
        " WHERE relation.child_task_id IN",
        "<foreach collection='childTaskIds' item='taskId' open='(' separator=',' close=')'>",
        "#{taskId}",
        "</foreach>",
        "</when>",
        "<otherwise> WHERE 1 = 0</otherwise>",
        "</choose>",
        " ORDER BY relation.child_task_id,",
        "          CASE relation.relation_mode WHEN 'MANAGED' THEN 0 ELSE 1 END,",
        "          relation.first_discovered_at, relation.parent_task_id",
        "</script>"
    })
    List<TaskDiscoveryParentRow> findPreferredParentsByChildIds(
            @Param("childTaskIds") List<Long> childTaskIds);

    @Select({
        "<script>",
        "SELECT relation.parent_task_id,",
        "       count(*) AS total,",
        "       count(*) FILTER (WHERE latest.execution_id IS NULL) AS never_started,",
        "       count(*) FILTER (WHERE latest.status IN ('QUEUED', 'RUNNING')) AS queued_or_running,",
        "       count(*) FILTER (WHERE latest.status = 'RETRY_WAIT') AS retry_waiting,",
        "       count(*) FILTER (WHERE latest.status = 'FAILED') AS errors,",
        "       COALESCE(sum((",
        "           SELECT COALESCE(sum(recent.comments_inserted), 0)",
        "             FROM app.task_execution recent",
        "            WHERE recent.task_id = relation.child_task_id",
        "              AND recent.finished_at &gt;= #{since}",
        "       )), 0) AS comments_inserted24h,",
        "       max(relation.last_discovered_at) AS newest_discovered_at",
        "  FROM app.task_discovery_relation relation",
        "  LEFT JOIN LATERAL (",
        "      SELECT execution.execution_id, execution.status",
        "        FROM app.task_execution execution",
        "       WHERE execution.task_id = relation.child_task_id",
        "       ORDER BY execution.execution_id DESC",
        "       LIMIT 1",
        "  ) latest ON true",
        "<choose>",
        "<when test='parentTaskIds != null and !parentTaskIds.isEmpty()'>",
        " WHERE relation.parent_task_id IN",
        "<foreach collection='parentTaskIds' item='taskId' open='(' separator=',' close=')'>",
        "#{taskId}",
        "</foreach>",
        "</when>",
        "<otherwise> WHERE 1 = 0</otherwise>",
        "</choose>",
        " GROUP BY relation.parent_task_id",
        "</script>"
    })
    List<TaskDiscoverySummaryRow> findSummariesByParentIds(
            @Param("parentTaskIds") List<Long> parentTaskIds,
            @Param("since") Instant since);

    @Select({
        "<script>",
        "SELECT execution.task_id,",
        "       COALESCE(sum(execution.comments_inserted), 0) AS comments_total,",
        "       COALESCE(sum(execution.comments_inserted) FILTER (",
        "           WHERE execution.finished_at &gt;= #{since}), 0) AS comments_inserted24h",
        "  FROM app.task_execution execution",
        "<choose>",
        "<when test='childTaskIds != null and !childTaskIds.isEmpty()'>",
        " WHERE execution.task_id IN",
        "<foreach collection='childTaskIds' item='taskId' open='(' separator=',' close=')'>",
        "#{taskId}",
        "</foreach>",
        "</when>",
        "<otherwise> WHERE 1 = 0</otherwise>",
        "</choose>",
        " GROUP BY execution.task_id",
        "</script>"
    })
    List<TaskExecutionCommentMetricsRow> findCommentMetricsByChildIds(
            @Param("childTaskIds") List<Long> childTaskIds,
            @Param("since") Instant since);

    @Select({
        "<script>",
        "SELECT relation.parent_task_id, relation.child_task_id, relation.relation_mode,",
        "       relation.first_discovered_at, relation.last_discovered_at,",
        "       relation.first_discovered_execution_id, relation.last_discovered_execution_id",
        "  FROM app.task_discovery_relation relation",
        "  JOIN app.task_definition child ON child.task_id = relation.child_task_id",
        "  LEFT JOIN LATERAL (",
        "      SELECT execution.status",
        "        FROM app.task_execution execution",
        "       WHERE execution.task_id = child.task_id",
        "       ORDER BY execution.execution_id DESC",
        "       LIMIT 1",
        "  ) latest ON true",
        " WHERE relation.parent_task_id = #{parentTaskId}",
        "<if test='query != null and query != \"\"'>",
        "   AND (child.task_name ILIKE CONCAT('%', #{query}, '%')",
        "        OR child.source_id ILIKE CONCAT('%', #{query}, '%'))",
        "</if>",
        "<if test='sourceType != null'> AND child.source_type = #{sourceType}</if>",
        "<if test='runtimeState != null and runtimeState != \"\"'>",
        "   AND COALESCE(CASE latest.status",
        "       WHEN 'QUEUED' THEN 'QUEUED' WHEN 'RUNNING' THEN 'RUNNING'",
        "       WHEN 'RETRY_WAIT' THEN 'RETRY_WAIT' WHEN 'SUCCEEDED' THEN 'COMPLETED'",
        "       WHEN 'FAILED' THEN 'ERROR' ELSE 'IDLE' END, 'IDLE') = #{runtimeState}",
        "</if>",
        "<if test='health != null and health != \"\"'>",
        "   AND COALESCE(CASE latest.status",
        "       WHEN 'FAILED' THEN 'ERROR' WHEN 'RETRY_WAIT' THEN 'DEGRADED'",
        "       WHEN 'SUCCEEDED' THEN 'HEALTHY' WHEN 'RUNNING' THEN 'HEALTHY'",
        "       WHEN 'QUEUED' THEN 'HEALTHY' ELSE 'UNKNOWN' END, 'UNKNOWN') = #{health}",
        "</if>",
        "<if test='cursorFirstDiscoveredAt != null and cursorChildTaskId != null'>",
        "   AND (relation.first_discovered_at, relation.child_task_id)",
        "       &lt; (#{cursorFirstDiscoveredAt}, #{cursorChildTaskId})",
        "</if>",
        " ORDER BY relation.first_discovered_at DESC, relation.child_task_id DESC",
        " LIMIT #{limit}",
        "</script>"
    })
    List<TaskDiscoveryRelationRow> findPageByParent(
            @Param("parentTaskId") long parentTaskId,
            @Param("query") String query,
            @Param("sourceType") SourceType sourceType,
            @Param("runtimeState") String runtimeState,
            @Param("health") String health,
            @Param("limit") int limit,
            @Param("cursorFirstDiscoveredAt") Instant cursorFirstDiscoveredAt,
            @Param("cursorChildTaskId") Long cursorChildTaskId);

    @Select({
        "<script>",
        "SELECT count(*)",
        "  FROM app.task_discovery_relation relation",
        "  JOIN app.task_definition child ON child.task_id = relation.child_task_id",
        "  LEFT JOIN LATERAL (",
        "      SELECT execution.status",
        "        FROM app.task_execution execution",
        "       WHERE execution.task_id = child.task_id",
        "       ORDER BY execution.execution_id DESC",
        "       LIMIT 1",
        "  ) latest ON true",
        " WHERE relation.parent_task_id = #{parentTaskId}",
        "<if test='query != null and query != \"\"'>",
        "   AND (child.task_name ILIKE CONCAT('%', #{query}, '%')",
        "        OR child.source_id ILIKE CONCAT('%', #{query}, '%'))",
        "</if>",
        "<if test='sourceType != null'> AND child.source_type = #{sourceType}</if>",
        "<if test='runtimeState != null and runtimeState != \"\"'>",
        "   AND COALESCE(CASE latest.status",
        "       WHEN 'QUEUED' THEN 'QUEUED' WHEN 'RUNNING' THEN 'RUNNING'",
        "       WHEN 'RETRY_WAIT' THEN 'RETRY_WAIT' WHEN 'SUCCEEDED' THEN 'COMPLETED'",
        "       WHEN 'FAILED' THEN 'ERROR' ELSE 'IDLE' END, 'IDLE') = #{runtimeState}",
        "</if>",
        "<if test='health != null and health != \"\"'>",
        "   AND COALESCE(CASE latest.status",
        "       WHEN 'FAILED' THEN 'ERROR' WHEN 'RETRY_WAIT' THEN 'DEGRADED'",
        "       WHEN 'SUCCEEDED' THEN 'HEALTHY' WHEN 'RUNNING' THEN 'HEALTHY'",
        "       WHEN 'QUEUED' THEN 'HEALTHY' ELSE 'UNKNOWN' END, 'UNKNOWN') = #{health}",
        "</if>",
        "</script>"
    })
    long countByParent(
            @Param("parentTaskId") long parentTaskId,
            @Param("query") String query,
            @Param("sourceType") SourceType sourceType,
            @Param("runtimeState") String runtimeState,
            @Param("health") String health);
}
