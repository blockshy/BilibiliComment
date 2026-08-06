package com.hy.bilicomment.infrastructure.security;

import com.hy.bilicomment.config.AppProperties;
import com.hy.bilicomment.domain.error.DomainException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class CredentialCipher {

    private static final String PREFIX = "v1:";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final AppProperties properties;
    private final SecureRandom secureRandom;

    @Autowired
    public CredentialCipher(AppProperties properties) {
        this(properties, new SecureRandom());
    }

    CredentialCipher(AppProperties properties, SecureRandom secureRandom) {
        this.properties = properties;
        this.secureRandom = secureRandom;
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new DomainException("CREDENTIAL_REQUIRED", "Bilibili Cookie 不能为空");
        }
        byte[] iv = new byte[IV_LENGTH];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to encrypt credential", exception);
        }
    }

    public String decrypt(String encoded) {
        if (encoded == null || !encoded.startsWith(PREFIX)) {
            throw new DomainException("CREDENTIAL_FORMAT_INVALID", "凭据密文格式无效");
        }
        byte[] payload;
        try {
            payload = Base64.getDecoder().decode(encoded.substring(PREFIX.length()));
        } catch (IllegalArgumentException exception) {
            throw new DomainException("CREDENTIAL_FORMAT_INVALID", "凭据密文格式无效", exception);
        }
        if (payload.length <= IV_LENGTH) {
            throw new DomainException("CREDENTIAL_FORMAT_INVALID", "凭据密文格式无效");
        }
        byte[] iv = java.util.Arrays.copyOfRange(payload, 0, IV_LENGTH);
        byte[] ciphertext = java.util.Arrays.copyOfRange(payload, IV_LENGTH, payload.length);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new DomainException("CREDENTIAL_DECRYPTION_FAILED", "凭据无法解密，请重新保存", exception);
        }
    }

    private SecretKeySpec key() {
        String configured = properties.getCrypto().getCredentialKey();
        if (configured == null || configured.isBlank()) {
            throw new DomainException("CREDENTIAL_KEY_MISSING", "未配置凭据加密密钥");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(configured);
        } catch (IllegalArgumentException exception) {
            throw new DomainException("CREDENTIAL_KEY_INVALID", "凭据加密密钥必须是 Base64", exception);
        }
        if (decoded.length != 32) {
            throw new DomainException("CREDENTIAL_KEY_INVALID", "凭据加密密钥解码后必须为 32 字节");
        }
        return new SecretKeySpec(decoded, "AES");
    }
}
