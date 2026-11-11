package ch.benedict.m321.chatservice.service;

import ch.benedict.m321.chatservice.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/** Verbindet den lokalen Replay-Puffer mit den SSE-Clients dieser Dienstinstanz. */
@Service
@RequiredArgsConstructor
@Slf4j
public class LiveMessageService {

    private final MessageReplayBuffer replayBuffer;
    private final List<StreamConnection> connections = new ArrayList<>();

    /**
     * Führt Replay und Registrierung unter derselben Sperre aus, damit dazwischen
     * keine gleichzeitig eintreffende Live-Nachricht verloren gehen kann.
     */
    public synchronized SseEmitter subscribe(UUID roomId, UUID lastEventId) {
        SseEmitter emitter = newEmitter();
        StreamConnection connection = new StreamConnection(roomId, emitter);
        configureCallbacks(connection);
        if (lastEventId != null) {
            List<ChatMessage> replay = replayBuffer.after(roomId, lastEventId);
            for (ChatMessage message : replay) {
                if (!send(connection, message)) {
                    throw new IllegalStateException("Replay connection failed");
                }
            }
        }
        connections.add(connection);
        log.info("SSE client connected: room={}, clients={}", roomId, connections.size());
        return emitter;
    }

    /** Puffert eine neue UUID einmalig und verteilt sie nur an Clients desselben Raums. */
    public synchronized void deliver(ChatMessage message) {
        boolean added = replayBuffer.add(message);
        if (!added) {
            log.debug("Duplicate live message ignored: id={}", message.id());
            return;
        }
        Iterator<StreamConnection> iterator = connections.iterator();
        while (iterator.hasNext()) {
            StreamConnection connection = iterator.next();
            if (connection.roomId().equals(message.roomId())) {
                boolean sent = send(connection, message);
                if (!sent) {
                    iterator.remove();
                }
            }
        }
    }

    /** Die schmale Erzeugungsstelle ermöglicht kontrollierte Emitter in Unit-Tests. */
    SseEmitter newEmitter() {
        return new SseEmitter(Long.MAX_VALUE);
    }

    /** Entfernt abgeschlossene und fehlerhafte Verbindungen auch ohne weitere Nachricht. */
    private void configureCallbacks(StreamConnection connection) {
        SseEmitter emitter = connection.emitter();
        emitter.onCompletion(() -> remove(connection));
        emitter.onTimeout(() -> remove(connection));
        emitter.onError(error -> remove(connection));
    }

    /** Formatiert genau den bewerteten SSE-Vertrag aus ID und vollständigem JSON-Datensatz. */
    private boolean send(StreamConnection connection, ChatMessage message) {
        SseEmitter.SseEventBuilder event = SseEmitter.event();
        event.id(message.id().toString());
        event.data(message);
        try {
            connection.emitter().send(event);
            return true;
        } catch (IOException | IllegalStateException exception) {
            Class<?> errorType = exception.getClass();
            log.warn("SSE delivery failed: room={}, error={}", connection.roomId(), errorType.getSimpleName());
            return false;
        }
    }

    /** Der Callback kann parallel eintreffen und verwendet deshalb denselben Zustandsschutz. */
    private synchronized void remove(StreamConnection connection) {
        boolean removed = connections.remove(connection);
        if (removed) {
            log.info("SSE client removed: room={}, clients={}", connection.roomId(), connections.size());
        }
    }

    /** Hält Raum und Emitter zusammen, damit beide bei jedem Fehler gemeinsam entfernt werden. */
    private record StreamConnection(UUID roomId, SseEmitter emitter) {
    }
}
