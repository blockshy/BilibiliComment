ALTER TABLE app.task_execution
    ADD COLUMN lease_owner varchar(200),
    ADD COLUMN lease_until timestamptz;

-- Deployments must not inherit RUNNING rows that were created before leases
-- existed. Keeping the same execution row preserves its durable checkpoint and
-- the one-active-execution invariant while allowing a new worker to claim it.
UPDATE app.task_execution
   SET status = 'QUEUED',
       phase = NULL,
       attempt = attempt + 1,
       worker_id = NULL,
       queued_at = clock_timestamp(),
       heartbeat_at = clock_timestamp(),
       error_code = 'EXECUTION_LEASE_MIGRATED',
       error_summary = '执行记录已迁移到租约派发机制'
 WHERE status = 'RUNNING';

ALTER TABLE app.task_execution
    ADD CONSTRAINT ck_task_execution_lease
        CHECK (
            (status = 'RUNNING' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
            OR
            (status <> 'RUNNING' AND lease_owner IS NULL AND lease_until IS NULL)
        );

CREATE INDEX idx_task_execution_queued_dispatch
    ON app.task_execution (queued_at, execution_id)
    WHERE status = 'QUEUED' AND NOT cancellation_requested;

CREATE INDEX idx_task_execution_running_lease
    ON app.task_execution (lease_until, execution_id)
    WHERE status = 'RUNNING';

COMMENT ON COLUMN app.task_execution.lease_owner
    IS 'Unique claim token containing the worker instance identity';
COMMENT ON COLUMN app.task_execution.lease_until
    IS 'Expired RUNNING rows are returned to QUEUED by the durable dispatcher';
