package com.hy.bilicomment.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

public interface OperationAuditMapper {

    @Insert("""
            INSERT INTO app.operation_audit (
                actor, action, target_type, target_id, outcome,
                trace_id, source_ip, details
            ) VALUES (
                #{actor}, #{action}, #{targetType}, #{targetId}, #{outcome},
                #{traceId}, CAST(#{sourceIp} AS inet), '{}'::jsonb
            )
            """)
    int insert(
            @Param("actor") String actor,
            @Param("action") String action,
            @Param("targetType") String targetType,
            @Param("targetId") String targetId,
            @Param("outcome") String outcome,
            @Param("traceId") String traceId,
            @Param("sourceIp") String sourceIp);
}
