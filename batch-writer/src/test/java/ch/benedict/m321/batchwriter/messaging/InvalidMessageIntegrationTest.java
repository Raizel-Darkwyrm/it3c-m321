package ch.benedict.m321.batchwriter.messaging;

import ch.benedict.m321.batchwriter.BatchWriterApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** Prüft echte Dead-Letter-Übergabe und gültige Nachbarn ohne Ersatz des produktiven Fehlerpfads. */
@SpringBootTest(classes = BatchWriterApplication.class,
        properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@Testcontainers
class InvalidMessageIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = createPostgres();
    @Container
    private static final RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private RabbitListenerEndpointRegistry listeners;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Die Prüfung verwendet dieselbe Init-Datei wie der produktive Compose-Stack. */
    private static PostgreSQLContainer<?> createPostgres() {
        String moduleDirectory = System.getProperty("basedir");
        Path script = Path.of(moduleDirectory, "..", "postgres", "init", "001-create-message.sql");
        MountableFile initFile = MountableFile.forHostPath(script);
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:16");
        container.withCopyFileToContainer(initFile, "/docker-entrypoint-initdb.d/001-create-message.sql");
        return container;
    }

    /** Eigene Container ersetzen nur Verbindungsdaten; Decoder, Consumer und DB-Schreibweg bleiben echt. */
    @DynamicPropertySource
    static void configureConnections(DynamicPropertyRegistry registry) {
        registry.add("POSTGRES_USER", postgres::getUsername);
        registry.add("POSTGRES_PASSWORD", postgres::getPassword);
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("RABBITMQ_HOST", rabbitMq::getHost);
        registry.add("RABBITMQ_USER", rabbitMq::getAdminUsername);
        registry.add("RABBITMQ_PASSWORD", rabbitMq::getAdminPassword);
        registry.add("spring.rabbitmq.port", rabbitMq::getAmqpPort);
    }

    /** Alle Fehlerarten liegen zwischen gültigen Lieferungen und dürfen deren Speicherung nicht verhindern. */
    @Test
    void deadLettersOnlyInvalidBodiesAndStoresValidNeighbors() throws Exception {
        List<Message> invalidMessages = invalidMessages();
        List<UUID> expectedIds = new ArrayList<>();
        publishValidMessage(expectedIds);
        for (Message invalid : invalidMessages) {
            rabbitTemplate.send("", "chat.persist", invalid);
            publishValidMessage(expectedIds);
        }
        listeners.start();
        try {
            assertProcessingCompleted(expectedIds, invalidMessages);
            assertUnchangedDeadLetterBodies(invalidMessages);
        } finally {
            listeners.stop();
        }
    }

    /** Prüft IDs und Queue-Zähler gemeinsam, damit offene oder verlorene Lieferungen nicht übersehen werden. */
    private void assertProcessingCompleted(List<UUID> expectedIds, List<Message> invalidMessages) {
        Set<UUID> expected = new HashSet<>(expectedIds);
        int validCount = expectedIds.size();
        int invalidCount = invalidMessages.size();
        await().pollInterval(Duration.ofMillis(200)).atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            List<UUID> storedIds = jdbcTemplate.queryForList("SELECT id FROM public.message", UUID.class);
            int storedCount = storedIds.size();
            Set<UUID> stored = new HashSet<>(storedIds);
            assertEquals(validCount, storedCount);
            assertEquals(expected, stored);
            JsonNode input = queueState("chat.persist");
            assertCounter(input, "messages_ready", 0);
            assertCounter(input, "messages_unacknowledged", 0);
            JsonNode deadLetters = queueState("chat.dlq");
            assertCounter(deadLetters, "messages_ready", invalidCount);
            assertCounter(deadLetters, "messages_unacknowledged", 0);
        });
        List<String> contents = jdbcTemplate.queryForList("SELECT content FROM public.message", String.class);
        for (String content : contents) {
            assertEquals("Hallo Grüezi 🌍", content);
        }
    }

    /** Erst nach dem Mengennachweis werden ausschliesslich die DLQ-Nachrichten dieses Testcontainers entnommen. */
    private void assertUnchangedDeadLetterBodies(List<Message> invalidMessages) {
        Base64.Encoder encoder = Base64.getEncoder();
        List<String> expectedBodies = new ArrayList<>();
        for (Message message : invalidMessages) {
            byte[] body = message.getBody();
            String encoded = encoder.encodeToString(body);
            expectedBodies.add(encoded);
        }
        int count = invalidMessages.size();
        for (int i = 0; i < count; i++) {
            Message actual = rabbitTemplate.receive("chat.dlq", 1000);
            assertNotNull(actual);
            byte[] body = actual.getBody();
            String encoded = encoder.encodeToString(body);
            boolean expectedBody = expectedBodies.remove(encoded);
            assertTrue(expectedBody, "DLQ contains an unexpected or duplicated body");
        }
        boolean allBodiesFound = expectedBodies.isEmpty();
        assertTrue(allBodiesFound);
        Message extra = rabbitTemplate.receive("chat.dlq");
        assertNull(extra);
    }

    /** Fünf verschiedene Bodies machen auch vertauschte, fehlende oder doppelte DLQ-Einträge sichtbar. */
    private List<Message> invalidMessages() {
        List<Message> messages = new ArrayList<>();
        Message invalidJson = rawMessage("{invalid JSON", "application/json", null);
        messages.add(invalidJson);
        UUID wrongTypeId = UUID.randomUUID();
        String wrongType = validJson(wrongTypeId);
        wrongType = wrongType.replace("\"content\":\"Hallo Grüezi 🌍\"", "\"content\":42");
        Message wrongTypeMessage = rawMessage(wrongType, "application/json", null);
        messages.add(wrongTypeMessage);
        UUID missingFieldId = UUID.randomUUID();
        String missingField = validJson(missingFieldId);
        missingField = missingField.replace("\"senderId\":\"anna\",", "");
        Message missingFieldMessage = rawMessage(missingField, "application/json", null);
        messages.add(missingFieldMessage);
        UUID wrongContentTypeId = UUID.randomUUID();
        String wrongContentType = validJson(wrongContentTypeId);
        Message wrongContentTypeMessage = rawMessage(wrongContentType, "text/plain", null);
        messages.add(wrongContentTypeMessage);
        UUID wrongEncodingId = UUID.randomUUID();
        String wrongEncoding = validJson(wrongEncodingId);
        Message wrongEncodingMessage = rawMessage(wrongEncoding, "application/json", "ISO-8859-1");
        messages.add(wrongEncodingMessage);
        return messages;
    }

    /** Eindeutige gültige Nachbarn erlauben einen vollständigen Vergleich der tatsächlich gespeicherten IDs. */
    private void publishValidMessage(List<UUID> expectedIds) {
        UUID id = UUID.randomUUID();
        expectedIds.add(id);
        String json = validJson(id);
        Message message = rawMessage(json, "application/json", null);
        rabbitTemplate.send("", "chat.persist", message);
    }

    /** Unicode und vollständige Pflichtfelder prüfen denselben Vertrag wie Nachrichten des chat-service. */
    private String validJson(UUID id) {
        return """
                {"id":"%s","roomId":"123e4567-e89b-12d3-a456-426614174001",
                 "senderId":"anna","senderName":"Anna","content":"Hallo Grüezi 🌍",
                 "sentAt":"2026-10-01T12:00:00Z"}
                """.formatted(id);
    }

    /** Rohe Bytes und explizite Formatangaben verhindern eine automatische Reparatur durch Konverter. */
    private Message rawMessage(String json, String contentType, String encoding) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        MessageProperties properties = new MessageProperties();
        properties.setContentType(contentType);
        properties.setContentEncoding(encoding);
        return new Message(body, properties);
    }

    /** Fehlende Management-Felder sind kein Beweis für eine leere Queue. */
    private void assertCounter(JsonNode queue, String field, int expected) {
        JsonNode value = queue.path(field);
        int actual = value.asInt(-1);
        assertEquals(expected, actual);
    }

    /** Beobachtet offene Lieferungen am isolierten Broker, ohne Nachrichten aus der Eingangsqueue zu entnehmen. */
    private JsonNode queueState(String queueName) throws Exception {
        String url = "http://" + rabbitMq.getHost() + ":" + rabbitMq.getHttpPort() + "/api/queues/%2F/" + queueName;
        URI uri = URI.create(url);
        String credentials = rabbitMq.getAdminUsername() + ":" + rabbitMq.getAdminPassword();
        byte[] bytes = credentials.getBytes(StandardCharsets.UTF_8);
        Base64.Encoder encoder = Base64.getEncoder();
        String authorization = encoder.encodeToString(bytes);
        Duration timeout = Duration.ofSeconds(2);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri);
        builder.timeout(timeout);
        builder.header("Authorization", "Basic " + authorization);
        builder.GET();
        HttpRequest request = builder.build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse.BodyHandler<String> handler = HttpResponse.BodyHandlers.ofString();
            HttpResponse<String> response = client.send(request, handler);
            int status = response.statusCode();
            assertEquals(200, status);
            String body = response.body();
            return objectMapper.readTree(body);
        }
    }
}
