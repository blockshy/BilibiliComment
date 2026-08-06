package com.hy.bilicomment.domain.comment;

import java.time.Instant;

public record CommentRecord(
        long rpid,
        String mid,
        String uname,
        String avatar,
        Integer currentLevel,
        String content,
        Instant ctime,
        Long parentRpid) {

    public CommentRecord {
        if (rpid <= 0) {
            throw new IllegalArgumentException("rpid must be positive");
        }
        if (mid == null || mid.isBlank()) {
            throw new IllegalArgumentException("mid is required");
        }
        if (ctime == null) {
            throw new IllegalArgumentException("ctime is required");
        }
    }
}
