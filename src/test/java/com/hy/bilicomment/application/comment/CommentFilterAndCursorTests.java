package com.hy.bilicomment.application.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hy.bilicomment.config.AppProperties;
import com.hy.bilicomment.domain.comment.CommentFilter;
import com.hy.bilicomment.domain.comment.CommentReplyScope;
import com.hy.bilicomment.domain.comment.CommentSort;
import com.hy.bilicomment.domain.error.DomainException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class CommentFilterAndCursorTests {

    private static final Instant NOW = Instant.parse("2026-07-14T00:00:00Z");
    private static final String SIGNING_KEY =
            "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=";

    private final CommentFilterService filterService = new CommentFilterService();

    @Test
    void normalizesTextAndDefaultsReplyScope() {
        CommentFilter normalized = filterService.normalize(new CommentFilter(
                "  三连支持  ",
                " 123 ",
                "  测试用户  ",
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null));

        assertThat(normalized.keyword()).isEqualTo("三连支持");
        assertThat(normalized.mid()).isEqualTo("123");
        assertThat(normalized.uname()).isEqualTo("测试用户");
        assertThat(normalized.replyScope()).isEqualTo(CommentReplyScope.ALL);
    }

    @Test
    void rejectsBroadShortFuzzySearchButAllowsASelectiveBoundary() {
        CommentFilter broad = new CommentFilter(
                "ab", null, null, null, null, false,
                null, null, null, null, CommentReplyScope.ALL);

        assertDomainCode(() -> filterService.normalize(broad), "COMMENT_FILTER_TOO_BROAD");

        CommentFilter selective = new CommentFilter(
                "ab", null, null, null, null, false,
                NOW.minusSeconds(60), null, null, null, CommentReplyScope.ALL);
        assertThat(filterService.normalize(selective).keyword()).isEqualTo("ab");
    }

    @Test
    void signsCursorAndBindsItToTaskFilterSortSnapshotAndExpiry() {
        AppProperties properties = new AppProperties();
        properties.getCursor().setSigningKey(SIGNING_KEY);
        CommentCursorCodec codec = new CommentCursorCodec(
                properties,
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        CommentFilter filter = filterService.normalize(new CommentFilter(
                "关键字", null, null, null, null, false,
                null, null, null, null, CommentReplyScope.ALL));
        String fingerprint = codec.fingerprint(filter, CommentSort.CTIME_DESC);
        Instant boundary = Instant.parse("2026-07-13T23:59:00Z");
        String cursor = codec.encode(
                17L,
                fingerprint,
                CommentSort.CTIME_DESC,
                99L,
                boundary,
                88L);

        assertThat(codec.decode(cursor, 17L, fingerprint, CommentSort.CTIME_DESC))
                .isEqualTo(new CommentCursorCodec.CursorState(99L, boundary, 88L));
        assertDomainCode(
                () -> codec.decode(cursor, 18L, fingerprint, CommentSort.CTIME_DESC),
                "CURSOR_INVALID");
        assertDomainCode(
                () -> codec.decode(cursor, 17L, "0".repeat(64), CommentSort.CTIME_DESC),
                "CURSOR_INVALID");
        assertDomainCode(
                () -> codec.decode(cursor, 17L, fingerprint, CommentSort.CTIME_ASC),
                "CURSOR_INVALID");

        String tampered = cursor.substring(0, cursor.length() - 1)
                + (cursor.endsWith("A") ? "B" : "A");
        assertDomainCode(
                () -> codec.decode(tampered, 17L, fingerprint, CommentSort.CTIME_DESC),
                "CURSOR_INVALID");

        CommentCursorCodec expiredCodec = new CommentCursorCodec(
                properties,
                new ObjectMapper(),
                Clock.fixed(NOW.plusSeconds(3 * 60 * 60), ZoneOffset.UTC));
        assertDomainCode(
                () -> expiredCodec.decode(cursor, 17L, fingerprint, CommentSort.CTIME_DESC),
                "CURSOR_INVALID");
    }

    private void assertDomainCode(Runnable operation, String code) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(code));
    }
}
