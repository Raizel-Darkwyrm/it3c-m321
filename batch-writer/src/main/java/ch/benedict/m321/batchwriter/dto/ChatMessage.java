package ch.benedict.m321.batchwriter.dto;

import java.time.Instant;
import java.util.UUID;

/** Bewahrt die sechs Vertragsfelder unabhängig von Java-Klassen des Producers auf. */
public record ChatMessage(
        UUID id,
        UUID roomId,
        String senderId,
        String senderName,
        String content,
        Instant sentAt) {
}
