package com.hy.bilicomment.infrastructure.persistence.mapper;

import com.hy.bilicomment.infrastructure.persistence.entity.CredentialRow;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface CredentialMapper {

    String COLUMNS = """
            credential_profile_id, credential_key, display_name, enabled,
            encrypted_cookie, validation_status, last_validated_at,
            last_validation_message, secret_version, version, created_at, updated_at
            """;

    @Select("SELECT " + COLUMNS
            + " FROM app.credential_profile ORDER BY credential_profile_id")
    List<CredentialRow> findAll();

    @Select("SELECT " + COLUMNS
            + " FROM app.credential_profile WHERE credential_profile_id = #{credentialProfileId}")
    Optional<CredentialRow> findById(long credentialProfileId);

    @Update("""
            UPDATE app.credential_profile
               SET encrypted_cookie = #{encryptedCookie},
                   enabled = true,
                   validation_status = 'UNKNOWN',
                   last_validation_message = NULL,
                   secret_version = secret_version + 1,
                   version = version + 1
             WHERE credential_profile_id = #{credentialProfileId}
            """)
    int updateSecret(
            @Param("credentialProfileId") long credentialProfileId,
            @Param("encryptedCookie") String encryptedCookie);

    @Update("""
            UPDATE app.credential_profile
               SET validation_status = #{status},
                   last_validated_at = clock_timestamp(),
                   last_validation_message = #{message},
                   version = version + 1
             WHERE credential_profile_id = #{credentialProfileId}
               AND secret_version = #{expectedSecretVersion}
            """)
    int updateValidation(
            @Param("credentialProfileId") long credentialProfileId,
            @Param("expectedSecretVersion") int expectedSecretVersion,
            @Param("status") String status,
            @Param("message") String message);
}
