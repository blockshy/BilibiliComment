package com.hy.bilicomment.application.comment;

import com.hy.bilicomment.config.AppProperties;
import com.hy.bilicomment.domain.comment.CommentFilter;
import com.hy.bilicomment.domain.comment.CommentSort;
import com.hy.bilicomment.domain.error.DomainException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class CommentCursorCodec {

    private static final int VERSION = 1;

    private final byte[] signingKey;
    private final AppProperties.Cursor properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CommentCursorCodec(AppProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.properties = properties.getCursor();
        this.signingKey = decodeKey(this.properties.getSigningKey());
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public String fingerprint(CommentFilter filter, CommentSort sort) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, filter.keyword());
        append(canonical, filter.mid());
        append(canonical, filter.uname());
        append(canonical, filter.levelMin());
        append(canonical, filter.levelMax());
        append(canonical, filter.unknownLevelOnly());
        append(canonical, filter.ctimeFrom());
        append(canonical, filter.ctimeBefore());
        append(canonical, filter.rpid());
        append(canonical, filter.parentRpid());
        append(canonical, filter.replyScope());
        append(canonical, sort);
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public String encode(
            long taskId,
            String filterHash,
            CommentSort sort,
            long snapshotMaxCommentId,
            Instant afterTime,
            long afterRpid) {
        CursorPayload payload = new CursorPayload(
                VERSION,
                Long.toString(taskId),
                filterHash,
                sort,
                Long.toString(snapshotMaxCommentId),
                afterTime,
                Long.toString(afterRpid),
                clock.instant().plus(properties.getTtl()));
        try {
            byte[] serialized = objectMapper.writeValueAsBytes(payload);
            String body = Base64.getUrlEncoder().withoutPadding().encodeToString(serialized);
            String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(sign(serialized));
            return body + '.' + signature;
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to encode comment cursor", exception);
        }
    }

    public CursorState decode(
            String token,
            long expectedTaskId,
            String expectedFilterHash,
            CommentSort expectedSort) {
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2) {
                throw invalid();
            }
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[0]);
            byte[] providedSignature = Base64.getUrlDecoder().decode(parts[1]);
            if (!MessageDigest.isEqual(sign(payloadBytes), providedSignature)) {
                throw invalid();
            }
            CursorPayload payload = objectMapper.readValue(payloadBytes, CursorPayload.class);
            long taskId = Long.parseLong(payload.taskId());
            long snapshot = Long.parseLong(payload.snapshotMaxCommentId());
            long rpid = Long.parseLong(payload.afterRpid());
            if (payload.version() != VERSION
                    || taskId != expectedTaskId
                    || !expectedFilterHash.equals(payload.filterHash())
                    || payload.sort() != expectedSort
                    || snapshot < 0
                    || rpid <= 0
                    || payload.afterTime() == null
                    || payload.expiresAt() == null
                    || !payload.expiresAt().isAfter(clock.instant())) {
                throw invalid();
            }
            return new CursorState(snapshot, payload.afterTime(), rpid);
        } catch (DomainException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    private byte[] decodeKey(String configured) {
        try {
            byte[] decoded = Base64.getDecoder().decode(configured == null ? "" : configured);
            if (decoded.length != 32) {
                throw new IllegalStateException("CURSOR_SIGNING_KEY must decode to exactly 32 bytes");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("CURSOR_SIGNING_KEY must be valid Base64", exception);
        }
    }

    private void append(StringBuilder target, Object value) {
        String text = value == null ? "" : value.toString();
        target.append(text.length()).append(':').append(text).append('|');
    }

    private DomainException invalid() {
        return new DomainException("CURSOR_INVALID", "评论分页游标无效或已过期");
    }

    private record CursorPayload(
            int version,
            String taskId,
            String filterHash,
            CommentSort sort,
            String snapshotMaxCommentId,
            Instant afterTime,
            String afterRpid,
            Instant expiresAt) {}

    public record CursorState(long snapshotMaxCommentId, Instant afterTime, long afterRpid) {}
}
