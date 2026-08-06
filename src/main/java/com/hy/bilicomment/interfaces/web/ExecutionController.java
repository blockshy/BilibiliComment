package com.hy.bilicomment.interfaces.web;

import com.hy.bilicomment.application.execution.TaskExecutionService;
import com.hy.bilicomment.application.task.TaskService;
import com.hy.bilicomment.domain.error.DomainException;
import com.hy.bilicomment.domain.task.TaskType;
import com.hy.bilicomment.infrastructure.persistence.CommentRepository;
import com.hy.bilicomment.infrastructure.persistence.entity.ExecutionRow;
import com.hy.bilicomment.interfaces.web.dto.ApiPage;
import com.hy.bilicomment.interfaces.web.dto.TaskDtos;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ExecutionController {

    private final TaskExecutionService executionService;
    private final TaskViewAssembler assembler;
    private final CommentRepository commentRepository;
    private final TaskService taskService;

    public ExecutionController(
            TaskExecutionService executionService,
            TaskViewAssembler assembler,
            CommentRepository commentRepository,
            TaskService taskService) {
        this.executionService = executionService;
        this.assembler = assembler;
        this.commentRepository = commentRepository;
        this.taskService = taskService;
    }

    @GetMapping("/tasks/{taskId}/executions")
    public ApiPage<TaskDtos.TaskExecution> executions(
            @PathVariable String taskId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        long id = TaskController.parseId(taskId, "taskId");
        int safeLimit = Math.max(1, Math.min(limit, 100));
        Long beforeExecutionId = cursor == null || cursor.isBlank()
                ? null
                : TaskController.parseId(cursor, "cursor");
        List<ExecutionRow> rows = executionService.history(id, beforeExecutionId, safeLimit + 1);
        boolean hasNext = rows.size() > safeLimit;
        if (hasNext) {
            rows = rows.subList(0, safeLimit);
        }
        List<TaskDtos.TaskExecution> items = rows.stream()
                .map(assembler::execution)
                .toList();
        String next = hasNext && !rows.isEmpty()
                ? Long.toString(rows.getLast().executionId())
                : null;
        return new ApiPage<>(items, next, executionService.historyCount(id));
    }

    @GetMapping("/executions/{executionId}")
    public TaskDtos.TaskExecution execution(@PathVariable String executionId) {
        return assembler.execution(executionService.require(
                TaskController.parseId(executionId, "executionId")));
    }

    @GetMapping("/tasks/{taskId}/comments")
    public ApiPage<TaskDtos.Comment> comments(
            @PathVariable String taskId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        long id = TaskController.parseId(taskId, "taskId");
        if (taskService.require(id).taskType() != TaskType.CONTENT_COMMENTS) {
            throw new DomainException("COMMENTS_NOT_APPLICABLE", "UP 主监控任务没有独立评论表");
        }
        int safeLimit = Math.max(1, Math.min(limit, 100));
        CommentCursor before = decodeCursor(cursor);
        List<CommentRepository.CommentView> rows = commentRepository.findPage(
                id,
                before == null ? null : before.time(),
                before == null ? null : before.rpid(),
                safeLimit + 1);
        boolean hasNext = rows.size() > safeLimit;
        if (hasNext) {
            rows = rows.subList(0, safeLimit);
        }
        List<TaskDtos.Comment> items = rows.stream()
                .map(row -> new TaskDtos.Comment(
                        Long.toString(row.commentId()),
                        Long.toString(row.rpid()),
                        row.parentRpid() == null ? null : Long.toString(row.parentRpid()),
                        row.mid(),
                        row.uname(),
                        row.avatar(),
                        row.currentLevel(),
                        row.content(),
                        row.ctime()))
                .toList();
        String next = hasNext && !rows.isEmpty() ? encodeCursor(rows.getLast()) : null;
        return new ApiPage<>(items, next, commentRepository.count(id));
    }

    private CommentCursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = decoded.lastIndexOf(':');
            if (separator <= 0) {
                throw new IllegalArgumentException();
            }
            return new CommentCursor(
                    Instant.ofEpochMilli(Long.parseLong(decoded.substring(0, separator))),
                    Long.parseLong(decoded.substring(separator + 1)));
        } catch (RuntimeException exception) {
            throw new DomainException("CURSOR_INVALID", "评论分页游标无效");
        }
    }

    private String encodeCursor(CommentRepository.CommentView row) {
        String value = row.ctime().toEpochMilli() + ":" + row.rpid();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private record CommentCursor(Instant time, long rpid) {}
}
