package ch.benedict.m321.chatservice.service;

import ch.benedict.m321.chatservice.dto.ChatMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Bewahrt ein begrenztes, geordnetes Replay-Fenster unabhängig von PostgreSQL auf. */
@Component
public class MessageReplayBuffer {

    private static final int MAX_MESSAGES = 1000;

    private final Map<UUID, ChatMessage> messages = new LinkedHashMap<>();

    /** Fügt eine neue UUID einmalig ein und verdrängt bei Bedarf den ältesten Eintrag. */
    public synchronized boolean add(ChatMessage message) {
        UUID messageId = message.id();
        if (messages.containsKey(messageId)) {
            return false;
        }
        messages.put(messageId, message);
        removeOldestWhenFull();
        return true;
    }

    /** Liefert nach einem gültigen Cursor nur spätere Nachrichten des angefragten Raums. */
    public synchronized List<ChatMessage> after(UUID roomId, UUID lastEventId) {
        List<ChatMessage> replay = new ArrayList<>();
        boolean cursorFound = false;
        for (ChatMessage message : messages.values()) {
            if (!cursorFound) {
                if (message.id().equals(lastEventId)) {
                    if (!message.roomId().equals(roomId)) {
                        throw new ReplayCursorException();
                    }
                    cursorFound = true;
                }
            } else if (message.roomId().equals(roomId)) {
                replay.add(message);
            }
        }
        if (!cursorFound) {
            throw new ReplayCursorException();
        }
        return List.copyOf(replay);
    }

    /** Die sichtbare Grösse erlaubt eine direkte Prüfung der verbindlichen Speichergrenze. */
    synchronized int size() {
        return messages.size();
    }

    /** Entfernt höchstens einen Eintrag, weil jeder Aufruf ebenfalls nur einen hinzufügt. */
    private void removeOldestWhenFull() {
        if (messages.size() <= MAX_MESSAGES) {
            return;
        }
        Iterator<UUID> messageIds = messages.keySet().iterator();
        if (messageIds.hasNext()) {
            messageIds.next();
            messageIds.remove();
        }
    }
}
