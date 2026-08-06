package com.hy.bilicomment.interfaces.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hy.bilicomment.application.event.SseEventService;
import com.hy.bilicomment.domain.error.DomainException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@ExtendWith(MockitoExtension.class)
class EventControllerTests {

    @Mock
    private SseEventService eventService;

    private EventController controller;

    @BeforeEach
    void setUp() {
        controller = new EventController(eventService);
    }

    @Test
    void passesLastEventIdToReplayAndDisablesProxyBuffering() {
        SseEmitter emitter = new SseEmitter();
        when(eventService.subscribe(41L)).thenReturn(emitter);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(controller.events("41", null, response)).isSameAs(emitter);

        verify(eventService).subscribe(41L);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("X-Accel-Buffering")).isEqualTo("no");
    }

    @Test
    void startsAtTheLiveHeadWithoutAnIdAndClampsNegativeIds() {
        when(eventService.subscribe(null)).thenReturn(new SseEmitter());
        when(eventService.subscribe(0L)).thenReturn(new SseEmitter());

        controller.events(null, null, new MockHttpServletResponse());
        controller.events("  ", null, new MockHttpServletResponse());
        controller.events("-5", null, new MockHttpServletResponse());

        verify(eventService, org.mockito.Mockito.times(2)).subscribe(null);
        verify(eventService).subscribe(0L);
    }

    @Test
    void explicitAfterSequenceTakesPrecedenceOverTheBrowserHeader() {
        when(eventService.subscribe(55L)).thenReturn(new SseEmitter());

        controller.events("41", "55", new MockHttpServletResponse());

        verify(eventService).subscribe(55L);
    }

    @Test
    void rejectsNonNumericLastEventId() {
        assertThatThrownBy(() -> controller.events(
                        "event-41", null, new MockHttpServletResponse()))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("EVENT_ID_INVALID"));
    }
}
