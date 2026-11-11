package ch.benedict.m321.chatservice.service;

import ch.benedict.m321.chatservice.dto.ChatMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prüft das begrenzte Replay-Fenster ohne Netzwerk oder Spring-Kontext. */
class MessageReplayBufferTest {

    /** Nach einem bekannten Cursor kommen nur spätere Nachrichten desselben Raums zurück. */
    @Test
    void replaysLaterMessagesOfRequestedRoomInOrder() {
        MessageReplayBuffer buffer = new MessageReplayBuffer();
        UUID roomA = UUID.randomUUID();
        UUID roomB = UUID.randomUUID();
        ChatMessage cursor = message(roomA, "cursor");
        ChatMessage roomBMessage = message(roomB, "other");
        ChatMessage first = message(roomA, "first");
        ChatMessage second = message(roomA, "second");
        buffer.add(cursor);
        buffer.add(roomBMessage);
        buffer.add(first);
        buffer.add(second);

        List<ChatMessage> replay = buffer.after(roomA, cursor.id());

        assertEquals(List.of(first, second), replay);
    }

    /** Dieselbe UUID darf innerhalb des Replay-Fensters kein zweites Ereignis erzeugen. */
    @Test
    void ignoresDuplicateMessageId() {
        MessageReplayBuffer buffer = new MessageReplayBuffer();
        ChatMessage message = message(UUID.randomUUID(), "once");

        boolean firstAdded = buffer.add(message);
        boolean secondAdded = buffer.add(message);

        assertTrue(firstAdded);
        assertFalse(secondAdded);
        assertEquals(1, buffer.size());
    }

    /** Der 1001. Eintrag verdrängt den ältesten und hält den Speicherverbrauch begrenzt. */
    @Test
    void evictsOldestMessageAfterOneThousandEntries() {
        MessageReplayBuffer buffer = new MessageReplayBuffer();
        UUID roomId = UUID.randomUUID();
        List<ChatMessage> messages = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            ChatMessage message = message(roomId, "message-" + i);
            messages.add(message);
            buffer.add(message);
        }

        ChatMessage oldest = messages.getFirst();
        ChatMessage second = messages.get(1);
        assertEquals(1000, buffer.size());
        assertThrows(ReplayCursorException.class, () -> buffer.after(roomId, oldest.id()));
        assertEquals(999, buffer.after(roomId, second.id()).size());
    }

    /** Ein Cursor aus einem anderen Raum darf nicht als gültiger Fortschritt dienen. */
    @Test
    void rejectsCursorFromDifferentRoom() {
        MessageReplayBuffer buffer = new MessageReplayBuffer();
        ChatMessage message = message(UUID.randomUUID(), "other-room");
        buffer.add(message);

        UUID requestedRoom = UUID.randomUUID();
        assertThrows(ReplayCursorException.class, () -> buffer.after(requestedRoom, message.id()));
    }

    /** Eine unbekannte UUID kann keine lückenlose Fortsetzung belegen. */
    @Test
    void rejectsUnknownCursor() {
        MessageReplayBuffer buffer = new MessageReplayBuffer();

        UUID roomId = UUID.randomUUID();
        UUID unknownId = UUID.randomUUID();
        assertThrows(ReplayCursorException.class, () -> buffer.after(roomId, unknownId));
    }

    /** Baut vollständige Vertragsnachrichten mit eindeutigem Inhalt für die Reihenfolgeprüfung. */
    private ChatMessage message(UUID roomId, String content) {
        UUID messageId = UUID.randomUUID();
        Instant sentAt = Instant.now();
        return new ChatMessage(messageId, roomId, "sender", "Sender", content, sentAt);
    }
}
