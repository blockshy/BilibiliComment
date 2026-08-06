package com.hy.bilicomment.application.event;

import com.hy.bilicomment.config.AppProperties;
import com.hy.bilicomment.infrastructure.persistence.entity.EventRow;
import com.hy.bilicomment.infrastructure.persistence.mapper.EventMapper;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class SseEventService {

    private final EventMapper eventMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final long emitterTimeoutMillis;
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final AtomicLong latestSequence = new AtomicLong();
    private final ReentrantLock deliveryLock = new ReentrantLock();

    public SseEventService(
            EventMapper eventMapper,
            ObjectMapper objectMapper,
            Clock clock,
            AppProperties properties) {
        this.eventMapper = eventMapper;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.emitterTimeoutMillis = properties.getEvents().getEmitterTimeout().toMillis();
    }

    public SseEmitter subscribe(Long afterSequence) {
        SseEmitter emitter = new SseEmitter(emitterTimeoutMillis);
        String subscriberId = UUID.randomUUID().toString();
        emitter.onCompletion(() -> emitters.remove(subscriberId));
        emitter.onTimeout(() -> emitters.remove(subscriberId));
        emitter.onError(error -> emitters.remove(subscriberId));

        deliveryLock.lock();
        try {
            long persistedLatest = eventMapper.findLatestSequence();
            latestSequence.accumulateAndGet(persistedLatest, Math::max);
            if (afterSequence != null) {
                List<EventRow> replay = eventMapper.findAfter(Math.max(0, afterSequence), 201);
                int replayCount = Math.min(200, replay.size());
                for (int index = 0; index < replayCount; index++) {
                    EventRow event = replay.get(index);
                    send(emitter, toLiveEvent(event));
                    latestSequence.accumulateAndGet(event.eventSequence(), Math::max);
                }
            }
            emitters.put(subscriberId, emitter);
            send(emitter, heartbeatEvent());
        } catch (IOException exception) {
            emitters.remove(subscriberId);
            emitter.completeWithError(exception);
        } finally {
            deliveryLock.unlock();
        }
        return emitter;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPersistedEvent(EventRow event) {
        deliveryLock.lock();
        try {
            latestSequence.accumulateAndGet(event.eventSequence(), Math::max);
            broadcast(toLiveEvent(event));
        } finally {
            deliveryLock.unlock();
        }
    }

    @Scheduled(fixedDelayString = "${app.events.heartbeat-delay:25s}")
    public void heartbeat() {
        if (!emitters.isEmpty()) {
            deliveryLock.lock();
            try {
                broadcast(heartbeatEvent());
            } finally {
                deliveryLock.unlock();
            }
        }
    }

    public int connectionCount() {
        return emitters.size();
    }

    private void broadcast(LiveEvent event) {
        emitters.forEach((id, emitter) -> {
            try {
                send(emitter, event);
            } catch (IOException | IllegalStateException exception) {
                emitters.remove(id);
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // The async response may already be unusable after a failed send.
                }
            }
        });
    }

    private void send(SseEmitter emitter, LiveEvent event) throws IOException {
        emitter.send(SseEmitter.event()
                .id(event.eventId())
                .name(event.type())
                .data(event));
    }

    private LiveEvent toLiveEvent(EventRow event) {
        JsonNode payload;
        try {
            payload = objectMapper.readTree(event.payloadJson());
        } catch (Exception ignored) {
            payload = objectMapper.createObjectNode();
        }
        return new LiveEvent(
                Long.toString(event.eventSequence()),
                event.eventType(),
                event.createdAt(),
                event.eventSequence(),
                event.taskId() == null ? null : Long.toString(event.taskId()),
                event.executionId() == null ? null : Long.toString(event.executionId()),
                payload);
    }

    private LiveEvent heartbeatEvent() {
        long sequence = latestSequence.get();
        return new LiveEvent(
                Long.toString(sequence),
                "heartbeat",
                clock.instant(),
                sequence,
                null,
                null,
                objectMapper.createObjectNode());
    }

    public record LiveEvent(
            String eventId,
            String type,
            Instant occurredAt,
            long sequence,
            String taskId,
            String executionId,
            JsonNode data) {}
}
