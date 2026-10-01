package ch.benedict.m321.batchwriter.messaging;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/** Prüft den JSON-Vertrag, bevor ungültige Daten einen ganzen Stapel gefährden. */
@Component
public class MessageDecoder {

    // Grenzen aus PostgreSQL 16, src/include/datatype/timestamp.h, in ISO-Jahreszählung.
    private static final Instant MIN_TIMESTAMP = Instant.parse("-4713-11-24T00:00:00Z");
    private static final Instant END_TIMESTAMP = Instant.parse("+294277-01-01T00:00:00Z");

    private final ObjectMapper objectMapper;

    /** Eigene Parserregeln verhindern mehrdeutige JSON-Objekte und angehängte Werte. */
    public MessageDecoder() {
        objectMapper = new ObjectMapper();
        objectMapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        objectMapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /** Nur Body und Formatangaben werden gebraucht; Java-Typheader spielen keine Rolle. */
    public ChatMessage decode(byte[] body, String contentType, String contentEncoding) {
        validateMetadata(contentType, contentEncoding);
        JsonNode object = readObject(body);
        UUID id = readUuid(object, "id");
        UUID roomId = readUuid(object, "roomId");
        String senderId = readText(object, "senderId");
        String senderName = readText(object, "senderName");
        String content = readText(object, "content");
        Instant sentAt = readTimestamp(object);
        return new ChatMessage(id, roomId, senderId, senderName, content, sentAt);
    }

    /** Falsche Formatangaben dürfen nicht durch zufällig lesbare Bytes verdeckt werden. */
    private void validateMetadata(String contentType, String contentEncoding) {
        if (!"application/json".equals(contentType)) {
            throw new IllegalArgumentException("Expected application/json");
        }
        if (contentEncoding != null && !"UTF-8".equalsIgnoreCase(contentEncoding)) {
            throw new IllegalArgumentException("Expected UTF-8 encoding");
        }
    }

    /** Striktes UTF-8-Lesen meldet beschädigte Bytes statt Zeichen still zu ersetzen. */
    private JsonNode readObject(byte[] body) {
        if (body == null) {
            throw new IllegalArgumentException("Missing message body");
        }
        try {
            CharsetDecoder utf8Decoder = StandardCharsets.UTF_8.newDecoder();
            ByteBuffer bytes = ByteBuffer.wrap(body);
            CharBuffer characters = utf8Decoder.decode(bytes);
            String json = characters.toString();
            JsonNode object = objectMapper.readTree(json);
            if (object == null || !object.isObject()) {
                throw new IllegalArgumentException("Expected one JSON object");
            }
            return object;
        } catch (CharacterCodingException | JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid UTF-8 JSON", exception);
        }
    }

    /** Explizite Typprüfung vermeidet automatische Umwandlungen und erhält Leerzeichen. */
    private String readText(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("Expected string field: " + field);
        }
        String text = value.textValue();
        if (text.isBlank() || text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid text field: " + field);
        }
        return text;
    }

    /** Der Rückvergleich schliesst verkürzte UUID-Schreibweisen aus. */
    private UUID readUuid(JsonNode object, String field) {
        String text = readText(object, field);
        UUID uuid = UUID.fromString(text);
        String canonical = uuid.toString();
        if (!canonical.equalsIgnoreCase(text)) {
            throw new IllegalArgumentException("Expected canonical UUID: " + field);
        }
        return uuid;
    }

    /** Der Offset bestimmt den Zeitpunkt; der DB-Bereich verhindert spätere Überläufe. */
    private Instant readTimestamp(JsonNode object) {
        String text = readText(object, "sentAt");
        try {
            OffsetDateTime dateTime = OffsetDateTime.parse(text);
            Instant timestamp = dateTime.toInstant();
            if (timestamp.isBefore(MIN_TIMESTAMP) || !timestamp.isBefore(END_TIMESTAMP)) {
                throw new IllegalArgumentException("Timestamp outside PostgreSQL range");
            }
            return timestamp;
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Expected ISO-8601 timestamp with offset", exception);
        }
    }
}
