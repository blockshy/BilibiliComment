package com.hy.bilicomment.infrastructure.bilibili;

import com.hy.bilicomment.domain.task.SourceType;

public record ResolvedContent(
        SourceType sourceType,
        String sourceId,
        String oid,
        String commentType,
        String title) {}
