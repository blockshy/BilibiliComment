CREATE INDEX idx_credential_profile_enabled
    ON app.credential_profile (enabled, credential_profile_id);

CREATE INDEX idx_task_definition_desired_next
    ON app.task_definition (desired_state, next_execution_at, task_id)
    WHERE desired_state = 'ACTIVE';

CREATE INDEX idx_task_definition_credential
    ON app.task_definition (credential_profile_id)
    WHERE credential_profile_id IS NOT NULL;

CREATE UNIQUE INDEX uq_task_execution_one_active_per_task
    ON app.task_execution (task_id)
    WHERE status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT');

CREATE INDEX idx_task_execution_task_history
    ON app.task_execution (task_id, execution_id DESC);

CREATE INDEX idx_task_execution_status_heartbeat
    ON app.task_execution (status, heartbeat_at)
    WHERE status IN ('RUNNING', 'RETRY_WAIT');

CREATE INDEX idx_task_event_task_sequence
    ON app.task_event (task_id, event_sequence DESC)
    WHERE task_id IS NOT NULL;

CREATE INDEX idx_task_event_execution_sequence
    ON app.task_event (execution_id, event_sequence DESC)
    WHERE execution_id IS NOT NULL;

CREATE INDEX idx_operation_audit_occurred
    ON app.operation_audit (occurred_at DESC, audit_id DESC);

CREATE INDEX idx_operation_audit_target
    ON app.operation_audit (target_type, target_id, occurred_at DESC)
    WHERE target_type IS NOT NULL AND target_id IS NOT NULL;

CREATE INDEX idx_idempotency_request_expiry
    ON app.idempotency_request (expires_at);

-- INCLUDING ALL in ensure_comment_table copies this cursor index and the
-- template's primary/unique constraints into every future per-task table.
CREATE INDEX idx_comment_table_template_cursor
    ON comment_data.comment_table_template (ctime DESC, rpid DESC);
