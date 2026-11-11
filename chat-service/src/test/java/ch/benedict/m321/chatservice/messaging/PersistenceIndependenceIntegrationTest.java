package ch.benedict.m321.chatservice.messaging;

import ch.benedict.m321.chatservice.ChatServiceApplication;
import ch.benedict.m321.chatservice.config.QueueNames;
import ch.benedict.m321.chatservice.dto.AcceptedResponse;
import ch.benedict.m321.chatservice.dto.ChatMessage;
import ch.benedict.m321.chatservice.dto.SendMessageRequest;
import ch.benedict.m321.chatservice.support.SseTestClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Beweist die Live-Zustellung ohne batch-writer und ohne PostgreSQL. */
@Testcontainers
class PersistenceIndependenceIntegrationTest {

    @Container
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    private final HttpClient httpClient = HttpClient.newHttpClient();

    /** Hundert Nachrichten kommen binnen fünf Sekunden live an und warten zugleich auf Persistenz. */
    @Test
    void deliversWhileAllMessagesWaitInPersistQueue() throws Exception {
        ConfigurableApplicationContext context = startInstance();
        ObjectMapper objectMapper = context.getBean(ObjectMapper.class);
        RabbitAdmin rabbitAdmin = context.getBean(RabbitAdmin.class);
        URI baseUri = baseUri(context);
        UUID roomId = UUID.randomUUID();
        try (SseTestClient client = new SseTestClient(objectMapper, baseUri, roomId, null)) {
            client.start();
            post(baseUri, objectMapper, roomId, "probe");
            client.awaitMessages(1, Duration.ofSeconds(15));
            client.clear();
            rabbitAdmin.purgeQueue(QueueNames.PERSIST_QUEUE);

            Set<UUID> expectedIds = new HashSet<>();
            long started = System.nanoTime();
            for (int i = 0; i < 100; i++) {
                AcceptedResponse accepted = post(baseUri, objectMapper, roomId, "live-" + i);
                expectedIds.add(accepted.id());
            }
            client.awaitMessages(100, Duration.ofSeconds(5));
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

            Set<UUID> actualIds = ids(client.snapshot());
            assertEquals(expectedIds, actualIds);
            assertTrue(elapsedMillis <= 5000);
            Properties properties = rabbitAdmin.getQueueProperties(QueueNames.PERSIST_QUEUE);
            assertEquals(100, properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT));
        } finally {
            context.close();
        }
    }

    /** Startet absichtlich nur den Producer mit RabbitMQ-Verbindung. */
    private ConfigurableApplicationContext startInstance() {
        return new SpringApplicationBuilder(ChatServiceApplication.class)
                .run(
                        "--server.port=0",
                        "--server.shutdown=immediate",
                        "--spring.rabbitmq.host=" + rabbitMq.getHost(),
                        "--spring.rabbitmq.port=" + rabbitMq.getAmqpPort(),
                        "--spring.rabbitmq.username=guest",
                        "--spring.rabbitmq.password=guest",
                        "--spring.main.banner-mode=off",
                        "--logging.level.root=WARN");
    }

    /** Ermittelt die lokale Testadresse des zufälligen HTTP-Ports. */
    private URI baseUri(ConfigurableApplicationContext context) {
        WebServerApplicationContext webContext = (WebServerApplicationContext) context;
        int port = webContext.getWebServer().getPort();
        return URI.create("http://127.0.0.1:" + port);
    }

    /** Sendet über POST und gibt die serverseitig erzeugte Identität zurück. */
    private AcceptedResponse post(URI baseUri, ObjectMapper objectMapper, UUID roomId,
                                  String content) throws Exception {
        SendMessageRequest request = new SendMessageRequest(roomId, "live", "Live", content);
        String json = objectMapper.writeValueAsString(request);
        HttpRequest httpRequest = HttpRequest.newBuilder(baseUri.resolve("/messages"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(202, response.statusCode());
        return objectMapper.readValue(response.body(), AcceptedResponse.class);
    }

    /** Baut ohne Streams die Menge der tatsächlich live empfangenen IDs. */
    private Set<UUID> ids(List<ChatMessage> messages) {
        Set<UUID> ids = new HashSet<>();
        for (ChatMessage message : messages) {
            ids.add(message.id());
        }
        return ids;
    }
}
