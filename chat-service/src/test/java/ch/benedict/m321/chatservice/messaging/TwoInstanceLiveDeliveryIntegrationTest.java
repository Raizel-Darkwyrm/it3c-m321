package ch.benedict.m321.chatservice.messaging;

import ch.benedict.m321.chatservice.ChatServiceApplication;
import ch.benedict.m321.chatservice.dto.AcceptedResponse;
import ch.benedict.m321.chatservice.dto.ChatMessage;
import ch.benedict.m321.chatservice.dto.SendMessageRequest;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Beweist S3 über zwei echte Dienstinstanzen, HTTP, SSE und RabbitMQ. */
@Testcontainers
class TwoInstanceLiveDeliveryIntegrationTest {

    @Container
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    private final HttpClient httpClient = HttpClient.newHttpClient();

    /** Beide Raum-A-Clients erhalten jede ID einmal; Raum B bleibt vollständig getrennt. */
    @Test
    void deliversFourHundredMessagesToBothInstancesInSenderOrder() throws Exception {
        ConfigurableApplicationContext first = startInstance();
        ConfigurableApplicationContext second = startInstance();
        ObjectMapper objectMapper = first.getBean(ObjectMapper.class);
        URI firstUri = baseUri(first);
        URI secondUri = baseUri(second);
        UUID roomA = UUID.randomUUID();
        UUID roomB = UUID.randomUUID();
        try (SseTestClient firstRoomA = new SseTestClient(objectMapper, firstUri, roomA, null);
             SseTestClient secondRoomA = new SseTestClient(objectMapper, secondUri, roomA, null);
             SseTestClient roomBClient = new SseTestClient(objectMapper, firstUri, roomB, null)) {
            firstRoomA.start();
            secondRoomA.start();
            roomBClient.start();
            post(firstUri, objectMapper, roomA, "probe-a", "Probe A");
            post(firstUri, objectMapper, roomB, "probe-b", "Probe B");
            firstRoomA.awaitMessages(1, Duration.ofSeconds(15));
            secondRoomA.awaitMessages(1, Duration.ofSeconds(15));
            roomBClient.awaitMessages(1, Duration.ofSeconds(15));
            firstRoomA.clear();
            secondRoomA.clear();
            roomBClient.clear();

            Set<UUID> expectedIds = new HashSet<>();
            for (int i = 0; i < 200; i++) {
                AcceptedResponse fromFirst = post(firstUri, objectMapper, roomA, "sender-a", numbered("a", i));
                AcceptedResponse fromSecond = post(secondUri, objectMapper, roomA, "sender-b", numbered("b", i));
                expectedIds.add(fromFirst.id());
                expectedIds.add(fromSecond.id());
            }

            firstRoomA.awaitMessages(400, Duration.ofSeconds(30));
            secondRoomA.awaitMessages(400, Duration.ofSeconds(30));
            assertMessages(firstRoomA.snapshot(), expectedIds);
            assertMessages(secondRoomA.snapshot(), expectedIds);
            assertTrue(roomBClient.snapshot().isEmpty());
        } finally {
            second.close();
            first.close();
        }
    }

    /** Startet eine vollständige Instanz mit zufälligem internem HTTP-Port. */
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

    /** Ermittelt die nur für den Test lokal erreichbare Adresse einer Instanz. */
    private URI baseUri(ConfigurableApplicationContext context) {
        WebServerApplicationContext webContext = (WebServerApplicationContext) context;
        int port = webContext.getWebServer().getPort();
        return URI.create("http://127.0.0.1:" + port);
    }

    /** Sendet über den echten REST-Eingang und gibt die vom Server erzeugte ID zurück. */
    private AcceptedResponse post(URI baseUri, ObjectMapper objectMapper, UUID roomId,
                                  String senderId, String content) throws Exception {
        SendMessageRequest request = new SendMessageRequest(roomId, senderId, senderId, content);
        String json = objectMapper.writeValueAsString(request);
        HttpRequest httpRequest = HttpRequest.newBuilder(baseUri.resolve("/messages"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(202, response.statusCode());
        return objectMapper.readValue(response.body(), AcceptedResponse.class);
    }

    /** Formatiert Sequenznummern so, dass lexische und numerische Reihenfolge übereinstimmen. */
    private String numbered(String prefix, int number) {
        return String.format("%s-%03d", prefix, number);
    }

    /** Prüft Eindeutigkeit, Vollständigkeit und die Reihenfolge jedes Absenders. */
    private void assertMessages(List<ChatMessage> messages, Set<UUID> expectedIds) {
        Set<UUID> actualIds = new HashSet<>();
        int nextA = 0;
        int nextB = 0;
        for (ChatMessage message : messages) {
            actualIds.add(message.id());
            if (message.senderId().equals("sender-a")) {
                assertEquals(numbered("a", nextA), message.content());
                nextA++;
            } else if (message.senderId().equals("sender-b")) {
                assertEquals(numbered("b", nextB), message.content());
                nextB++;
            }
        }
        assertEquals(200, nextA);
        assertEquals(200, nextB);
        assertEquals(expectedIds, actualIds);
        assertEquals(400, actualIds.size());
    }
}
