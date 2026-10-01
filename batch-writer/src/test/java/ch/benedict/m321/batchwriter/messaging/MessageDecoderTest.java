package ch.benedict.m321.batchwriter.messaging;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Prüft den Nachrichtenvertrag ohne Broker, Datenbank oder Java-Typheader. */
class MessageDecoderTest {

    private static final String VALID_JSON = """
            {"id":"c8c8b460-3bbb-4f73-9b8b-c8a7eca89125",
             "roomId":"3f2b1c4e-0000-0000-0000-000000000001",
             "senderId":"anna","senderName":"Anna Muster",
             "content":"  Grüezi 🌍  ","sentAt":"2026-10-01T14:00:00.123456789+02:00"}
            """;

    private final MessageDecoder decoder = new MessageDecoder();

    /** Nur content_type genügt; alle sechs Werte müssen unverändert ankommen. */
    @Test
    void decodesAllFieldsWithoutTypeHeaders() {
        ChatMessage message = decode(VALID_JSON);
        UUID expectedId = UUID.fromString("c8c8b460-3bbb-4f73-9b8b-c8a7eca89125");
        UUID expectedRoomId = UUID.fromString("3f2b1c4e-0000-0000-0000-000000000001");
        Instant expectedTime = Instant.parse("2026-10-01T12:00:00.123456789Z");

        assertEquals(expectedId, message.id());
        assertEquals(expectedRoomId, message.roomId());
        assertEquals("anna", message.senderId());
        assertEquals("Anna Muster", message.senderName());
        assertEquals("  Grüezi 🌍  ", message.content());
        assertEquals(expectedTime, message.sentAt());
    }

    /** Erweiterungen dürfen den Vertrag der bekannten Felder nicht verändern. */
    @Test
    void ignoresAdditionalFields() {
        String json = VALID_JSON.replace("{", "{\"extra\":{\"value\":[1,true]},");
        ChatMessage expected = decode(VALID_JSON);
        ChatMessage actual = decode(json);
        assertEquals(expected, actual);
    }

    /** Jedes Pflichtfeld braucht einen echten String, keinen Ersatzwert. */
    @ParameterizedTest
    @ValueSource(strings = {"id", "roomId", "senderId", "senderName", "content", "sentAt"})
    void rejectsMissingNullAndWrongTypes(String field) {
        String missing = VALID_JSON.replaceFirst("\"" + field + "\":\"[^\"]*\",?", "");
        missing = missing.replaceAll(",\\s*}", "}");
        assertInvalid(missing);
        String[] invalidValues = {"null", "42", "true", "[]", "{}"};
        for (String value : invalidValues) {
            String json = replaceField(field, value);
            assertInvalid(json);
        }
    }

    /** Leere Texte und PostgreSQL-unverträgliche Nullzeichen sind Eingabefehler. */
    @ParameterizedTest
    @ValueSource(strings = {"senderId", "senderName", "content"})
    void rejectsBlankAndNullCharacters(String field) {
        String[] invalidValues = {"\"\"", "\" \\t\\n\"", "\"a\\u0000b\""};
        for (String value : invalidValues) {
            String json = replaceField(field, value);
            assertInvalid(json);
        }
    }

    /** UUID.fromString allein akzeptiert auch verkürzte, hier verbotene UUIDs. */
    @ParameterizedTest
    @ValueSource(strings = {"id", "roomId"})
    void rejectsInvalidUuidRepresentations(String field) {
        String[] invalidValues = {"\"1-1-1-1-1\"", "\"not-a-uuid\"", "\"\""};
        for (String value : invalidValues) {
            String json = replaceField(field, value);
            assertInvalid(json);
        }
    }

    /** Fehlende Zeitzonen, ungültige Kalenderdaten und DB-Überläufe werden erkannt. */
    @ParameterizedTest
    @ValueSource(strings = {"2026-10-01T12:00:00", "2026-02-30T12:00:00Z",
            "not-a-date", "+294277-01-01T00:00:00Z", "-4713-11-23T23:59:59Z"})
    void rejectsInvalidTimestamps(String timestamp) {
        String json = replaceField("sentAt", "\"" + timestamp + "\"");
        assertInvalid(json);
    }

    /** Auch gültige Randwerte und UTC bleiben ohne künstliche Datumsgrenze erlaubt. */
    @ParameterizedTest
    @ValueSource(strings = {"-4713-11-24T00:00:00Z", "+294276-12-31T23:59:59.999999Z",
            "2026-10-01T12:00:00Z"})
    void acceptsTimestampBoundaries(String timestamp) {
        String json = replaceField("sentAt", "\"" + timestamp + "\"");
        ChatMessage message = decode(json);
        Instant expected = Instant.parse(timestamp);
        assertEquals(expected, message.sentAt());
    }

    /** Lange Texte werden nicht willkürlich gekürzt oder begrenzt. */
    @Test
    void preservesLongText() {
        String content = "a".repeat(100000);
        String json = replaceField("content", "\"" + content + "\"");
        ChatMessage message = decode(json);
        assertEquals(content, message.content());
    }

    /** Genau ein Objekt ist erlaubt; auch doppelte unbekannte Felder sind mehrdeutig. */
    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "42", "{", "{} {}",
            "{\"extra\":1,\"extra\":2}", "{\"id\":\"a\",\"id\":\"b\"}"})
    void rejectsInvalidJsonStructures(String json) {
        assertInvalid(json);
    }

    /** Nach einem gültigen Objekt darf kein zweiter JSON-Wert folgen. */
    @Test
    void rejectsTrailingValuesAndDuplicateFields() {
        assertInvalid(VALID_JSON + " {}");
        String duplicate = VALID_JSON.replace("{", "{\"senderId\":\"other\",");
        assertInvalid(duplicate);
    }

    /** Defekte UTF-8-Bytes dürfen nicht still durch Ersatzzeichen ausgetauscht werden. */
    @Test
    void rejectsMalformedUtf8() {
        byte[] body = VALID_JSON.getBytes(StandardCharsets.UTF_8);
        body[body.length - 5] = (byte) 0xC3;
        body[body.length - 4] = (byte) 0x28;
        assertThrows(IllegalArgumentException.class,
                () -> decoder.decode(body, "application/json", null));
    }

    /** Metadaten müssen zum vereinbarten Format passen, UTF-8 ist optional. */
    @Test
    void validatesContentMetadata() {
        byte[] body = VALID_JSON.getBytes(StandardCharsets.UTF_8);
        ChatMessage expected = decode(VALID_JSON);
        ChatMessage actual = decoder.decode(body, "application/json", "UTF-8");
        assertEquals(expected, actual);
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(body, null, null));
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(body, "text/plain", null));
        assertThrows(IllegalArgumentException.class,
                () -> decoder.decode(body, "application/json", "UTF-16"));
    }

    /** Wiederholte Zustellung muss dieselbe Identität und dieselben Daten ergeben. */
    @Test
    void preservesRepeatedMessage() {
        ChatMessage first = decode(VALID_JSON);
        ChatMessage second = decode(VALID_JSON);
        assertEquals(first, second);
    }

    /** Testnachrichten werden wie der Queue-Body ausdrücklich als UTF-8 übergeben. */
    private ChatMessage decode(String json) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        return decoder.decode(body, "application/json", null);
    }

    /** Ein einheitlicher Fehlertyp erlaubt dem späteren Consumer die Ablehnung. */
    private void assertInvalid(String json) {
        assertThrows(IllegalArgumentException.class, () -> decode(json));
    }

    /** Ersetzt gezielt einen Feldwert, damit jeweils nur eine Vertragsregel verletzt wird. */
    private String replaceField(String field, String value) {
        String expression = "\"" + field + "\":\"[^\"]*\"";
        String replacement = "\"" + field + "\":" + value;
        String literalReplacement = java.util.regex.Matcher.quoteReplacement(replacement);
        return VALID_JSON.replaceFirst(expression, literalReplacement);
    }
}
