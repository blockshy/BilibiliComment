package com.hy.bilicomment.interfaces.web;

import com.hy.bilicomment.application.event.SseEventService;
import com.hy.bilicomment.domain.error.DomainException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/events")
public class EventController {

    private final SseEventService eventService;

    public EventController(SseEventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            @RequestParam(required = false) String afterSequence,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");
        return eventService.subscribe(parseSequence(
                afterSequence == null || afterSequence.isBlank() ? lastEventId : afterSequence));
    }

    private Long parseSequence(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long sequence = Long.parseLong(value);
            return Math.max(0, sequence);
        } catch (NumberFormatException exception) {
            throw new DomainException("EVENT_ID_INVALID", "Last-Event-ID 无效");
        }
    }
}
