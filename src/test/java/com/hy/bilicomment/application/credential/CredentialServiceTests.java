package com.hy.bilicomment.application.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hy.bilicomment.application.event.TaskEventPublisher;
import com.hy.bilicomment.domain.error.DomainException;
import com.hy.bilicomment.infrastructure.bilibili.BilibiliClient;
import com.hy.bilicomment.infrastructure.persistence.entity.CredentialRow;
import com.hy.bilicomment.infrastructure.persistence.mapper.CredentialMapper;
import com.hy.bilicomment.infrastructure.security.CredentialCipher;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CredentialServiceTests {

    @Mock private CredentialMapper credentialMapper;
    @Mock private CredentialCipher credentialCipher;
    @Mock private BilibiliClient bilibiliClient;
    @Mock private TaskEventPublisher eventPublisher;
    @InjectMocks private CredentialService credentialService;

    @Test
    void doesNotApplyAnOldSecretValidationResultToANewerSecretVersion() {
        CredentialRow row = credential(7);
        when(credentialMapper.findById(9L)).thenReturn(Optional.of(row));
        when(credentialCipher.decrypt("encrypted-cookie")).thenReturn("SESSDATA=fixture");
        when(bilibiliClient.validateCredential(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(credentialMapper.updateValidation(9L, 7, "VALID", "登录状态有效"))
                .thenReturn(0);

        assertThatThrownBy(() -> credentialService.validate(9L))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("CREDENTIAL_CHANGED_DURING_VALIDATION"));

        verify(credentialMapper).updateValidation(9L, 7, "VALID", "登录状态有效");
    }

    private CredentialRow credential(int secretVersion) {
        Instant now = Instant.parse("2026-07-14T00:00:00Z");
        return new CredentialRow(
                9L,
                "fixture",
                "Fixture",
                true,
                "encrypted-cookie",
                "UNKNOWN",
                null,
                null,
                secretVersion,
                0,
                now,
                now);
    }
}
