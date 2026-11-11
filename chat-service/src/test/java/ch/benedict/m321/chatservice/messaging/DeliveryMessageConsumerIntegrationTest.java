package ch.benedict.m321.chatservice.messaging;

import ch.benedict.m321.chatservice.config.QueueNames;
import ch.benedict.m321.chatservice.dto.ChatMessage;
import ch.benedict.m321.chatservice.service.MessageReplayBuffer;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Prüft den vollständigen Brokerweg bis in den lokalen Replay-Puffer. */
@SpringBootTest
@Testcontainers
class DeliveryMessageConsumerIntegrationTest {

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private MessageReplayBuffer replayBuffer;

    /** Fanout, JSON-Konvertierung, Raumfilter und UUID-Deduplizierung müssen zusammenwirken. */
    @Test
    void consumesFanoutMessageOnceAndKeepsRoomOrder() {
        UUID roomA = UUID.randomUUID();
        UUID roomB = UUID.randomUUID();
        ChatMessage cursor = message(roomA, "cursor");
        ChatMessage otherRoom = message(roomB, "other");
        ChatMessage expected = message(roomA, "expected");

        publish(cursor);
        publish(otherRoom);
        publish(expected);
        publish(expected);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<ChatMessage> replay = replayBuffer.after(roomA, cursor.id());
            assertEquals(List.of(expected), replay);
        });
    }

    /** Sendet genau wie der Produktivpublisher an den bestehenden Fanout-Exchange. */
    private void publish(ChatMessage message) {
        rabbitTemplate.convertAndSend(QueueNames.DELIVERY_EXCHANGE, "", message);
    }

    /** Baut vollständige Vertragsnachrichten für die echte JSON-Konvertierung. */
    private ChatMessage message(UUID roomId, String content) {
        UUID messageId = UUID.randomUUID();
        Instant sentAt = Instant.now();
        return new ChatMessage(messageId, roomId, "sender", "Sender", content, sentAt);
    }
}
