package com.hy.bilicomment.application.idempotency;

import com.hy.bilicomment.domain.error.DomainException;
import com.hy.bilicomment.infrastructure.persistence.entity.IdempotencyRow;
import com.hy.bilicomment.infrastructure.persistence.mapper.IdempotencyMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdempotencyService {

    private final IdempotencyMapper mapper;
    private final Clock clock;

    public IdempotencyService(IdempotencyMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Claim claim(String scope, String key, String canonicalRequest) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new DomainException("IDEMPOTENCY_KEY_INVALID", "Idempotency-Key 必须为 1 至 128 个字符");
        }
        String hash = sha256(canonicalRequest);
        mapper.releaseExpired(scope, key, clock.instant());
        IdempotencyRow claimed = mapper.tryClaim(
                        scope,
                        key,
                        hash,
                        clock.instant().plus(Duration.ofHours(24)))
                .orElse(null);
        if (claimed != null) {
            return new Claim(claimed.idempotencyRequestId(), false, null);
        }
        IdempotencyRow existing = mapper.find(scope, key)
                .orElseThrow(() -> new DomainException("IDEMPOTENCY_RACE", "幂等请求状态暂不可用"));
        if (!existing.requestHash().equals(hash)) {
            throw new DomainException("IDEMPOTENCY_KEY_REUSED", "该 Idempotency-Key 已用于不同请求");
        }
        if ("COMPLETED".equals(existing.state()) && existing.resourceId() != null) {
            return new Claim(existing.idempotencyRequestId(), true, existing.resourceId());
        }
        throw new DomainException("IDEMPOTENCY_IN_PROGRESS", "相同请求正在处理中，请稍后查询");
    }

    @Transactional
    public void complete(long claimId, String resourceId) {
        complete(claimId, "TASK", resourceId);
    }

    @Transactional
    public void complete(long claimId, String resourceType, String resourceId) {
        if (resourceType == null || !resourceType.matches("^[A-Z][A-Z0-9_]{0,31}$")) {
            throw new DomainException("IDEMPOTENCY_RESOURCE_INVALID", "幂等请求资源类型无效");
        }
        if (mapper.complete(claimId, resourceType, resourceId) != 1) {
            throw new DomainException("IDEMPOTENCY_COMPLETION_FAILED", "幂等请求结果无法保存");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(long claimId) {
        mapper.release(claimId);
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record Claim(long id, boolean replay, String resourceId) {}
}
