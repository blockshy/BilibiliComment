package com.hy.bilicomment.infrastructure.persistence.mapper;

import com.hy.bilicomment.infrastructure.persistence.entity.EventRow;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface EventMapper {

    String COLUMNS = """
            event_sequence, task_id, execution_id, event_type, message,
            payload::text AS payload_json, created_at
            """;

    @Select("INSERT INTO app.task_event (task_id, execution_id, event_type, message, payload)"
            + " VALUES (#{taskId}, #{executionId}, #{eventType}, #{message}, CAST(#{payloadJson} AS jsonb))"
            + " RETURNING " + COLUMNS)
    EventRow insert(
            @Param("taskId") Long taskId,
            @Param("executionId") Long executionId,
            @Param("eventType") String eventType,
            @Param("message") String message,
            @Param("payloadJson") String payloadJson);

    @Select("SELECT " + COLUMNS + " FROM app.task_event"
            + " WHERE event_sequence > #{afterSequence}"
            + " ORDER BY event_sequence LIMIT #{limit}")
    List<EventRow> findAfter(
            @Param("afterSequence") long afterSequence,
            @Param("limit") int limit);

    @Select("SELECT COALESCE(max(event_sequence), 0) FROM app.task_event")
    long findLatestSequence();
}
