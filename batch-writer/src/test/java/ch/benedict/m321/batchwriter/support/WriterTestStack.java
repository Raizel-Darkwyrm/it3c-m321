package ch.benedict.m321.batchwriter.support;

import ch.benedict.m321.batchwriter.BatchWriterApplication;
import ch.benedict.m321.batchwriter.dto.ChatMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.MountableFile;

import java.net.URI;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Gemeinsamer echter Stack für die Duplikat-, Kanal-, Parallel- und Ausfallnachweise. */
public class WriterTestStack implements AutoCloseable {
    public final PostgreSQLContainer<?> postgres;
    public final RabbitMQContainer rabbitMq;
    public final JdbcTemplate database;
    public final List<ConfigurableApplicationContext> writers = new ArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final Connection connection;
    private final Channel publisher;
    private long published;

    /** Jeder Test erhält isolierte Dienste und das unveränderte produktive Schema. */
    public WriterTestStack() throws Exception {
        this(false);
    }

    /** Der Ausfalltest braucht dieselbe Host-Port-Zuordnung auch nach einem Docker-Neustart. */
    public WriterTestStack(boolean stablePostgresPort) throws Exception {
        String moduleDirectory = System.getProperty("basedir");
        Path script = Path.of(moduleDirectory, "..", "postgres", "init", "001-create-message.sql");
        MountableFile initFile = MountableFile.forHostPath(script);
        postgres = new PostgreSQLContainer<>("postgres:16");
        if (stablePostgresPort) {
            // Einen freien Testport wählen und explizit binden; keine Produktionsports veröffentlichen.
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int candidate = random.nextInt(20000, 30000);
            try (ServerSocket socket = new ServerSocket(candidate)) {
                int port = socket.getLocalPort();
                List<String> bindings = List.of(port + ":5432");
                postgres.setPortBindings(bindings);
            }
        }
        postgres.withCopyFileToContainer(initFile, "/docker-entrypoint-initdb.d/001-create-message.sql");
        rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");
        rabbitMq.withEnv("RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS", "-rabbit collect_statistics_interval 100");
        postgres.start();
        rabbitMq.start();
        DriverManagerDataSource source = new DriverManagerDataSource();
        String url = postgres.getJdbcUrl();
        source.setUrl(url + "?connectTimeout=1&socketTimeout=1");
        source.setUsername(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        database = new JdbcTemplate(source);
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(rabbitMq.getHost());
        factory.setPort(rabbitMq.getAmqpPort());
        factory.setUsername(rabbitMq.getAdminUsername());
        factory.setPassword(rabbitMq.getAdminPassword());
        connection = factory.newConnection();
        publisher = connection.createChannel();
        publisher.confirmSelect();
        Map<String, Object> arguments = Map.of("x-dead-letter-exchange", "",
                "x-dead-letter-routing-key", "chat.dlq");
        publisher.queueDeclare("chat.persist", true, false, false, arguments);
        publisher.queueDeclare("chat.dlq", true, false, false, null);
    }

    /** Getrennte Spring-Kontexte besitzen eigene Puffer, DB-Pools und AMQP-Verbindungen. */
    public ConfigurableApplicationContext startWriter(boolean autoStartup) {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(BatchWriterApplication.class);
        ConfigurableApplicationContext context = builder.run(
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--POSTGRES_USER=" + postgres.getUsername(),
                "--POSTGRES_PASSWORD=" + postgres.getPassword(),
                "--RABBITMQ_HOST=" + rabbitMq.getHost(),
                "--spring.rabbitmq.port=" + rabbitMq.getAmqpPort(),
                "--RABBITMQ_USER=" + rabbitMq.getAdminUsername(),
                "--RABBITMQ_PASSWORD=" + rabbitMq.getAdminPassword(),
                "--spring.rabbitmq.listener.simple.auto-startup=" + autoStartup);
        writers.add(context);
        return context;
    }

    /** Ein eigener Kanal ermöglicht kontrollierten Verlust echter unbestätigter Lieferungen. */
    public Channel newChannel() throws Exception {
        return connection.createChannel();
    }

    /** Nur content_type wird gesetzt: keine Java-Header und keine Konverter-Eigenschaften. */
    public void publish(List<ChatMessage> messages) throws Exception {
        publish(messages, false);
    }

    /** Der optionale Producer-Header darf die Auswertung desselben JSON-Vertrags nicht verändern. */
    public void publish(List<ChatMessage> messages, boolean withTypeHeader) throws Exception {
        AMQP.BasicProperties.Builder builder = new AMQP.BasicProperties.Builder();
        builder.contentType("application/json");
        if (withTypeHeader) {
            Map<String, Object> headers = Map.of("__TypeId__", "ch.benedict.m321.chatservice.dto.ChatMessage");
            builder.headers(headers);
        }
        AMQP.BasicProperties properties = builder.build();
        for (ChatMessage message : messages) {
            byte[] body = body(message);
            publisher.basicPublish("", "chat.persist", properties, body);
        }
        publisher.waitForConfirmsOrDie(5000);
        published += messages.size();
    }

    /** Explizite JSON-Felder halten den Test unabhängig vom produktiven Java-Konverter. */
    public byte[] body(ChatMessage message) throws Exception {
        Map<String, String> values = Map.of("id", message.id().toString(),
                "roomId", message.roomId().toString(), "senderId", message.senderId(),
                "senderName", message.senderName(), "content", message.content(),
                "sentAt", message.sentAt().toString());
        return mapper.writeValueAsBytes(values);
    }

    /** Neue IDs und ein eigener Raum verhindern die Anrechnung fremder Nachrichten. */
    public List<ChatMessage> messages(int count) {
        List<ChatMessage> result = new ArrayList<>();
        UUID room = UUID.randomUUID();
        for (int i = 0; i < count; i++) {
            UUID id = UUID.randomUUID();
            Instant time = Instant.parse("2026-10-02T12:00:00Z");
            ChatMessage message = new ChatMessage(id, room, "review", "Review", "Message-" + i, time);
            result.add(message);
        }
        return result;
    }

    /** Vergleicht alle Felder und Queue-Zähler; gespeicherte Zeilen allein beweisen kein ACK. */
    public void assertCompleted(List<ChatMessage> messages, int consumers, Duration limit) {
        await().pollInterval(Duration.ofMillis(200)).atMost(limit).untilAsserted(() -> {
            assertStored(messages);
            assertQueue("chat.persist", 0, 0, consumers);
            JsonNode state = management("queues/%2F/chat.persist", null);
            JsonNode statistics = state.path("message_stats");
            long acknowledged = statistics.path("ack").asLong(-1);
            assertTrue(acknowledged >= published, "Broker statistics must include the new deliveries");
            assertQueue("chat.dlq", 0, 0, 0);
        });
    }

    /** Die unabhängige Verbindung liest nur committed Werte und prüft auch deren Unverändertheit. */
    public void assertStored(ChatMessage message) {
        UUID id = message.id();
        List<Map<String, Object>> rows = database.queryForList("SELECT * FROM message WHERE id = ?", id);
        // Eine noch fehlende Zeile ist beim Polling eine unerfüllte Erwartung, kein sofortiger Abbruch.
        int rowCount = rows.size();
        assertEquals(1, rowCount);
        Map<String, Object> row = rows.getFirst();
        assertStoredFields(message, row);
    }

    /** Eine Sammelabfrage vermeidet tausend Verbindungsaufbauten innerhalb der Prüfungsfrist. */
    public void assertStored(List<ChatMessage> messages) {
        List<String> placeholders = new ArrayList<>();
        List<UUID> ids = new ArrayList<>();
        for (ChatMessage message : messages) {
            placeholders.add("?");
            ids.add(message.id());
        }
        String parameters = String.join(",", placeholders);
        String sql = "SELECT * FROM message WHERE id IN (" + parameters + ")";
        Object[] arguments = ids.toArray();
        List<Map<String, Object>> rows = database.queryForList(sql, arguments);
        int expectedCount = messages.size();
        int actualCount = rows.size();
        assertEquals(expectedCount, actualCount);
        Map<UUID, Map<String, Object>> byId = new HashMap<>();
        for (Map<String, Object> row : rows) {
            UUID id = (UUID) row.get("id");
            byId.put(id, row);
        }
        for (ChatMessage message : messages) {
            UUID id = message.id();
            Map<String, Object> row = byId.get(id);
            assertTrue(row != null, "Expected message must be present");
            assertStoredFields(message, row);
        }
    }

    /** Beide Abfragewege vergleichen dieselben sechs unveränderten Vertragsfelder. */
    private void assertStoredFields(ChatMessage message, Map<String, Object> row) {
        assertEquals(message.id(), row.get("id"));
        assertEquals(message.roomId(), row.get("room_id"));
        assertEquals(message.senderId(), row.get("sender_id"));
        assertEquals(message.senderName(), row.get("sender_name"));
        assertEquals(message.content(), row.get("content"));
        Timestamp stored = (Timestamp) row.get("sent_at");
        Instant actualTime = stored.toInstant();
        assertEquals(message.sentAt(), actualTime);
    }

    /** Die Management-Statistik muss alle Felder liefern; fehlende Werte sind kein Null-Erfolg. */
    public void assertQueue(String name, int ready, int unacknowledged, int consumers) throws Exception {
        JsonNode state = management("queues/%2F/" + name, null);
        // Passive Deklaration liest die aktuelle Queue-Tiefe statt verzögerter Management-Statistik.
        AMQP.Queue.DeclareOk queue = publisher.queueDeclarePassive(name);
        int actualReady = queue.getMessageCount();
        int actualUnacknowledged = state.path("messages_unacknowledged").asInt(-1);
        int actualConsumers = state.path("consumers").asInt(-1);
        assertEquals(ready, actualReady);
        assertEquals(unacknowledged, actualUnacknowledged);
        assertEquals(consumers, actualConsumers);
    }

    /** DLQ-Diagnosen geben gelesene Lieferungen zurück und zählen IDs nur einmal. */
    public Set<UUID> deadLetterIds() throws Exception {
        String request = "{\"count\":10000,\"ackmode\":\"ack_requeue_true\",\"encoding\":\"auto\",\"truncate\":1000000}";
        JsonNode deliveries = management("queues/%2F/chat.dlq/get", request);
        Set<UUID> result = new HashSet<>();
        for (JsonNode delivery : deliveries) {
            String payload = delivery.path("payload").asText();
            JsonNode message = mapper.readTree(payload);
            String id = message.path("id").asText();
            result.add(UUID.fromString(id));
        }
        return result;
    }

    /** Beobachtet Brokerzustände mit kurzer Anfragefrist, ohne einen Host-Port im Produkt einzuführen. */
    private JsonNode management(String path, String body) throws Exception {
        String address = "http://" + rabbitMq.getHost() + ":" + rabbitMq.getHttpPort() + "/api/" + path;
        URI uri = URI.create(address);
        String credentials = rabbitMq.getAdminUsername() + ":" + rabbitMq.getAdminPassword();
        byte[] bytes = credentials.getBytes(StandardCharsets.UTF_8);
        Base64.Encoder encoder = Base64.getEncoder();
        String authorization = encoder.encodeToString(bytes);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri);
        builder.timeout(Duration.ofSeconds(1));
        builder.header("Authorization", "Basic " + authorization);
        if (body != null) {
            builder.header("Content-Type", "application/json");
            HttpRequest.BodyPublisher payload = HttpRequest.BodyPublishers.ofString(body);
            builder.POST(payload);
        }
        HttpRequest request = builder.build();
        HttpResponse.BodyHandler<String> handler = HttpResponse.BodyHandlers.ofString();
        HttpResponse<String> response = http.send(request, handler);
        int status = response.statusCode();
        assertEquals(200, status);
        String responseBody = response.body();
        return mapper.readTree(responseBody);
    }

    /** Nur die eigenen Testcontainer werden beendet; Compose-Daten bleiben unberührt. */
    @Override
    public void close() throws Exception {
        for (ConfigurableApplicationContext writer : writers) {
            writer.close();
        }
        connection.close();
        http.close();
        rabbitMq.stop();
        postgres.stop();
    }
}
