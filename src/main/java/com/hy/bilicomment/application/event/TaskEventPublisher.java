package com.hy.bilicomment.application.event;

import com.hy.bilicomment.domain.error.DomainException;
import com.hy.bilicomment.infrastructure.persistence.entity.EventRow;
import com.hy.bilicomment.infrastructure.persistence.mapper.EventMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class TaskEventPublisher {

    private final EventMapper eventMapper;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    public TaskEventPublisher(
            EventMapper eventMapper,
            ObjectMapper objectMapper,
            ApplicationEventPublisher applicationEventPublisher) {
        this.eventMapper = eventMapper;
        this.objectMapper = objectMapper;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Transactional
    public EventRow publish(
            Long taskId,
            Long executionId,
            String eventType,
            String message,
            Object payload) {
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload == null ? java.util.Map.of() : payload);
        } catch (Exception exception) {
            throw new DomainException("EVENT_SERIALIZATION_FAILED", "任务事件无法序列化", exception);
        }
        EventRow event = eventMapper.insert(taskId, executionId, eventType, message, payloadJson);
        applicationEventPublisher.publishEvent(event);
        return event;
    }
}
