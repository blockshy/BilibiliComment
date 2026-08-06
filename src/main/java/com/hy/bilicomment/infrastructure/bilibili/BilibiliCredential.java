package com.hy.bilicomment.infrastructure.bilibili;

public record BilibiliCredential(long id, String cookie) {

    public BilibiliCredential {
        if (id <= 0) {
            throw new IllegalArgumentException("credential id must be positive");
        }
        if (cookie == null || cookie.isBlank()) {
            throw new IllegalArgumentException("credential cookie is required");
        }
    }
}
