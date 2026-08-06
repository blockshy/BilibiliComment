package com.hy.bilicomment.application.task;

import com.hy.bilicomment.domain.error.DomainException;
import com.hy.bilicomment.domain.task.TaskDiscoveryRelationMode;
import com.hy.bilicomment.infrastructure.persistence.entity.TaskDiscoveryRelationRow;
import com.hy.bilicomment.infrastructure.persistence.mapper.TaskDiscoveryRelationMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskDiscoveryRelationService {

    private final TaskDiscoveryRelationMapper relationMapper;

    public TaskDiscoveryRelationService(TaskDiscoveryRelationMapper relationMapper) {
        this.relationMapper = relationMapper;
    }

    @Transactional
    public TaskDiscoveryRelationRow upsert(
            long parentTaskId,
            long childTaskId,
            long executionId,
            TaskDiscoveryRelationMode relationMode) {
        if (parentTaskId <= 0 || childTaskId <= 0 || executionId <= 0) {
            throw new DomainException("TASK_DISCOVERY_RELATION_INVALID", "任务发现关系标识无效");
        }
        if (parentTaskId == childTaskId) {
            throw new DomainException("TASK_DISCOVERY_RELATION_INVALID", "任务不能发现自身");
        }
        if (relationMode == null) {
            throw new DomainException("TASK_DISCOVERY_RELATION_INVALID", "任务发现关系模式不能为空");
        }
        return relationMapper.upsert(parentTaskId, childTaskId, executionId, relationMode)
                .orElseThrow(() -> new DomainException(
                        "TASK_DISCOVERY_RELATION_REJECTED", "无法保存任务发现关系"));
    }

    @Transactional(readOnly = true)
    public Optional<TaskDiscoveryRelationMode> findModeForChild(long childTaskId) {
        return childTaskId <= 0
                ? Optional.empty()
                : relationMapper.findModeForChild(childTaskId);
    }

    @Transactional(readOnly = true)
    public boolean isManagedChild(long childTaskId) {
        return childTaskId > 0 && relationMapper.existsManagedByChild(childTaskId);
    }

    @Transactional(readOnly = true)
    public List<Long> findManagedChildrenWithoutExecution(int limit) {
        return relationMapper.findManagedChildrenWithoutExecution(Math.max(1, Math.min(limit, 100)));
    }
}
