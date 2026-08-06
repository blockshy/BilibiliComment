package com.hy.bilicomment.interfaces.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hy.bilicomment.application.comment.CommentSearchService;
import com.hy.bilicomment.domain.comment.CommentFilter;
import com.hy.bilicomment.domain.comment.CommentSort;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class CommentControllerValidationTests {

    @Mock private CommentSearchService searchService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new CommentController(searchService))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void rejectsOversizedPageCursorAndCommentIds() throws Exception {
        assertValidation("{\"filter\":{},\"limit\":101}", "limit");
        assertValidation(
                "{\"filter\":{},\"cursor\":\"" + "x".repeat(2049) + "\"}",
                "cursor");
        assertValidation(
                "{\"filter\":{\"rpid\":\"12345678901234567890\"}}",
                "filter.rpid");
    }

    @Test
    void appliesDocumentedDefaultsForAnEmptyFilter() throws Exception {
        when(searchService.search(
                        anyLong(),
                        any(CommentFilter.class),
                        isNull(),
                        isNull(),
                        anyInt(),
                        anyBoolean()))
                .thenReturn(new CommentSearchService.SearchResult(
                        List.of(),
                        null,
                        null,
                        0L,
                        new CommentFilter(null, null, null, null, null, false,
                                null, null, null, null, null),
                        CommentSort.CTIME_DESC));

        mockMvc.perform(post("/api/v1/tasks/17/comments/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.nextCursor").value(nullValue()))
                .andExpect(jsonPath("$.total").value(nullValue()));
    }

    private void assertValidation(String json, String field) throws Exception {
        mockMvc.perform(post("/api/v1/tasks/17/comments/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors['" + field + "']").exists());
    }
}
