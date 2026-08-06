package com.hy.bilicomment.infrastructure.bilibili;

import com.hy.bilicomment.domain.task.SourceType;

public record DiscoveredContent(
        SourceType sourceType,
        String sourceId,
        String title,
        String oid,
        String commentType) {}
