package com.hy.bilicomment.infrastructure.persistence.mapper;

import com.hy.bilicomment.infrastructure.persistence.entity.CommentExportJobRow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface CommentExportJobMapper {

    String COLUMNS = """
            export_id, task_id, requested_by, export_format,
            export_columns::text AS export_columns_json,
            filter_json::text AS filter_json, filter_hash, sort_order,
            snapshot_max_comment_id, status, row_limit, byte_limit,
            rows_written, bytes_written, file_key, encrypted_sha256,
            cancellation_requested, worker_owner, lease_until, error_code, error_summary,
            created_at, started_at, heartbeat_at, finished_at, expires_at, updated_at
            """;

    String JOB_COLUMNS = """
            job.export_id, job.task_id, job.requested_by, job.export_format,
            job.export_columns::text AS export_columns_json,
            job.filter_json::text AS filter_json, job.filter_hash, job.sort_order,
            job.snapshot_max_comment_id, job.status, job.row_limit, job.byte_limit,
            job.rows_written, job.bytes_written, job.file_key, job.encrypted_sha256,
            job.cancellation_requested, job.worker_owner, job.lease_until,
            job.error_code, job.error_summary,
            job.created_at, job.started_at, job.heartbeat_at, job.finished_at,
            job.expires_at, job.updated_at
            """;

    @Select("SELECT pg_advisory_xact_lock(hashtextextended('bilibili-comment-export-queue', 0))")
    void lockQueue();

    @Select("""
            INSERT INTO app.comment_export_job (
                task_id, requested_by, export_format, export_columns,
                filter_json, filter_hash, sort_order, snapshot_max_comment_id,
                row_limit, byte_limit, expires_at
            ) VALUES (
                #{taskId}, #{requestedBy}, #{format}, CAST(#{columnsJson} AS jsonb),
                CAST(#{filterJson} AS jsonb), #{filterHash}, #{sortOrder}, #{snapshotMaxCommentId},
                #{rowLimit}, #{byteLimit}, #{expiresAt}
            ) RETURNING
            """ + COLUMNS)
    CommentExportJobRow insert(
            @Param("taskId") long taskId,
            @Param("requestedBy") String requestedBy,
            @Param("format") String format,
            @Param("columnsJson") String columnsJson,
            @Param("filterJson") String filterJson,
            @Param("filterHash") String filterHash,
            @Param("sortOrder") String sortOrder,
            @Param("snapshotMaxCommentId") long snapshotMaxCommentId,
            @Param("rowLimit") long rowLimit,
            @Param("byteLimit") long byteLimit,
            @Param("expiresAt") Instant expiresAt);

    @Select("SELECT " + COLUMNS + " FROM app.comment_export_job WHERE export_id = #{exportId}")
    Optional<CommentExportJobRow> findById(long exportId);

    @Select("SELECT " + COLUMNS + " FROM app.comment_export_job"
            + " WHERE task_id = #{taskId} ORDER BY export_id DESC LIMIT #{limit}")
    List<CommentExportJobRow> findByTask(@Param("taskId") long taskId, @Param("limit") int limit);

    @Select("SELECT count(*) FROM app.comment_export_job WHERE status IN ('QUEUED', 'RUNNING')")
    long countActive();

    @Select("SELECT count(*) FROM app.comment_export_job"
            + " WHERE status = 'QUEUED' AND expires_at > clock_timestamp()")
    long countQueued();

    @Select("""
            WITH candidate AS (
                SELECT export_id
                  FROM app.comment_export_job
                 WHERE status = 'QUEUED'
                   AND NOT cancellation_requested
                   AND expires_at > clock_timestamp()
                 ORDER BY export_id
                 FOR UPDATE SKIP LOCKED
                 LIMIT 1
            )
            UPDATE app.comment_export_job AS job
               SET status = 'RUNNING',
                   started_at = COALESCE(started_at, clock_timestamp()),
                   heartbeat_at = clock_timestamp(),
                   worker_owner = #{workerOwner},
                   lease_until = clock_timestamp() + (#{leaseSeconds} * interval '1 second'),
                   error_code = NULL,
                   error_summary = NULL
              FROM candidate
             WHERE job.export_id = candidate.export_id
            RETURNING
            """ + JOB_COLUMNS)
    Optional<CommentExportJobRow> claimNext(
            @Param("workerOwner") String workerOwner,
            @Param("leaseSeconds") long leaseSeconds);

    @Update("""
            UPDATE app.comment_export_job
               SET rows_written = #{rowsWritten},
                   bytes_written = #{bytesWritten},
                   heartbeat_at = clock_timestamp()
             WHERE export_id = #{exportId}
               AND status = 'RUNNING'
               AND worker_owner = #{workerOwner}
               AND lease_until > clock_timestamp()
            """)
    int updateProgress(
            @Param("exportId") long exportId,
            @Param("rowsWritten") long rowsWritten,
            @Param("bytesWritten") long bytesWritten,
            @Param("workerOwner") String workerOwner);

    @Update("""
            UPDATE app.comment_export_job
               SET status = 'SUCCEEDED',
                   rows_written = #{rowsWritten},
                   bytes_written = #{bytesWritten},
                   file_key = #{fileKey},
                   encrypted_sha256 = #{encryptedSha256},
                   worker_owner = NULL,
                   lease_until = NULL,
                   heartbeat_at = clock_timestamp(),
                   finished_at = clock_timestamp()
             WHERE export_id = #{exportId}
               AND status = 'RUNNING'
               AND NOT cancellation_requested
               AND worker_owner = #{workerOwner}
               AND lease_until > clock_timestamp()
            """)
    int markSucceeded(
            @Param("exportId") long exportId,
            @Param("rowsWritten") long rowsWritten,
            @Param("bytesWritten") long bytesWritten,
            @Param("fileKey") String fileKey,
            @Param("encryptedSha256") String encryptedSha256,
            @Param("workerOwner") String workerOwner);

    @Update("""
            UPDATE app.comment_export_job
               SET status = 'FAILED',
                   error_code = #{errorCode},
                   error_summary = #{errorSummary},
                   worker_owner = NULL,
                   lease_until = NULL,
                   heartbeat_at = clock_timestamp(),
                   finished_at = clock_timestamp()
             WHERE export_id = #{exportId}
               AND status = 'RUNNING'
               AND worker_owner = #{workerOwner}
               AND lease_until > clock_timestamp()
            """)
    int markFailed(
            @Param("exportId") long exportId,
            @Param("errorCode") String errorCode,
            @Param("errorSummary") String errorSummary,
            @Param("workerOwner") String workerOwner);

    @Update("""
            UPDATE app.comment_export_job
               SET cancellation_requested = true,
                   status = CASE WHEN status = 'QUEUED' THEN 'CANCELLED' ELSE status END,
                   finished_at = CASE WHEN status = 'QUEUED' THEN clock_timestamp() ELSE finished_at END
             WHERE export_id = #{exportId}
               AND status IN ('QUEUED', 'RUNNING')
            """)
    int requestCancellation(long exportId);

    @Update("""
            UPDATE app.comment_export_job
               SET status = 'CANCELLED',
                   cancellation_requested = true,
                   worker_owner = NULL,
                   lease_until = NULL,
                   heartbeat_at = clock_timestamp(),
                   finished_at = clock_timestamp()
             WHERE export_id = #{exportId}
               AND status = 'RUNNING'
               AND worker_owner = #{workerOwner}
               AND lease_until > clock_timestamp()
            """)
    int markCancelled(
            @Param("exportId") long exportId,
            @Param("workerOwner") String workerOwner);

    @Select("SELECT cancellation_requested FROM app.comment_export_job"
            + " WHERE export_id = #{exportId} AND status = 'RUNNING'"
            + " AND worker_owner = #{workerOwner} AND lease_until > clock_timestamp()")
    Optional<Boolean> findCancellationRequested(
            @Param("exportId") long exportId,
            @Param("workerOwner") String workerOwner);

    @Update("""
            UPDATE app.comment_export_job
               SET status = CASE WHEN cancellation_requested THEN 'CANCELLED' ELSE 'QUEUED' END,
                   started_at = CASE WHEN cancellation_requested THEN started_at ELSE NULL END,
                   heartbeat_at = NULL,
                   worker_owner = NULL,
                   lease_until = NULL,
                   error_code = NULL,
                   error_summary = NULL,
                   finished_at = CASE WHEN cancellation_requested THEN clock_timestamp() ELSE NULL END
             WHERE status = 'RUNNING'
               AND lease_until <= clock_timestamp()
            """)
    int recoverStale();

    @Select("SELECT " + COLUMNS + " FROM app.comment_export_job"
            + " WHERE expires_at <= #{now} AND status <> 'RUNNING' ORDER BY export_id LIMIT #{limit}")
    List<CommentExportJobRow> findExpired(@Param("now") Instant now, @Param("limit") int limit);

    @Select("SELECT file_key FROM app.comment_export_job WHERE file_key IS NOT NULL")
    List<String> findStoredFileKeys();

    @Delete("DELETE FROM app.comment_export_job WHERE export_id = #{exportId}"
            + " AND expires_at <= #{now} AND status <> 'RUNNING'")
    int deleteExpired(@Param("exportId") long exportId, @Param("now") Instant now);
}
