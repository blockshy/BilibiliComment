package com.hy.bilicomment.domain.error;

public class TaskConflictException extends DomainException {

    private final long existingTaskId;

    public TaskConflictException(long existingTaskId) {
        super("TASK_ALREADY_EXISTS", "该来源已存在同模式任务");
        this.existingTaskId = existingTaskId;
    }

    public long getExistingTaskId() {
        return existingTaskId;
    }
}
