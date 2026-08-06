package com.hy.bilicomment.domain.comment;

import java.time.Instant;

public record CommentFilter(
        String keyword,
        String mid,
        String uname,
        Integer levelMin,
        Integer levelMax,
        boolean unknownLevelOnly,
        Instant ctimeFrom,
        Instant ctimeBefore,
        Long rpid,
        Long parentRpid,
        CommentReplyScope replyScope) {

    public CommentFilter {
        replyScope = replyScope == null ? CommentReplyScope.ALL : replyScope;
    }
}
