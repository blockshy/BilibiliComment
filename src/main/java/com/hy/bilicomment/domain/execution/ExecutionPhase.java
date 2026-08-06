package com.hy.bilicomment.domain.execution;

public enum ExecutionPhase {
    RESOLVING_SOURCE,
    FETCHING_COMMENTS,
    PERSISTING_COMMENTS,
    WAITING_RATE_LIMIT,
    SCHEDULING_NEXT
}
