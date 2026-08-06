package com.hy.bilicomment.infrastructure.persistence.mapper;

import com.hy.bilicomment.domain.task.CollectionMode;
import com.hy.bilicomment.domain.task.DesiredState;
import com.hy.bilicomment.domain.task.TaskListScope;
import com.hy.bilicomment.domain.task.ScheduleType;
import com.hy.bilicomment.domain.task.SourceType;
import com.hy.bilicomment.domain.task.TaskType;
import com.hy.bilicomment.infrastructure.persistence.entity.TaskRow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface TaskMapper {

    String COLUMNS = """
            task_id, task_name, task_type, source_type, source_id, collection_mode,
            desired_state, schedule_type, cron_expression, credential_profile_id,
            source_metadata::text AS source_metadata_json,
            schedule_config::text AS schedule_config_json, remarks,
            last_execution_at, next_execution_at, version, created_at, updated_at
            """;

    String LATEST_RUNTIME_STATE = """
            COALESCE((
                SELECT CASE execution.status
                    WHEN 'QUEUED' THEN 'QUEUED'
                    WHEN 'RUNNING' THEN 'RUNNING'
                    WHEN 'RETRY_WAIT' THEN 'RETRY_WAIT'
                    WHEN 'SUCCEEDED' THEN 'COMPLETED'
                    WHEN 'FAILED' THEN 'ERROR'
                    ELSE 'IDLE'
                END
                  FROM app.task_execution execution
                 WHERE execution.task_id = task_definition.task_id
                 ORDER BY execution.execution_id DESC
                 LIMIT 1
            ), 'IDLE')
            """;

    String LATEST_HEALTH = """
            COALESCE((
                SELECT CASE execution.status
                    WHEN 'FAILED' THEN 'ERROR'
                    WHEN 'RETRY_WAIT' THEN 'DEGRADED'
                    WHEN 'SUCCEEDED' THEN 'HEALTHY'
                    WHEN 'RUNNING' THEN 'HEALTHY'
                    WHEN 'QUEUED' THEN 'HEALTHY'
                    ELSE 'UNKNOWN'
                END
                  FROM app.task_execution execution
                 WHERE execution.task_id = task_definition.task_id
                 ORDER BY execution.execution_id DESC
                 LIMIT 1
            ), 'UNKNOWN')
            """;

    @Select("SELECT " + COLUMNS + " FROM app.task_definition WHERE task_id = #{taskId}")
    Optional<TaskRow> findById(long taskId);

    @Select("SELECT " + COLUMNS + " FROM app.task_definition WHERE task_id = #{taskId} FOR UPDATE")
    Optional<TaskRow> findByIdForUpdate(long taskId);

    @Select({
        "<script>",
        "SELECT " + COLUMNS + " FROM app.task_definition",
        "<choose>",
        "<when test='taskIds != null and !taskIds.isEmpty()'>",
        " WHERE task_id IN",
        "<foreach collection='taskIds' item='taskId' open='(' separator=',' close=')'>",
        "#{taskId}",
        "</foreach>",
        "</when>",
        "<otherwise> WHERE 1 = 0</otherwise>",
        "</choose>",
        "</script>"
    })
    List<TaskRow> findByIds(@Param("taskIds") List<Long> taskIds);

    @Select("SELECT " + COLUMNS + " FROM app.task_definition"
            + " WHERE task_type = #{taskType}"
            + " AND source_type = #{sourceType}"
            + " AND source_id = #{sourceId}"
            + " AND collection_mode = #{collectionMode}")
    Optional<TaskRow> findBySource(
            @Param("taskType") TaskType taskType,
            @Param("sourceType") SourceType sourceType,
            @Param("sourceId") String sourceId,
            @Param("collectionMode") CollectionMode collectionMode);

    @Select({
        "<script>",
        "SELECT " + COLUMNS + " FROM app.task_definition task_definition",
        "<where>",
        "<if test='query != null and query != \"\"'>",
        "  (task_name ILIKE CONCAT('%', #{query}, '%') OR source_id ILIKE CONCAT('%', #{query}, '%'))",
        "</if>",
        "<if test='desiredState != null'> AND desired_state = #{desiredState}</if>",
        "<if test='sourceType != null'> AND source_type = #{sourceType}</if>",
        "<if test='collectionMode != null'> AND collection_mode = #{collectionMode}</if>",
        "<if test='runtimeState != null and runtimeState != \"\"'> AND "
                + LATEST_RUNTIME_STATE + " = #{runtimeState}</if>",
        "<if test='health != null and health != \"\"'> AND "
                + LATEST_HEALTH + " = #{health}</if>",
        "<if test='beforeTaskId != null'> AND task_id &lt; #{beforeTaskId}</if>",
        "AND (#{scope} = 'ALL'",
        "  OR (#{scope} = 'PRIMARY' AND NOT EXISTS (",
        "      SELECT 1 FROM app.task_discovery_relation discovery_relation",
        "       WHERE discovery_relation.child_task_id = task_definition.task_id",
        "         AND discovery_relation.relation_mode = 'MANAGED'",
        "  ))",
        "  OR (#{scope} = 'MANAGED' AND EXISTS (",
        "      SELECT 1 FROM app.task_discovery_relation discovery_relation",
        "       WHERE discovery_relation.child_task_id = task_definition.task_id",
        "         AND discovery_relation.relation_mode = 'MANAGED'",
        "  ))",
        ")",
        "</where>",
        "ORDER BY task_id DESC LIMIT #{limit}",
        "</script>"
    })
    List<TaskRow> findPageScoped(
            @Param("query") String query,
            @Param("desiredState") DesiredState desiredState,
            @Param("sourceType") SourceType sourceType,
            @Param("collectionMode") CollectionMode collectionMode,
            @Param("runtimeState") String runtimeState,
            @Param("health") String health,
            @Param("scope") String scope,
            @Param("limit") int limit,
            @Param("beforeTaskId") Long beforeTaskId);

    default List<TaskRow> findPage(
            String query,
            DesiredState desiredState,
            SourceType sourceType,
            CollectionMode collectionMode,
            String runtimeState,
            String health,
            int limit,
            Long beforeTaskId) {
        return findPageScoped(
                query,
                desiredState,
                sourceType,
                collectionMode,
                runtimeState,
                health,
                TaskListScope.ALL.name(),
                limit,
                beforeTaskId);
    }

    @Select({
        "<script>",
        "SELECT count(*) FROM app.task_definition task_definition",
        "<where>",
        "<if test='query != null and query != \"\"'>",
        "  (task_name ILIKE CONCAT('%', #{query}, '%') OR source_id ILIKE CONCAT('%', #{query}, '%'))",
        "</if>",
        "<if test='desiredState != null'> AND desired_state = #{desiredState}</if>",
        "<if test='sourceType != null'> AND source_type = #{sourceType}</if>",
        "<if test='collectionMode != null'> AND collection_mode = #{collectionMode}</if>",
        "<if test='runtimeState != null and runtimeState != \"\"'> AND "
                + LATEST_RUNTIME_STATE + " = #{runtimeState}</if>",
        "<if test='health != null and health != \"\"'> AND "
                + LATEST_HEALTH + " = #{health}</if>",
        "AND (#{scope} = 'ALL'",
        "  OR (#{scope} = 'PRIMARY' AND NOT EXISTS (",
        "      SELECT 1 FROM app.task_discovery_relation discovery_relation",
        "       WHERE discovery_relation.child_task_id = task_definition.task_id",
        "         AND discovery_relation.relation_mode = 'MANAGED'",
        "  ))",
        "  OR (#{scope} = 'MANAGED' AND EXISTS (",
        "      SELECT 1 FROM app.task_discovery_relation discovery_relation",
        "       WHERE discovery_relation.child_task_id = task_definition.task_id",
        "         AND discovery_relation.relation_mode = 'MANAGED'",
        "  ))",
        ")",
        "</where>",
        "</script>"
    })
    long countFilteredScoped(
            @Param("query") String query,
            @Param("desiredState") DesiredState desiredState,
            @Param("sourceType") SourceType sourceType,
            @Param("collectionMode") CollectionMode collectionMode,
            @Param("runtimeState") String runtimeState,
            @Param("health") String health,
            @Param("scope") String scope);

    default long countFiltered(
            String query,
            DesiredState desiredState,
            SourceType sourceType,
            CollectionMode collectionMode,
            String runtimeState,
            String health) {
        return countFilteredScoped(
                query,
                desiredState,
                sourceType,
                collectionMode,
                runtimeState,
                health,
                TaskListScope.ALL.name());
    }

    @Select("SELECT " + COLUMNS + " FROM app.task_definition task_definition"
            + " WHERE desired_state = 'ACTIVE'"
            + " AND schedule_type != 'MANUAL'"
            + " AND NOT EXISTS (SELECT 1 FROM app.task_execution active_execution"
            + "     WHERE active_execution.task_id = task_definition.task_id"
            + "       AND active_execution.status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT'))"
            + " ORDER BY task_id")
    List<TaskRow> findSchedulable();

    @Select("INSERT INTO app.task_definition ("
            + "task_name, task_type, source_type, source_id, collection_mode,"
            + "desired_state, schedule_type, cron_expression, credential_profile_id,"
            + "source_metadata, schedule_config, remarks, next_execution_at) VALUES ("
            + "#{taskName}, #{taskType}, #{sourceType}, #{sourceId}, #{collectionMode},"
            + "#{desiredState}, #{scheduleType}, #{cronExpression}, #{credentialProfileId},"
            + "CAST(#{sourceMetadataJson} AS jsonb), CAST(#{scheduleConfigJson} AS jsonb),"
            + "#{remarks}, #{nextExecutionAt})"
            + " ON CONFLICT (task_type, source_type, source_id, collection_mode) DO NOTHING"
            + " RETURNING " + COLUMNS)
    Optional<TaskRow> insert(
            @Param("taskName") String taskName,
            @Param("taskType") TaskType taskType,
            @Param("sourceType") SourceType sourceType,
            @Param("sourceId") String sourceId,
            @Param("collectionMode") CollectionMode collectionMode,
            @Param("desiredState") DesiredState desiredState,
            @Param("scheduleType") ScheduleType scheduleType,
            @Param("cronExpression") String cronExpression,
            @Param("credentialProfileId") Long credentialProfileId,
            @Param("sourceMetadataJson") String sourceMetadataJson,
            @Param("scheduleConfigJson") String scheduleConfigJson,
            @Param("remarks") String remarks,
            @Param("nextExecutionAt") Instant nextExecutionAt);

    @Update("""
            UPDATE app.task_definition
               SET desired_state = #{desiredState},
                   next_execution_at = #{nextExecutionAt},
                   version = version + 1
             WHERE task_id = #{taskId}
               AND version = #{expectedVersion}
            """)
    int updateDesiredState(
            @Param("taskId") long taskId,
            @Param("expectedVersion") long expectedVersion,
            @Param("desiredState") DesiredState desiredState,
            @Param("nextExecutionAt") Instant nextExecutionAt);

    @Update("""
            UPDATE app.task_definition
               SET last_execution_at = #{lastExecutionAt},
                   next_execution_at = CASE
                       WHEN desired_state = 'ACTIVE'
                           THEN CAST(#{nextExecutionAt} AS timestamptz)
                       ELSE NULL::timestamptz
                   END
             WHERE task_id = #{taskId}
            """)
    int updateSchedule(
            @Param("taskId") long taskId,
            @Param("lastExecutionAt") Instant lastExecutionAt,
            @Param("nextExecutionAt") Instant nextExecutionAt);

    @Update("""
            UPDATE app.task_definition
               SET source_metadata = CAST(#{sourceMetadataJson} AS jsonb),
                   version = version + 1
             WHERE task_id = #{taskId}
            """)
    int updateSourceMetadata(
            @Param("taskId") long taskId,
            @Param("sourceMetadataJson") String sourceMetadataJson);

    @Update("""
            UPDATE app.task_definition
               SET desired_state = 'PAUSED',
                   last_execution_at = #{lastExecutionAt},
                   next_execution_at = NULL,
                   version = version + 1
             WHERE task_id = #{taskId}
            """)
    int pauseAfterCompletion(
            @Param("taskId") long taskId,
            @Param("lastExecutionAt") Instant lastExecutionAt);

    @Select("SELECT count(*) FROM app.task_definition")
    long countAll();

    @Select("SELECT count(*) FROM app.task_definition WHERE desired_state = 'ACTIVE'")
    long countActive();

    @Select("""
            SELECT count(*)
              FROM app.task_definition task_definition
             WHERE desired_state = 'ACTIVE'
               AND NOT EXISTS (
                   SELECT 1
                     FROM app.task_discovery_relation relation
                    WHERE relation.child_task_id = task_definition.task_id
                      AND relation.relation_mode = 'MANAGED'
               )
            """)
    long countPrimaryActive();

    @Select("""
            SELECT count(*)
              FROM app.task_definition task_definition
             WHERE desired_state = 'ACTIVE'
               AND EXISTS (
                   SELECT 1
                     FROM app.task_discovery_relation relation
                    WHERE relation.child_task_id = task_definition.task_id
                      AND relation.relation_mode = 'MANAGED'
               )
            """)
    long countManagedActive();
}
