package com.hy.bilicomment.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hy.bilicomment.config.AppProperties;
import com.hy.bilicomment.domain.error.DomainException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class CredentialCipherTests {

    @Test
    void encryptsWithRandomIvAndDecrypts() {
        AppProperties properties = propertiesWithKey("0123456789abcdef0123456789abcdef");
        CredentialCipher cipher = new CredentialCipher(properties);

        String first = cipher.encrypt("SESSDATA=secret; bili_jct=csrf");
        String second = cipher.encrypt("SESSDATA=secret; bili_jct=csrf");

        assertThat(first).startsWith("v1:").isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo("SESSDATA=secret; bili_jct=csrf");
    }

    @Test
    void refusesMissingOrWrongLengthKeys() {
        AppProperties missing = new AppProperties();
        assertThatThrownBy(() -> new CredentialCipher(missing).encrypt("cookie"))
                .isInstanceOf(DomainException.class)
                .extracting(exception -> ((DomainException) exception).getCode())
                .isEqualTo("CREDENTIAL_KEY_MISSING");

        AppProperties shortKey = propertiesWithKey("short");
        assertThatThrownBy(() -> new CredentialCipher(shortKey).encrypt("cookie"))
                .isInstanceOf(DomainException.class)
                .extracting(exception -> ((DomainException) exception).getCode())
                .isEqualTo("CREDENTIAL_KEY_INVALID");
    }

    private AppProperties propertiesWithKey(String rawKey) {
        AppProperties properties = new AppProperties();
        properties.getCrypto().setCredentialKey(
                Base64.getEncoder().encodeToString(rawKey.getBytes(StandardCharsets.UTF_8)));
        return properties;
    }
}
