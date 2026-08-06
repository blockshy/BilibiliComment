package com.hy.bilicomment.infrastructure.persistence.mapper;

import com.hy.bilicomment.domain.execution.ExecutionPhase;
import com.hy.bilicomment.domain.execution.TriggerType;
import com.hy.bilicomment.infrastructure.persistence.entity.ExecutionRow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface ExecutionMapper {

    String COLUMNS = """
            execution_id, task_id, trigger_type, status, phase, attempt,
            pages_processed, comments_discovered, comments_inserted,
            comments_duplicate, retry_count, cursor_state::text AS cursor_state_json,
            cancellation_requested, error_code, error_summary, trace_id, worker_id,
            queued_at, started_at, heartbeat_at, next_retry_at, finished_at,
            created_at, updated_at
            """;

    @Select("SELECT " + COLUMNS + " FROM app.task_execution WHERE execution_id = #{executionId}")
    Optional<ExecutionRow> findById(long executionId);

    @Select("SELECT " + COLUMNS + " FROM app.task_execution"
            + " WHERE task_id = #{taskId} ORDER BY execution_id DESC LIMIT #{limit}")
    List<ExecutionRow> findByTask(@Param("taskId") long taskId, @Param("limit") int limit);

    @Select({
        "<script>",
        "SELECT DISTINCT ON (task_id) " + COLUMNS + " FROM app.task_execution",
        "<choose>",
        "<when test='taskIds != null and !taskIds.isEmpty()'>",
        " WHERE task_id IN",
        "<foreach collection='taskIds' item='taskId' open='(' separator=',' close=')'>",
        "#{taskId}",
        "</foreach>",
        "</when>",
        "<otherwise> WHERE 1 = 0</otherwise>",
        "</choose>",
        " ORDER BY task_id, execution_id DESC",
        "</script>"
    })
    List<ExecutionRow> findLatestByTaskIds(@Param("taskIds") List<Long> taskIds);

    @Select({
        "<script>",
        "SELECT " + COLUMNS + " FROM app.task_execution",
        " WHERE task_id = #{taskId}",
        "<if test='beforeExecutionId != null'> AND execution_id &lt; #{beforeExecutionId}</if>",
        " ORDER BY execution_id DESC LIMIT #{limit}",
        "</script>"
    })
    List<ExecutionRow> findPageByTask(
            @Param("taskId") long taskId,
            @Param("beforeExecutionId") Long beforeExecutionId,
            @Param("limit") int limit);

    @Select("SELECT count(*) FROM app.task_execution WHERE task_id = #{taskId}")
    long countByTask(long taskId);

    @Select("SELECT " + COLUMNS + " FROM app.task_execution"
            + " WHERE task_id = #{taskId} AND status = 'SUCCEEDED'"
            + " ORDER BY execution_id DESC LIMIT 1")
    Optional<ExecutionRow> findLastSucceeded(long taskId);

    @Select({
        "<script>",
        "SELECT DISTINCT ON (task_id) " + COLUMNS + " FROM app.task_execution",
        "<choose>",
        "<when test='taskIds != null and !taskIds.isEmpty()'>",
        " WHERE task_id IN",
        "<foreach collection='taskIds' item='taskId' open='(' separator=',' close=')'>",
        "#{taskId}",
        "</foreach>",
        " AND status = 'SUCCEEDED'",
        "</when>",
        "<otherwise> WHERE 1 = 0</otherwise>",
        "</choose>",
        " ORDER BY task_id, execution_id DESC",
        "</script>"
    })
    List<ExecutionRow> findLastSucceededByTaskIds(@Param("taskIds") List<Long> taskIds);

    @Select("INSERT INTO app.task_execution (task_id, trigger_type, status, trace_id)"
            + " VALUES (#{taskId}, #{triggerType}, 'QUEUED', #{traceId}) RETURNING " + COLUMNS)
    ExecutionRow insertQueued(
            @Param("taskId") long taskId,
            @Param("triggerType") TriggerType triggerType,
            @Param("traceId") String traceId);

    @Select("INSERT INTO app.task_execution (task_id, trigger_type, status, trace_id)"
            + " SELECT #{taskId}, #{triggerType}, 'QUEUED', #{traceId}"
            + " WHERE NOT EXISTS (SELECT 1 FROM app.task_execution"
            + "     WHERE task_id = #{taskId})"
            + " ON CONFLICT (task_id) WHERE status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT')"
            + " DO NOTHING RETURNING " + COLUMNS)
    Optional<ExecutionRow> insertInitialQueued(
            @Param("taskId") long taskId,
            @Param("triggerType") TriggerType triggerType,
            @Param("traceId") String traceId);

    @Select("SELECT " + COLUMNS + " FROM app.task_execution"
            + " WHERE status = 'QUEUED' AND NOT cancellation_requested"
            + " ORDER BY queued_at, execution_id LIMIT #{limit}")
    List<ExecutionRow> findQueued(@Param("limit") int limit);

    @Update("""
            UPDATE app.task_execution
               SET status = 'RUNNING',
                   phase = 'RESOLVING_SOURCE',
                   worker_id = #{workerId},
                   lease_owner = #{leaseOwner},
                   lease_until = clock_timestamp() + (#{leaseSeconds} * interval '1 second'),
                   error_code = NULL,
                   error_summary = NULL,
                   started_at = COALESCE(started_at, clock_timestamp()),
                   heartbeat_at = clock_timestamp()
             WHERE execution_id = #{executionId}
               AND status = 'QUEUED'
               AND NOT cancellation_requested
            """)
    int claimQueued(
            @Param("executionId") long executionId,
            @Param("leaseOwner") String leaseOwner,
            @Param("workerId") String workerId,
            @Param("leaseSeconds") long leaseSeconds);

    @Update("""
            UPDATE app.task_execution
               SET heartbeat_at = clock_timestamp(),
                   lease_until = clock_timestamp() + (#{leaseSeconds} * interval '1 second')
             WHERE execution_id = #{executionId}
               AND status = 'RUNNING'
               AND lease_owner = #{leaseOwner}
               AND lease_until > clock_timestamp()
            """)
    int renewLease(
            @Param("executionId") long executionId,
            @Param("leaseOwner") String leaseOwner,
            @Param("leaseSeconds") long leaseSeconds);

    @Select("""
            SELECT EXISTS (
                SELECT 1
                  FROM app.task_execution
                 WHERE execution_id = #{executionId}
                   AND status = 'RUNNING'
                   AND lease_owner = #{leaseOwner}
                   AND lease_until > clock_timestamp()
            )
            """)
    boolean ownsLease(
            @Param("executionId") long executionId,
            @Param("leaseOwner") String leaseOwner);

    @Select("""
            SELECT execution_id
              FROM app.task_execution
             WHERE execution_id = #{executionId}
               AND task_id = #{taskId}
               AND status = 'RUNNING'
               AND lease_owner = #{leaseOwner}
               AND lease_until > clock_timestamp()
             FOR UPDATE
            """)
    Optional<Long> lockOwnedLease(
            @Param("executionId") long executionId,
            @Param("taskId") long taskId,
            @Param("leaseOwner") String leaseOwner);

    @Select("""
            WITH expired AS (
                SELECT execution_id
                 FROM app.task_execution
                 WHERE status = 'RUNNING'
                   AND lease_until <= clock_timestamp()
                 ORDER BY lease_until, execution_id
                 FOR UPDATE SKIP LOCKED
                 LIMIT #{limit}
            )
            , requeued AS (
                UPDATE app.task_execution execution
                   SET status = 'QUEUED',
                       phase = NULL,
                       attempt = attempt + 1,
                       worker_id = NULL,
                       lease_owner = NULL,
                       lease_until = NULL,
                       queued_at = clock_timestamp(),
                       heartbeat_at = clock_timestamp(),
                       error_code = 'EXECUTION_LEASE_EXPIRED',
                       error_summary = '执行租约过期，任务已重新排队'
                  FROM expired
                 WHERE execution.execution_id = expired.execution_id
                RETURNING execution.*
            )
            """ + "SELECT " + COLUMNS + " FROM requeued ORDER BY execution_id")
    List<ExecutionRow> requeueExpiredLeases(@Param("limit") int limit);

    @Update("""
            UPDATE app.task_execution
               SET phase = #{phase},
                   pages_processed = #{pagesProcessed},
                   comments_discovered = #{commentsDiscovered},
                   comments_inserted = #{commentsInserted},
                   comments_duplicate = #{commentsDuplicate},
                   cursor_state = CAST(#{cursorStateJson} AS jsonb),
                   heartbeat_at = clock_timestamp()
             WHERE execution_id = #{executionId}
               AND status = 'RUNNING'
               AND lease_owner = #{leaseOwner}
               AND lease_until > clock_timestamp()
            """)
    int updateProgress(
            @Param("executionId") long executionId,
            @Param("leaseOwner") String leaseOwner,
            @Param("phase") ExecutionPhase phase,
            @Param("pagesProcessed") long pagesProcessed,
            @Param("commentsDiscovered") long commentsDiscovered,
            @Param("commentsInserted") long commentsInserted,
            @Param("commentsDuplicate") long commentsDuplicate,
            @Param("cursorStateJson") String cursorStateJson);

    @Update("""
            UPDATE app.task_execution
               SET status = 'SUCCEEDED',
                   phase = 'SCHEDULING_NEXT',
                   finished_at = clock_timestamp(),
                   heartbeat_at = clock_timestamp(),
                   lease_owner = NULL,
                   lease_until = NULL
             WHERE execution_id = #{executionId}
               AND status = 'RUNNING'
               AND lease_owner = #{leaseOwner}
               AND lease_until > clock_timestamp()
               AND NOT cancellation_requested
            """)
    int markSucceeded(
            @Param("executionId") long executionId,
            @Param("leaseOwner") String leaseOwner);

    @Update("""
            UPDATE app.task_execution
               SET status = 'RETRY_WAIT',
                   phase = 'WAITING_RATE_LIMIT',
                   retry_count = retry_count + 1,
                   next_retry_at = #{nextRetryAt},
                   error_code = #{errorCode},
                   error_summary = #{errorSummary},
                   worker_id = NULL,
                   lease_owner = NULL,
                   lease_until = NULL,
                   heartbeat_at = clock_timestamp()
             WHERE execution_id = #{executionId}
               AND status = 'RUNNING'
               AND lease_owner = #{leaseOwner}
               AND lease_until > clock_timestamp()
            """)
    int markRetryWaiting(
            @Param("executionId") long executionId,
            @Param("leaseOwner") String leaseOwner,
            @Param("nextRetryAt") Instant nextRetryAt,
            @Param("errorCode") String errorCode,
            @Param("errorSummary") String errorSummary);

    @Select("SELECT " + COLUMNS + " FROM app.task_execution"
            + " WHERE status = 'RETRY_WAIT' AND next_retry_at <= #{now}"
            + " ORDER BY next_retry_at, execution_id LIMIT #{limit}")
    List<ExecutionRow> findRetryWaitingDue(
            @Param("now") Instant now,
            @Param("limit") int limit);

    @Update("""
            UPDATE app.task_execution
               SET status = 'QUEUED',
                   phase = NULL,
                   attempt = attempt + 1,
                   next_retry_at = NULL,
                   error_code = NULL,
                   error_summary = NULL,
                   worker_id = NULL,
                   lease_owner = NULL,
                   lease_until = NULL,
                   heartbeat_at = clock_timestamp()
             WHERE execution_id = #{executionId}
               AND status = 'RETRY_WAIT'
               AND next_retry_at <= #{now}
               AND NOT cancellation_requested
            """)
    int resumeRetry(
            @Param("executionId") long executionId,
            @Param("now") Instant now);

    @Update("""
            UPDATE app.task_execution
               SET status = 'RETRY_WAIT',
                   phase = 'WAITING_RATE_LIMIT',
                   next_retry_at = #{nextRetryAt},
                   worker_id = NULL,
                   lease_owner = NULL,
                   lease_until = NULL,
                   heartbeat_at = clock_timestamp()
             WHERE execution_id = #{executionId}
               AND status = 'QUEUED'
               AND retry_count > 0
            """)
    int deferQueuedRetry(
            @Param("executionId") long executionId,
            @Param("nextRetryAt") Instant nextRetryAt);

    @Update("""
            UPDATE app.task_execution
               SET status = 'FAILED',
                   next_retry_at = NULL,
                   error_code = #{errorCode},
                   error_summary = #{errorSummary},
                   finished_at = clock_timestamp(),
                   heartbeat_at = clock_timestamp(),
                   lease_owner = NULL,
                   lease_until = NULL
             WHERE execution_id = #{executionId}
               AND status IN ('QUEUED', 'RETRY_WAIT')
            """)
    int markFailed(
            @Param("executionId") long executionId,
            @Param("errorCode") String errorCode,
            @Param("errorSummary") String errorSummary);

    @Update("""
            UPDATE app.task_execution
               SET status = 'FAILED',
                   next_retry_at = NULL,
                   error_code = #{errorCode},
                   error_summary = #{errorSummary},
                   finished_at = clock_timestamp(),
                   heartbeat_at = clock_timestamp(),
                   lease_owner = NULL,
                   lease_until = NULL
             WHERE execution_id = #{executionId}
               AND status = 'RUNNING'
               AND lease_owner = #{leaseOwner}
               AND lease_until > clock_timestamp()
               AND NOT cancellation_requested
            """)
    int markFailedOwned(
            @Param("executionId") long executionId,
            @Param("leaseOwner") String leaseOwner,
            @Param("errorCode") String errorCode,
            @Param("errorSummary") String errorSummary);

    @Update("""
            UPDATE app.task_execution
               SET status = 'CANCELLED',
                   next_retry_at = NULL,
                   error_code = 'EXECUTION_CANCELLED',
                   error_summary = #{summary},
                   finished_at = clock_timestamp(),
                   heartbeat_at = clock_timestamp(),
                   lease_owner = NULL,
                   lease_until = NULL
             WHERE execution_id = #{executionId}
               AND status IN ('QUEUED', 'RETRY_WAIT')
            """)
    int markCancelled(
            @Param("executionId") long executionId,
            @Param("summary") String summary);

    @Update("""
            UPDATE app.task_execution
               SET status = 'CANCELLED',
                   next_retry_at = NULL,
                   error_code = 'EXECUTION_CANCELLED',
                   error_summary = #{summary},
                   finished_at = clock_timestamp(),
                   heartbeat_at = clock_timestamp(),
                   lease_owner = NULL,
                   lease_until = NULL
             WHERE execution_id = #{executionId}
               AND status = 'RUNNING'
               AND lease_owner = #{leaseOwner}
               AND lease_until > clock_timestamp()
            """)
    int markCancelledOwned(
            @Param("executionId") long executionId,
            @Param("leaseOwner") String leaseOwner,
            @Param("summary") String summary);

    @Update("""
            UPDATE app.task_execution
               SET cancellation_requested = true
             WHERE execution_id = #{executionId}
               AND status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT')
            """)
    int requestCancellation(long executionId);

    @Select("SELECT cancellation_requested FROM app.task_execution WHERE execution_id = #{executionId}")
    boolean isCancellationRequested(long executionId);

    @Select("SELECT count(*) FROM app.task_execution WHERE status = 'RUNNING'")
    long countRunning();

    @Select("SELECT count(*) FROM app.task_execution WHERE status = 'RETRY_WAIT'")
    long countRetryWaiting();

    @Select("""
            SELECT count(*)
              FROM app.task_execution
             WHERE status = 'FAILED'
               AND finished_at >= #{since}
            """)
    long countFailuresSince(Instant since);

    @Select("""
            SELECT COALESCE(sum(execution.comments_inserted), 0)
              FROM app.task_execution execution
              JOIN app.task_definition task_definition
                ON task_definition.task_id = execution.task_id
             WHERE execution.finished_at >= #{since}
               AND task_definition.task_type = 'CONTENT_COMMENTS'
            """)
    long sumInsertedSince(Instant since);
}
