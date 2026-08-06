package com.hy.bilicomment.infrastructure.bilibili;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hy.bilicomment.domain.comment.CommentRecord;
import com.hy.bilicomment.domain.error.DomainException;
import com.hy.bilicomment.domain.task.SourceType;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class BilibiliClientTests {

    private static final BilibiliCredential CREDENTIAL =
            new BilibiliCredential(42L, "SESSDATA=fixture-only-not-a-real-cookie");
    private static final Instant FIXED_INSTANT = Instant.ofEpochSecond(1_700_000_000L);
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    @Mock
    private BilibiliHttpClient httpClient;

    private BilibiliClient client;

    @BeforeEach
    void setUp() {
        client = new BilibiliClient(
                httpClient,
                new ObjectMapper(),
                new WbiSigner(FIXED_CLOCK),
                FIXED_CLOCK);
    }

    @Test
    void resolvesVideoFromTheViewApi() {
        when(httpClient.get(any(URI.class), same(CREDENTIAL)))
                .thenReturn(BilibiliJsonFixtures.VIDEO_VIEW);

        ResolvedContent resolved = client.resolveVideo("BV1Fixture", CREDENTIAL);

        assertThat(resolved).isEqualTo(new ResolvedContent(
                SourceType.VIDEO,
                "BV1Fixture",
                "987654321",
                "1",
                "Fixture 视频"));
        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        verify(httpClient).get(uri.capture(), same(CREDENTIAL));
        assertThat(uri.getValue().getPath()).isEqualTo("/x/web-interface/view");
        assertThat(uri.getValue().getRawQuery()).isEqualTo("bvid=BV1Fixture");
    }

    @Test
    void resolvesRegularAndType17Dynamics() {
        when(httpClient.get(any(URI.class), same(CREDENTIAL)))
                .thenReturn(
                        BilibiliJsonFixtures.DYNAMIC_DETAIL,
                        BilibiliJsonFixtures.DYNAMIC_TYPE_17_DETAIL);

        ResolvedContent regular = client.resolveDynamic("20001", CREDENTIAL);
        ResolvedContent type17 = client.resolveDynamic("20002", CREDENTIAL);

        assertThat(regular).isEqualTo(new ResolvedContent(
                SourceType.DYNAMIC,
                "20001",
                "7654321",
                "11",
                "Fixture 动态标题"));
        assertThat(type17).isEqualTo(new ResolvedContent(
                SourceType.DYNAMIC,
                "20002",
                "20002",
                "17",
                "Fixture UP"));
        ArgumentCaptor<URI> uris = ArgumentCaptor.forClass(URI.class);
        verify(httpClient, times(2)).get(uris.capture(), same(CREDENTIAL));
        assertThat(uris.getAllValues())
                .extracting(URI::getRawQuery)
                .containsExactly("id=20001", "id=20002");
    }

    @Test
    void classifiesTheSupportedCreatorFirstPageAndSkipsUnknownTypes() {
        when(httpClient.get(any(URI.class), same(CREDENTIAL)))
                .thenReturn(BilibiliJsonFixtures.CREATOR_FIRST_PAGE);

        List<DiscoveredContent> discovered = client.discoverCreatorContent("fixture-uid", CREDENTIAL);

        assertThat(discovered).hasSize(5);
        assertThat(discovered)
                .extracting(DiscoveredContent::sourceType)
                .containsExactly(
                        SourceType.VIDEO,
                        SourceType.DYNAMIC,
                        SourceType.DYNAMIC,
                        SourceType.DYNAMIC,
                        SourceType.DYNAMIC);
        assertThat(discovered)
                .extracting(DiscoveredContent::sourceId)
                .containsExactly("BV1FixtureVideo", "20001", "20002", "20003", "20004")
                .doesNotContain("ignored-unknown");
        assertThat(discovered.getFirst())
                .isEqualTo(new DiscoveredContent(
                        SourceType.VIDEO,
                        "BV1FixtureVideo",
                        "Fixture 投稿",
                        "10001",
                        "1"));
    }

    @Test
    void readsOnlyTopLevelAndOneEmbeddedReplyLevel() {
        stubNavigationAndCommentPage();
        ResolvedContent resolved = new ResolvedContent(
                SourceType.VIDEO, "BV1Fixture", "987654321", "1", "Fixture 视频");

        CommentPage page = client.fetchCommentPage(resolved, null, CREDENTIAL);

        assertThat(page.comments())
                .extracting(CommentRecord::rpid)
                .containsExactly(1001L, 1002L)
                .doesNotContain(1003L, 1004L);
        assertThat(page.comments().getFirst())
                .extracting(
                        CommentRecord::mid,
                        CommentRecord::uname,
                        CommentRecord::currentLevel,
                        CommentRecord::content,
                        CommentRecord::ctime,
                        CommentRecord::parentRpid)
                .containsExactly(
                        "fixture-mid-1",
                        "Fixture 用户",
                        6,
                        "顶层评论",
                        Instant.ofEpochSecond(1_700_000_000L),
                        null);
        assertThat(page.comments().get(1).parentRpid()).isEqualTo(1001L);
        assertThat(page.nextCursor()).isEqualTo("fixture-next-cursor");
        assertThat(page.end()).isFalse();
    }

    @Test
    void mapsNonZeroBilibiliCodeToAStableDomainError() {
        when(httpClient.get(any(URI.class), same(CREDENTIAL)))
                .thenReturn(BilibiliJsonFixtures.API_ERROR);

        assertThatThrownBy(() -> client.resolveVideo("BV1Fixture", CREDENTIAL))
                .isInstanceOfSatisfying(DomainException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("BILIBILI_API_ERROR");
                    assertThat(exception.getMessage()).contains("-101", "Fixture 凭据未登录");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-json", "{"})
    void rejectsEmptyOrMalformedJson(String body) {
        when(httpClient.get(any(URI.class), same(CREDENTIAL))).thenReturn(body);

        assertThatThrownBy(() -> client.resolveVideo("BV1Fixture", CREDENTIAL))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("BILIBILI_RESPONSE_INVALID"));
    }

    @Test
    void rejectsSuccessfulResponsesWithoutData() {
        when(httpClient.get(any(URI.class), same(CREDENTIAL)))
                .thenReturn(BilibiliJsonFixtures.DATA_MISSING);

        assertThatThrownBy(() -> client.resolveVideo("BV1Fixture", CREDENTIAL))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("BILIBILI_DATA_MISSING"));
    }

    @Test
    void signsEveryCommentRequestEvenWhileWbiKeysRemainCached() {
        MutableClock signerClock = new MutableClock(FIXED_INSTANT, ZoneOffset.UTC);
        BilibiliClient signingClient = new BilibiliClient(
                httpClient,
                new ObjectMapper(),
                new WbiSigner(signerClock),
                FIXED_CLOCK);
        List<URI> commentRequests = new ArrayList<>();
        when(httpClient.get(any(URI.class), same(CREDENTIAL))).thenAnswer(invocation -> {
            URI uri = invocation.getArgument(0);
            if ("/x/web-interface/nav".equals(uri.getPath())) {
                return BilibiliJsonFixtures.NAV_WBI_KEYS;
            }
            if ("/x/v2/reply/wbi/main".equals(uri.getPath())) {
                commentRequests.add(uri);
                return BilibiliJsonFixtures.COMMENT_PAGE;
            }
            throw new AssertionError("unexpected fixture request: " + uri);
        });
        ResolvedContent resolved = new ResolvedContent(
                SourceType.VIDEO, "BV1Fixture", "987654321", "1", "Fixture 视频");

        signingClient.fetchCommentPage(resolved, "same-cursor", CREDENTIAL);
        signerClock.advance(Duration.ofSeconds(1));
        signingClient.fetchCommentPage(resolved, "same-cursor", CREDENTIAL);

        assertThat(commentRequests).hasSize(2);
        assertThat(commentRequests.get(0).getRawQuery())
                .contains("wts=1700000000", "w_rid=")
                .isNotEqualTo(commentRequests.get(1).getRawQuery());
        assertThat(commentRequests.get(1).getRawQuery()).contains("wts=1700000001", "w_rid=");
        verify(httpClient, times(3)).get(any(URI.class), same(CREDENTIAL));
    }

    private void stubNavigationAndCommentPage() {
        when(httpClient.get(any(URI.class), same(CREDENTIAL))).thenAnswer(invocation -> {
            URI uri = invocation.getArgument(0);
            return switch (uri.getPath()) {
                case "/x/web-interface/nav" -> BilibiliJsonFixtures.NAV_WBI_KEYS;
                case "/x/v2/reply/wbi/main" -> BilibiliJsonFixtures.COMMENT_PAGE;
                default -> throw new AssertionError("unexpected fixture request: " + uri);
            };
        });
    }

    private static final class MutableClock extends Clock {

        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId targetZone) {
            return new MutableClock(instant, targetZone);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
