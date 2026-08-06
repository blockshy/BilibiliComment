package com.hy.bilicomment.interfaces.web.dto;

import java.util.List;

public record ApiPage<T>(List<T> items, String nextCursor, long total) {

    public ApiPage {
        items = List.copyOf(items);
    }
}
