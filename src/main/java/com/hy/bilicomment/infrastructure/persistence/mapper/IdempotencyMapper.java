package com.hy.bilicomment.infrastructure.persistence.mapper;

import com.hy.bilicomment.infrastructure.persistence.entity.IdempotencyRow;
import java.time.Instant;
import java.util.Optional;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface IdempotencyMapper {

    String COLUMNS = """
            idempotency_request_id, request_scope, idempotency_key, request_hash,
            state, resource_type, resource_id, expires_at
            """;

    @Select("INSERT INTO app.idempotency_request ("
            + "request_scope, idempotency_key, request_hash, expires_at)"
            + " VALUES (#{scope}, #{key}, #{hash}, #{expiresAt})"
            + " ON CONFLICT (request_scope, idempotency_key) DO NOTHING"
            + " RETURNING " + COLUMNS)
    Optional<IdempotencyRow> tryClaim(
            @Param("scope") String scope,
            @Param("key") String key,
            @Param("hash") String hash,
            @Param("expiresAt") Instant expiresAt);

    @Select("SELECT " + COLUMNS + " FROM app.idempotency_request"
            + " WHERE request_scope = #{scope} AND idempotency_key = #{key}")
    Optional<IdempotencyRow> find(@Param("scope") String scope, @Param("key") String key);

    @Delete("DELETE FROM app.idempotency_request"
            + " WHERE request_scope = #{scope} AND idempotency_key = #{key}"
            + " AND expires_at <= #{now}")
    int releaseExpired(
            @Param("scope") String scope,
            @Param("key") String key,
            @Param("now") Instant now);

    @Update("UPDATE app.idempotency_request"
            + " SET state = 'COMPLETED', response_status = 201,"
            + " resource_type = #{resourceType}, resource_id = #{resourceId},"
            + " completed_at = clock_timestamp()"
            + " WHERE idempotency_request_id = #{id} AND state = 'PROCESSING'")
    int complete(
            @Param("id") long id,
            @Param("resourceType") String resourceType,
            @Param("resourceId") String resourceId);

    @Delete("DELETE FROM app.idempotency_request"
            + " WHERE idempotency_request_id = #{id} AND state = 'PROCESSING'")
    int release(long id);
}
