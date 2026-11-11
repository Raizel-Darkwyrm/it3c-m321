package ch.benedict.m321.chatservice.service;

import ch.benedict.m321.chatservice.dto.ChatMessage;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Prüft Raumfilterung und Verbindungsabbau ohne echten HTTP-Server. */
class LiveMessageServiceTest {

    /** Nur Verbindungen des Nachrichtenraums dürfen das Ereignis erhalten. */
    @Test
    void sendsOnlyToMatchingRoom() throws IOException {
        MessageReplayBuffer buffer = new MessageReplayBuffer();
        LiveMessageService service = spy(new LiveMessageService(buffer));
        SseEmitter roomAEmitter = mock(SseEmitter.class);
        SseEmitter roomBEmitter = mock(SseEmitter.class);
        doReturn(roomAEmitter, roomBEmitter).when(service).newEmitter();
        UUID roomA = UUID.randomUUID();
        UUID roomB = UUID.randomUUID();
        service.subscribe(roomA, null);
        service.subscribe(roomB, null);

        service.deliver(message(roomA, "for-a"));

        verify(roomAEmitter).send(any(SseEmitter.SseEventBuilder.class));
        verify(roomBEmitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    /** Ein Sendefehler entfernt nur den betroffenen Client und lässt den nächsten weiterlaufen. */
    @Test
    void removesFailedEmitterAndContinuesDelivery() throws IOException {
        MessageReplayBuffer buffer = new MessageReplayBuffer();
        LiveMessageService service = spy(new LiveMessageService(buffer));
        SseEmitter failed = mock(SseEmitter.class);
        SseEmitter healthy = mock(SseEmitter.class);
        doReturn(failed, healthy).when(service).newEmitter();
        doThrow(new IOException("Client disconnected"))
                .when(failed).send(any(SseEmitter.SseEventBuilder.class));
        UUID roomId = UUID.randomUUID();
        service.subscribe(roomId, null);
        service.subscribe(roomId, null);

        service.deliver(message(roomId, "first"));
        service.deliver(message(roomId, "second"));

        verify(failed, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(healthy, times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }

    /** Replay wird vor der anschliessenden Live-Nachricht über denselben Emitter gesendet. */
    @Test
    void replaysBeforeContinuingLive() throws IOException {
        MessageReplayBuffer buffer = new MessageReplayBuffer();
        UUID roomId = UUID.randomUUID();
        ChatMessage cursor = message(roomId, "cursor");
        ChatMessage missed = message(roomId, "missed");
        buffer.add(cursor);
        buffer.add(missed);
        LiveMessageService service = spy(new LiveMessageService(buffer));
        SseEmitter emitter = mock(SseEmitter.class);
        doReturn(emitter).when(service).newEmitter();

        service.subscribe(roomId, cursor.id());
        service.deliver(message(roomId, "live"));

        verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }

    /** Eine wiederholte Brokerlieferung derselben UUID bleibt für den Client einmalig. */
    @Test
    void doesNotDeliverDuplicateIdTwice() throws IOException {
        MessageReplayBuffer buffer = new MessageReplayBuffer();
        LiveMessageService service = spy(new LiveMessageService(buffer));
        SseEmitter emitter = mock(SseEmitter.class);
        doReturn(emitter).when(service).newEmitter();
        UUID roomId = UUID.randomUUID();
        service.subscribe(roomId, null);
        ChatMessage message = message(roomId, "once");

        service.deliver(message);
        service.deliver(message);

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
    }

    /** Der Completion-Callback entfernt eine Verbindung vor der nächsten Nachricht. */
    @Test
    void removesCompletedEmitter() throws IOException {
        MessageReplayBuffer buffer = new MessageReplayBuffer();
        LiveMessageService service = spy(new LiveMessageService(buffer));
        SseEmitter emitter = mock(SseEmitter.class);
        doReturn(emitter).when(service).newEmitter();
        UUID roomId = UUID.randomUUID();
        service.subscribe(roomId, null);
        org.mockito.ArgumentCaptor<Runnable> callback = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(emitter).onCompletion(callback.capture());
        reset(emitter);

        callback.getValue().run();
        service.deliver(message(roomId, "after-completion"));

        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    /** Baut eine vollständige Nachricht für die lokale Verteilungsprüfung. */
    private ChatMessage message(UUID roomId, String content) {
        UUID messageId = UUID.randomUUID();
        Instant sentAt = Instant.now();
        return new ChatMessage(messageId, roomId, "sender", "Sender", content, sentAt);
    }
}
