package com.hy.bilicomment.application.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hy.bilicomment.domain.error.DomainException;
import com.hy.bilicomment.infrastructure.persistence.entity.IdempotencyRow;
import com.hy.bilicomment.infrastructure.persistence.mapper.IdempotencyMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTests {

    private static final Instant NOW = Instant.parse("2026-07-14T00:00:00Z");
    private static final String SCOPE = "CREATE_TASK";
    private static final String KEY = "fixture-key";
    private static final String REQUEST = "{\"source\":\"BVFixture\"}";

    @Mock
    private IdempotencyMapper mapper;

    private IdempotencyService service;

    @BeforeEach
    void setUp() {
        service = new IdempotencyService(
                mapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void claimsANewRequestForTwentyFourHours() {
        String hash = sha256(REQUEST);
        Instant expiresAt = NOW.plus(Duration.ofHours(24));
        when(mapper.tryClaim(SCOPE, KEY, hash, expiresAt))
                .thenReturn(Optional.of(row(hash, "PROCESSING", null)));

        IdempotencyService.Claim claim = service.claim(SCOPE, KEY, REQUEST);

        assertThat(claim).isEqualTo(new IdempotencyService.Claim(11L, false, null));
        verify(mapper).releaseExpired(SCOPE, KEY, NOW);
        verify(mapper).tryClaim(SCOPE, KEY, hash, expiresAt);
    }

    @Test
    void replaysACompletedRequestWithTheSameCanonicalBody() {
        String hash = sha256(REQUEST);
        when(mapper.tryClaim(SCOPE, KEY, hash, NOW.plus(Duration.ofHours(24))))
                .thenReturn(Optional.empty());
        when(mapper.find(SCOPE, KEY)).thenReturn(Optional.of(row(hash, "COMPLETED", "73")));

        assertThat(service.claim(SCOPE, KEY, REQUEST))
                .isEqualTo(new IdempotencyService.Claim(11L, true, "73"));
    }

    @Test
    void rejectsReuseOfAKeyForADifferentBody() {
        String hash = sha256(REQUEST);
        when(mapper.tryClaim(SCOPE, KEY, hash, NOW.plus(Duration.ofHours(24))))
                .thenReturn(Optional.empty());
        when(mapper.find(SCOPE, KEY))
                .thenReturn(Optional.of(row("0".repeat(64), "COMPLETED", "73")));

        assertDomainCode(
                () -> service.claim(SCOPE, KEY, REQUEST),
                "IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void rejectsASecondCallerWhileTheFirstRequestIsProcessing() {
        String hash = sha256(REQUEST);
        when(mapper.tryClaim(SCOPE, KEY, hash, NOW.plus(Duration.ofHours(24))))
                .thenReturn(Optional.empty());
        when(mapper.find(SCOPE, KEY)).thenReturn(Optional.of(row(hash, "PROCESSING", null)));

        assertDomainCode(
                () -> service.claim(SCOPE, KEY, REQUEST),
                "IDEMPOTENCY_IN_PROGRESS");
    }

    @Test
    void validatesIdempotencyKeyBoundaries() {
        assertDomainCode(() -> service.claim(SCOPE, null, REQUEST), "IDEMPOTENCY_KEY_INVALID");
        assertDomainCode(() -> service.claim(SCOPE, "   ", REQUEST), "IDEMPOTENCY_KEY_INVALID");
        assertDomainCode(
                () -> service.claim(SCOPE, "x".repeat(129), REQUEST),
                "IDEMPOTENCY_KEY_INVALID");
    }

    @Test
    void completesOrReleasesTheOwnedClaim() {
        when(mapper.complete(11L, "TASK", "73")).thenReturn(1);
        when(mapper.complete(13L, "COMMENT_EXPORT", "99")).thenReturn(1);

        service.complete(11L, "73");
        service.complete(13L, "COMMENT_EXPORT", "99");
        service.fail(12L);

        verify(mapper).complete(11L, "TASK", "73");
        verify(mapper).complete(13L, "COMMENT_EXPORT", "99");
        verify(mapper).release(12L);
    }

    @Test
    void rejectsCompletionWhenTheClaimIsNoLongerProcessing() {
        when(mapper.complete(11L, "TASK", "73")).thenReturn(0);

        assertDomainCode(
                () -> service.complete(11L, "73"),
                "IDEMPOTENCY_COMPLETION_FAILED");
    }

    private IdempotencyRow row(String hash, String state, String resourceId) {
        return new IdempotencyRow(
                11L,
                SCOPE,
                KEY,
                hash,
                state,
                resourceId == null ? null : "TASK",
                resourceId,
                NOW.plus(Duration.ofHours(24)));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static void assertDomainCode(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(code));
    }
}
