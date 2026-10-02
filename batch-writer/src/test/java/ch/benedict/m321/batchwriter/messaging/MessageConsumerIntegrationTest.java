package ch.benedict.m321.batchwriter.messaging;

import ch.benedict.m321.batchwriter.BatchWriterApplication;
import ch.benedict.m321.batchwriter.dto.ChatMessage;
import ch.benedict.m321.batchwriter.repository.MessageRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

/** Prüft den produktiven Empfang und die Commit-Grenze gegen echte, isolierte Dienste. */
@SpringBootTest(classes = BatchWriterApplication.class,
        properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@Testcontainers
class MessageConsumerIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = createPostgres();

    @Container
    private static final RabbitMQContainer rabbitMq = createRabbitMq();

    @Autowired
    private RabbitListenerEndpointRegistry listeners;
    @Autowired
    private RabbitAdmin rabbitAdmin;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean
    private MessageRepository repository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicInteger checkedCommits = new AtomicInteger();

    /** Verwendet das produktive Schema statt einer vereinfachten Testtabelle. */
    private static PostgreSQLContainer<?> createPostgres() {
        String moduleDirectory = System.getProperty("basedir");
        Path script = Path.of(moduleDirectory, "..", "postgres", "init", "001-create-message.sql");
        MountableFile initFile = MountableFile.forHostPath(script);
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:16");
        container.withCopyFileToContainer(initFile, "/docker-entrypoint-initdb.d/001-create-message.sql");
        return container;
    }

    /** Kurze Statistikintervalle erlauben den Broker-Nachweis innerhalb des DB-Versuchsbudgets. */
    private static RabbitMQContainer createRabbitMq() {
        RabbitMQContainer container = new RabbitMQContainer("rabbitmq:3.13-management");
        container.withEnv("RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS", "-rabbit collect_statistics_interval 100");
        return container;
    }

    /** Isolierte Testcontainer ersetzen nur Adressen und Zugangsdaten, nicht die Verarbeitungseinstellungen. */
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

    /** Der Prüfpunkt liegt nach dem echten Insert, aber noch vor dem echten Commit. */
    @BeforeEach
    void prepareCommitCheck() {
        listeners.stop();
        rabbitAdmin.purgeQueue("chat.persist", false);
        rabbitAdmin.purgeQueue("chat.dlq", false);
        jdbcTemplate.execute("TRUNCATE public.message");
        checkedCommits.set(0);
        doAnswer(invocation -> {
            List<ChatMessage> messages = invocation.getArgument(0);
            invocation.callRealMethod();
            // Bindet die Sichtbarkeits- und ACK-Prüfung an den Zeitpunkt vor dem echten Commit.
            TransactionSynchronization check = new TransactionSynchronization() {
                /** Eine unabhängige Verbindung darf den Insert vor Commit noch nicht sehen. */
                @Override
                public void beforeCommit(boolean readOnly) {
                    checkBeforeCommit(messages);
                }
            };
            TransactionSynchronizationManager.registerSynchronization(check);
            return null;
        }).when(repository).insertBatch(anyList());
    }

    /** Jeder Fall gibt seinen Consumer frei, bevor die Testdaten des nächsten Falls entstehen. */
    @AfterEach
    void stopConsumer() {
        listeners.stop();
    }

    /** Eine einzige Lieferung muss ohne Folgelieferung durch den Zeitgeber gespeichert werden. */
    @Test
    void commitsSingleMessageWithoutFollowingDelivery() throws Exception {
        List<UUID> ids = publishMessages(1);
        listeners.start();
        assertCompleted(ids);
    }

    /** Ein wartender 500er-Vorrat darf weder im Empfang hängen bleiben noch vor Commit bestätigt werden. */
    @Test
    void commitsBacklogOf500Messages() throws Exception {
        List<UUID> ids = publishMessages(500);
        listeners.start();
        assertCompleted(ids);
    }

    /** Der Test-Thread besitzt keine Writer-Transaktion und liest deshalb nur committed Daten. */
    private void checkBeforeCommit(List<ChatMessage> messages) {
        ChatMessage first = messages.getFirst();
        UUID id = first.id();
        int batchSize = messages.size();
        // Awaitility führt die Prüfung auf einem separaten Thread ohne gebundene JDBC-Verbindung aus.
        await().pollInterval(Duration.ofMillis(50)).atMost(Duration.ofMillis(1500)).untilAsserted(() -> {
            Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM public.message WHERE id = ?",
                    Integer.class, id);
            assertEquals(0, count);
            JsonNode queue = queueState("chat.persist");
            JsonNode unacknowledged = queue.path("messages_unacknowledged");
            int outstanding = unacknowledged.asInt(-1);
            assertTrue(outstanding >= batchSize);
        });
        checkedCommits.incrementAndGet();
    }

    /** Rohe UTF-8-Bodies prüfen den Vertrag ohne Java-Typheader und ohne Producer-Abhängigkeit. */
    private List<UUID> publishMessages(int count) {
        List<UUID> ids = new ArrayList<>();
        UUID roomId = UUID.randomUUID();
        for (int i = 0; i < count; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            String json = """
                    {"id":"%s","roomId":"%s","senderId":"anna","senderName":"Anna",
                     "content":"Consumer test","sentAt":"2026-10-01T12:00:00Z"}
                    """.formatted(id, roomId);
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            MessageProperties properties = new MessageProperties();
            properties.setContentType("application/json");
            Message message = new Message(body, properties);
            rabbitTemplate.send("", "chat.persist", message);
        }
        return ids;
    }

    /** Gespeicherte IDs und beide Queue-Zähler verhindern einen bloss scheinbaren Abschluss. */
    private void assertCompleted(List<UUID> expectedIds) throws Exception {
        await().pollInterval(Duration.ofMillis(100)).atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            List<UUID> stored = jdbcTemplate.queryForList("SELECT id FROM public.message", UUID.class);
            int expectedCount = expectedIds.size();
            int storedCount = stored.size();
            assertEquals(expectedCount, storedCount);
            boolean allIdsStored = stored.containsAll(expectedIds);
            assertTrue(allIdsStored);
            JsonNode queue = queueState("chat.persist");
            assertQueueCounter(queue, "messages_ready", 0);
            assertQueueCounter(queue, "messages_unacknowledged", 0);
            assertQueueCounter(queue, "consumers", 1);
            JsonNode deadLetters = queueState("chat.dlq");
            assertQueueCounter(deadLetters, "messages", 0);
        });
        int commits = checkedCommits.get();
        assertTrue(commits > 0);
    }

    /** Fehlende Statistikfelder dürfen nicht als erfolgreicher Nullwert durchgehen. */
    private void assertQueueCounter(JsonNode queue, String field, int expected) {
        JsonNode value = queue.path(field);
        int actual = value.asInt(-1);
        assertEquals(expected, actual);
    }

    /** Die Management-Abfrage beobachtet Lieferungen, ohne sie aus der Queue zu entnehmen. */
    private JsonNode queueState(String queueName) throws Exception {
        String url = "http://" + rabbitMq.getHost() + ":" + rabbitMq.getHttpPort() + "/api/queues/%2F/" + queueName;
        URI uri = URI.create(url);
        String credentials = rabbitMq.getAdminUsername() + ":" + rabbitMq.getAdminPassword();
        byte[] bytes = credentials.getBytes(StandardCharsets.UTF_8);
        Base64.Encoder encoder = Base64.getEncoder();
        String authorization = encoder.encodeToString(bytes);
        Duration timeout = Duration.ofSeconds(1);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri);
        builder.timeout(timeout);
        builder.header("Authorization", "Basic " + authorization);
        HttpRequest request = builder.GET().build();
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
