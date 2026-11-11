package ch.benedict.m321.chatservice.messaging;

import ch.benedict.m321.chatservice.ChatServiceApplication;
import ch.benedict.m321.chatservice.dto.AcceptedResponse;
import ch.benedict.m321.chatservice.dto.ChatMessage;
import ch.benedict.m321.chatservice.dto.SendMessageRequest;
import ch.benedict.m321.chatservice.service.MessageReplayBuffer;
import ch.benedict.m321.chatservice.support.SseTestClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
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
import java.util.List;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Beweist S7 über eine echte getrennte und wiederaufgenommene SSE-Verbindung. */
@Testcontainers
class StreamReconnectIntegrationTest {

    @Container
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    private final HttpClient httpClient = HttpClient.newHttpClient();

    /** Nach zehn Sekunden Trennung kommen exakt die 50 fehlenden Ereignisse und danach live. */
    @Test
    void replaysFiftyMissingMessagesAndContinuesLive() throws Exception {
        ConfigurableApplicationContext context = startInstance();
        ObjectMapper objectMapper = context.getBean(ObjectMapper.class);
        MessageReplayBuffer replayBuffer = context.getBean(MessageReplayBuffer.class);
        URI baseUri = baseUri(context);
        UUID roomId = UUID.randomUUID();
        UUID cursorId;
        try {
            try (SseTestClient initial = new SseTestClient(objectMapper, baseUri, roomId, null)) {
                initial.start();
                AcceptedResponse cursor = post(baseUri, objectMapper, roomId, "cursor");
                initial.awaitMessages(1, Duration.ofSeconds(15));
                cursorId = cursor.id();
            }

            for (int i = 0; i < 50; i++) {
                post(baseUri, objectMapper, roomId, numbered(i));
            }
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                List<ChatMessage> replay = replayBuffer.after(roomId, cursorId);
                assertEquals(50, replay.size());
            });
            Thread.sleep(10_000);

            try (SseTestClient reconnected = new SseTestClient(objectMapper, baseUri, roomId, cursorId)) {
                reconnected.start();
                reconnected.awaitMessages(50, Duration.ofSeconds(15));
                assertReplayOrder(reconnected.snapshot());

                post(baseUri, objectMapper, roomId, "live-after-reconnect");
                reconnected.awaitMessages(51, Duration.ofSeconds(15));
                List<ChatMessage> all = reconnected.snapshot();
                assertEquals("live-after-reconnect", all.getLast().content());
            }
            assertUnknownCursorReturnsConflict(baseUri, roomId);
        } finally {
            context.close();
        }
    }

    /** Startet eine vollständige Testinstanz mit sofortigem Abbau offener Teststreams. */
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

    /** Ermittelt den zufälligen HTTP-Port der Testinstanz. */
    private URI baseUri(ConfigurableApplicationContext context) {
        WebServerApplicationContext webContext = (WebServerApplicationContext) context;
        int port = webContext.getWebServer().getPort();
        return URI.create("http://127.0.0.1:" + port);
    }

    /** Sendet eine Nachricht über denselben REST-Eingang wie ein echter Client. */
    private AcceptedResponse post(URI baseUri, ObjectMapper objectMapper, UUID roomId,
                                  String content) throws Exception {
        SendMessageRequest request = new SendMessageRequest(roomId, "reconnect", "Reconnect", content);
        String json = objectMapper.writeValueAsString(request);
        HttpRequest httpRequest = HttpRequest.newBuilder(baseUri.resolve("/messages"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(202, response.statusCode());
        return objectMapper.readValue(response.body(), AcceptedResponse.class);
    }

    /** Der unbekannte Cursor muss vor dem Öffnen eines Streams mit HTTP 409 enden. */
    private void assertUnknownCursorReturnsConflict(URI baseUri, UUID roomId) throws Exception {
        URI streamUri = baseUri.resolve("/stream?roomId=" + roomId);
        HttpRequest request = HttpRequest.newBuilder(streamUri)
                .header("Last-Event-ID", UUID.randomUUID().toString())
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(409, response.statusCode());
    }

    /** Formatiert die erwartete Reihenfolge unabhängig von lexischen Zahlensprüngen. */
    private String numbered(int number) {
        return String.format("missing-%02d", number);
    }

    /** Vergleicht jede Replay-Position und erkennt damit Lücken und Duplikate. */
    private void assertReplayOrder(List<ChatMessage> replay) {
        assertEquals(50, replay.size());
        for (int i = 0; i < replay.size(); i++) {
            ChatMessage message = replay.get(i);
            assertEquals(numbered(i), message.content());
        }
    }
}
