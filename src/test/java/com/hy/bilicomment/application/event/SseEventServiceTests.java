package com.hy.bilicomment.application.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hy.bilicomment.config.AppProperties;
import com.hy.bilicomment.infrastructure.persistence.entity.EventRow;
import com.hy.bilicomment.infrastructure.persistence.mapper.EventMapper;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class SseEventServiceTests {

    @Mock
    private EventMapper eventMapper;

    private SseEventService service;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        properties.getEvents().setEmitterTimeout(Duration.ofMinutes(5));
        service = new SseEventService(
                eventMapper,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-07-14T00:00:00Z"), ZoneOffset.UTC),
                properties);
    }

    @Test
    void replaysPersistedEventsStrictlyAfterTheRequestedSequence() {
        EventRow event42 = event(42L, "task.updated", "{\"state\":\"ACTIVE\"}");
        EventRow event43 = event(43L, "execution.updated", "{\"status\":\"RUNNING\"}");
        when(eventMapper.findLatestSequence()).thenReturn(43L);
        when(eventMapper.findAfter(41L, 201)).thenReturn(List.of(event42, event43));

        SseEmitter emitter = service.subscribe(41L);

        verify(eventMapper).findLatestSequence();
        verify(eventMapper).findAfter(41L, 201);
        assertThat(emitter.getTimeout()).isEqualTo(Duration.ofMinutes(5).toMillis());
        assertThat(service.connectionCount()).isEqualTo(1);
        assertThatCode(() -> service.onPersistedEvent(event(
                        44L,
                        "comments.appended",
                        "{\"inserted\":2}")))
                .doesNotThrowAnyException();
    }

    @Test
    void clampsNegativeReplayPositionsToTheBeginning() {
        when(eventMapper.findAfter(0L, 201)).thenReturn(List.of());

        service.subscribe(-9L);

        verify(eventMapper).findAfter(0L, 201);
    }

    @Test
    void malformedPersistedPayloadDoesNotBreakReplay() {
        when(eventMapper.findAfter(10L, 201))
                .thenReturn(List.of(event(11L, "system.updated", "not-json")));

        assertThatCode(() -> service.subscribe(10L)).doesNotThrowAnyException();
        verify(eventMapper).findAfter(10L, 201);
    }

    @Test
    void removesDisconnectedEmitterWhenSecondCompletionAlsoFails() throws IOException {
        SseEmitter emitter = disconnectedEmitter(new IOException("client disconnected"));
        emitters().put("disconnected", emitter);

        assertThatCode(() -> service.onPersistedEvent(event(
                        44L,
                        "comments.appended",
                        "{\"inserted\":2}")))
                .doesNotThrowAnyException();

        assertThat(service.connectionCount()).isZero();
        verify(emitter).complete();
    }

    @Test
    void heartbeatRemovesClosedEmitterWithoutPropagatingCompletionFailure() throws IOException {
        SseEmitter emitter = disconnectedEmitter(new IllegalStateException("emitter already completed"));
        emitters().put("closed", emitter);

        assertThatCode(service::heartbeat).doesNotThrowAnyException();

        assertThat(service.connectionCount()).isZero();
        verify(emitter).complete();
    }

    private SseEmitter disconnectedEmitter(Exception sendFailure) throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(sendFailure)
                .when(emitter)
                .send(any(SseEmitter.SseEventBuilder.class));
        doAnswer(ignored -> {
                    throw new AsyncRequestNotUsableException("response is no longer usable");
                })
                .when(emitter)
                .complete();
        return emitter;
    }

    @SuppressWarnings("unchecked")
    private Map<String, SseEmitter> emitters() {
        return (Map<String, SseEmitter>) ReflectionTestUtils.getField(service, "emitters");
    }

    private EventRow event(long sequence, String type, String payload) {
        return new EventRow(
                sequence,
                7L,
                31L,
                type,
                "Fixture event",
                payload,
                Instant.parse("2026-07-14T00:00:00Z").plusSeconds(sequence));
    }
}
