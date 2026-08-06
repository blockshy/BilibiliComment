package com.hy.bilicomment.interfaces.web;

import com.hy.bilicomment.application.comment.CommentSearchService;
import com.hy.bilicomment.domain.comment.CommentFilter;
import com.hy.bilicomment.domain.comment.CommentReplyScope;
import com.hy.bilicomment.domain.comment.CommentSort;
import com.hy.bilicomment.interfaces.web.dto.TaskDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class CommentController {

    private final CommentSearchService searchService;

    public CommentController(CommentSearchService searchService) {
        this.searchService = searchService;
    }

    @PostMapping("/tasks/{taskId}/comments/search")
    public CommentSearchPage search(
            @PathVariable String taskId,
            @Valid @RequestBody CommentSearchRequest request) {
        long id = TaskController.parseId(taskId, "taskId");
        CommentSearchService.SearchResult result = searchService.search(
                id,
                request.filter().toDomain(),
                request.sort(),
                request.cursor(),
                request.limit() == null ? 50 : request.limit(),
                Boolean.TRUE.equals(request.includeTotal()));
        List<TaskDtos.Comment> items = result.items().stream()
                .map(CommentController::comment)
                .toList();
        return new CommentSearchPage(items, result.nextCursor(), result.total());
    }

    static TaskDtos.Comment comment(
            com.hy.bilicomment.infrastructure.persistence.CommentRepository.CommentView row) {
        return new TaskDtos.Comment(
                Long.toString(row.commentId()),
                Long.toString(row.rpid()),
                row.parentRpid() == null ? null : Long.toString(row.parentRpid()),
                row.mid(),
                row.uname(),
                row.avatar(),
                row.currentLevel(),
                row.content(),
                row.ctime());
    }

    public record CommentSearchRequest(
            @NotNull @Valid CommentFilterRequest filter,
            CommentSort sort,
            @Size(max = 2048) String cursor,
            @Min(1) @Max(100) Integer limit,
            Boolean includeTotal) {}

    public record CommentFilterRequest(
            @Size(max = 200) String keyword,
            @Size(max = 32) String mid,
            @Size(max = 255) String uname,
            @Min(0) @Max(6) Integer levelMin,
            @Min(0) @Max(6) Integer levelMax,
            Boolean unknownLevelOnly,
            Instant ctimeFrom,
            Instant ctimeBefore,
            @Size(max = 19) String rpid,
            @Size(max = 19) String parentRpid,
            CommentReplyScope replyScope) {

        public CommentFilter toDomain() {
            return new CommentFilter(
                    keyword,
                    mid,
                    uname,
                    levelMin,
                    levelMax,
                    Boolean.TRUE.equals(unknownLevelOnly),
                    ctimeFrom,
                    ctimeBefore,
                    optionalId(rpid, "rpid"),
                    optionalId(parentRpid, "parentRpid"),
                    replyScope);
        }

        private Long optionalId(String value, String field) {
            return value == null || value.isBlank() ? null : TaskController.parseId(value, field);
        }
    }

    public record CommentSearchPage(
            List<TaskDtos.Comment> items,
            String nextCursor,
            Long total) {

        public CommentSearchPage {
            items = List.copyOf(items);
        }
    }
}
