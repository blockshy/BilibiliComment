package com.hy.bilicomment.domain.source;

import com.hy.bilicomment.domain.task.SourceType;

public record NormalizedSource(SourceType type, String id, String canonicalUrl) {}
