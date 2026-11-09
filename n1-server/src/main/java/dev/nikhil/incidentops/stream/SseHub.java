package dev.nikhil.incidentops.stream;

import dev.nikhil.incidentops.service.IncidentChanged;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Fan-out of incident changes to every open dashboard via Server-Sent Events.
 * Single process only: a second server instance would not see these events.
 */
@Component
public class SseHub {
    private static final long TIMEOUT_MS = 30 * 60 * 1000L; // browsers' EventSource reconnects on its own
    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        send(emitter, SseEmitter.event().name("hello").data(Map.of("subscribers", emitters.size())));
        return emitter;
    }

    /** AFTER_COMMIT: a rolled-back change (e.g. a lost optimistic-lock race) is never broadcast. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onIncidentChanged(IncidentChanged change) {
        String name = "incident." + change.event().type().name().toLowerCase();
        for (SseEmitter emitter : emitters) {
            send(emitter, SseEmitter.event().name(name).id(String.valueOf(change.event().id()))
                .data(change, MediaType.APPLICATION_JSON));
        }
    }

    @Scheduled(fixedRate = 15_000)
    public void heartbeat() {
        for (SseEmitter emitter : emitters) send(emitter, SseEmitter.event().comment("keepalive"));
    }

    public int subscriberCount() { return emitters.size(); }

    /**
     * ContextClosedEvent fires before the web server's graceful-shutdown phase. Closing streams in @PreDestroy was
     * too late: Tomcat sat waiting 30 s for "in-flight" SSE requests whose clients had already gone away.
     */
    @EventListener(ContextClosedEvent.class)
    public void closeAll() {
        emitters.forEach(SseEmitter::complete);
        emitters.clear();
    }

    private void send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException e) {
            // Client went away. Without this removal, dead emitters pile up and every broadcast pays for them.
            emitters.remove(emitter);
        }
    }
}
