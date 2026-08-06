package com.hy.bilicomment.domain.execution;

public enum ExecutionStatus {
    QUEUED,
    RUNNING,
    RETRY_WAIT,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
