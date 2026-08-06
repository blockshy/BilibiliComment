ALTER TABLE app.comment_export_job
    ADD COLUMN worker_owner varchar(200),
    ADD COLUMN lease_until timestamptz;

UPDATE app.comment_export_job
   SET status = CASE WHEN cancellation_requested THEN 'CANCELLED' ELSE 'QUEUED' END,
       started_at = CASE WHEN cancellation_requested THEN started_at ELSE NULL END,
       heartbeat_at = NULL,
       finished_at = CASE WHEN cancellation_requested THEN clock_timestamp() ELSE NULL END,
       error_code = NULL,
       error_summary = NULL
 WHERE status = 'RUNNING';

ALTER TABLE app.comment_export_job
    ADD CONSTRAINT ck_comment_export_job_lease
        CHECK (
            (status = 'RUNNING' AND worker_owner IS NOT NULL AND lease_until IS NOT NULL)
            OR
            (status <> 'RUNNING' AND worker_owner IS NULL AND lease_until IS NULL)
        );

CREATE INDEX idx_comment_export_job_running_lease
    ON app.comment_export_job (lease_until, export_id)
    WHERE status = 'RUNNING';

COMMENT ON COLUMN app.comment_export_job.worker_owner IS
    'Unpredictable fencing token for the worker that currently owns this export';
COMMENT ON COLUMN app.comment_export_job.lease_until IS
    'Database-clock deadline after which the export may be recovered and re-claimed';
