package com.hy.bilicomment.application.comment;

import com.hy.bilicomment.application.task.TaskService;
import com.hy.bilicomment.domain.comment.CommentFilter;
import com.hy.bilicomment.domain.comment.CommentSort;
import com.hy.bilicomment.domain.error.DomainException;
import com.hy.bilicomment.domain.task.TaskType;
import com.hy.bilicomment.infrastructure.persistence.CommentRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class CommentSearchService {

    private final TaskService taskService;
    private final CommentRepository commentRepository;
    private final CommentFilterService filterService;
    private final CommentCursorCodec cursorCodec;

    public CommentSearchService(
            TaskService taskService,
            CommentRepository commentRepository,
            CommentFilterService filterService,
            CommentCursorCodec cursorCodec) {
        this.taskService = taskService;
        this.commentRepository = commentRepository;
        this.filterService = filterService;
        this.cursorCodec = cursorCodec;
    }

    public SearchResult search(
            long taskId,
            CommentFilter requestedFilter,
            CommentSort requestedSort,
            String cursor,
            int limit,
            boolean includeTotal) {
        if (taskService.require(taskId).taskType() != TaskType.CONTENT_COMMENTS) {
            throw new DomainException("COMMENTS_NOT_APPLICABLE", "UP 主监控任务没有独立评论表");
        }
        if (limit < 1 || limit > 100) {
            throw new DomainException("COMMENT_LIMIT_INVALID", "评论分页大小必须在 1 到 100 之间");
        }

        CommentFilter filter = filterService.normalize(requestedFilter);
        CommentSort sort = requestedSort == null ? CommentSort.CTIME_DESC : requestedSort;
        String filterHash = cursorCodec.fingerprint(filter, sort);
        long snapshot;
        java.time.Instant afterTime = null;
        Long afterRpid = null;
        if (cursor == null || cursor.isBlank()) {
            snapshot = commentRepository.captureSnapshot(taskId);
        } else {
            CommentCursorCodec.CursorState state =
                    cursorCodec.decode(cursor, taskId, filterHash, sort);
            snapshot = state.snapshotMaxCommentId();
            afterTime = state.afterTime();
            afterRpid = state.afterRpid();
        }

        List<CommentRepository.CommentView> rows = commentRepository.search(
                new CommentRepository.SearchSpec(
                        taskId,
                        filter,
                        sort,
                        snapshot,
                        afterTime,
                        afterRpid,
                        limit + 1));
        boolean hasNext = rows.size() > limit;
        if (hasNext) {
            rows = new ArrayList<>(rows.subList(0, limit));
        }
        String nextCursor = null;
        if (hasNext && !rows.isEmpty()) {
            CommentRepository.CommentView boundary = rows.getLast();
            nextCursor = cursorCodec.encode(
                    taskId,
                    filterHash,
                    sort,
                    snapshot,
                    boundary.ctime(),
                    boundary.rpid());
        }
        Long total = includeTotal ? commentRepository.countFiltered(taskId, snapshot, filter) : null;
        return new SearchResult(List.copyOf(rows), nextCursor, total, snapshot, filter, sort);
    }

    public record SearchResult(
            List<CommentRepository.CommentView> items,
            String nextCursor,
            Long total,
            long snapshotMaxCommentId,
            CommentFilter filter,
            CommentSort sort) {}
}
