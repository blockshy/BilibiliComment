package com.hy.bilicomment.application.credential;

import com.hy.bilicomment.application.event.TaskEventPublisher;
import com.hy.bilicomment.domain.error.DomainException;
import com.hy.bilicomment.infrastructure.bilibili.BilibiliClient;
import com.hy.bilicomment.infrastructure.bilibili.BilibiliCredential;
import com.hy.bilicomment.infrastructure.persistence.entity.CredentialRow;
import com.hy.bilicomment.infrastructure.persistence.mapper.CredentialMapper;
import com.hy.bilicomment.infrastructure.security.CredentialCipher;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CredentialService {

    private final CredentialMapper credentialMapper;
    private final CredentialCipher credentialCipher;
    private final BilibiliClient bilibiliClient;
    private final TaskEventPublisher eventPublisher;

    public CredentialService(
            CredentialMapper credentialMapper,
            CredentialCipher credentialCipher,
            BilibiliClient bilibiliClient,
            TaskEventPublisher eventPublisher) {
        this.credentialMapper = credentialMapper;
        this.credentialCipher = credentialCipher;
        this.bilibiliClient = bilibiliClient;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly = true)
    public List<CredentialSummary> list() {
        return credentialMapper.findAll().stream().map(this::summary).toList();
    }

    @Transactional
    public CredentialSummary replaceSecret(long credentialId, String cookie) {
        require(credentialId);
        String encrypted = credentialCipher.encrypt(cookie);
        if (credentialMapper.updateSecret(credentialId, encrypted) != 1) {
            throw new DomainException("CREDENTIAL_NOT_FOUND", "凭据不存在");
        }
        eventPublisher.publish(null, null, "system.updated", "凭据已更新", java.util.Map.of(
                "credentialId", Long.toString(credentialId)));
        return summary(require(credentialId));
    }

    public CredentialSummary validate(long credentialId) {
        CredentialRow row = require(credentialId);
        BilibiliCredential credential = decrypt(row);
        boolean valid;
        try {
            valid = bilibiliClient.validateCredential(credential);
        } catch (RuntimeException exception) {
            credentialMapper.updateValidation(
                    credentialId, row.secretVersion(), "INVALID", "验证请求失败");
            throw exception;
        }
        if (credentialMapper.updateValidation(
                credentialId,
                row.secretVersion(),
                valid ? "VALID" : "EXPIRED",
                valid ? "登录状态有效" : "登录状态已失效") != 1) {
            throw new DomainException(
                    "CREDENTIAL_CHANGED_DURING_VALIDATION",
                    "凭据在验证期间已更新，请重新验证");
        }
        return summary(require(credentialId));
    }

    @Transactional(readOnly = true)
    public BilibiliCredential resolve(Long credentialId, boolean required) {
        if (credentialId == null) {
            if (required) {
                throw new DomainException("CREDENTIAL_REQUIRED", "该任务必须选择 Bilibili 凭据");
            }
            return null;
        }
        return decrypt(require(credentialId));
    }

    private CredentialRow require(long credentialId) {
        return credentialMapper.findById(credentialId)
                .orElseThrow(() -> new DomainException("CREDENTIAL_NOT_FOUND", "凭据不存在"));
    }

    private BilibiliCredential decrypt(CredentialRow row) {
        if (!row.enabled() || row.encryptedCookie() == null) {
            throw new DomainException("CREDENTIAL_UNCONFIGURED", "所选凭据尚未配置");
        }
        return new BilibiliCredential(
                row.credentialProfileId(),
                credentialCipher.decrypt(row.encryptedCookie()));
    }

    private CredentialSummary summary(CredentialRow row) {
        return new CredentialSummary(
                Long.toString(row.credentialProfileId()),
                row.credentialKey(),
                row.displayName(),
                row.enabled(),
                row.validationStatus(),
                row.lastValidatedAt(),
                row.secretVersion(),
                row.updatedAt());
    }

    public record CredentialSummary(
            String id,
            String key,
            String name,
            boolean enabled,
            String validationStatus,
            java.time.Instant lastValidatedAt,
            int secretVersion,
            java.time.Instant updatedAt) {}
}
