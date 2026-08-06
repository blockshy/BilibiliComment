package com.hy.bilicomment.infrastructure.bilibili;

import com.hy.bilicomment.domain.comment.CommentRecord;
import java.util.List;

public record CommentPage(List<CommentRecord> comments, String nextCursor, boolean end) {

    public CommentPage {
        comments = List.copyOf(comments);
    }
}
