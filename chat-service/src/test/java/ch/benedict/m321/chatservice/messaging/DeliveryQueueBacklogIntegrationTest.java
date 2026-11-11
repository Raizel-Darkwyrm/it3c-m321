package ch.benedict.m321.chatservice.messaging;

import ch.benedict.m321.chatservice.config.QueueNames;
import ch.benedict.m321.chatservice.dto.ChatMessage;
import ch.benedict.m321.chatservice.service.LiveMessageService;
import ch.benedict.m321.chatservice.service.MessageReplayBuffer;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Misst die produktive 1000er-Grenze bei kontrolliert blockierter lokaler Verarbeitung. */
@SpringBootTest
@Testcontainers
class DeliveryQueueBacklogIntegrationTest {

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    @Qualifier("deliveryQueue")
    private Queue deliveryQueue;

    @Autowired
    private LiveMessageService liveMessageService;

    @Autowired
    private MessageReplayBuffer replayBuffer;

    /** 5000 Eingänge bleiben auf 1000 ready begrenzt und der Consumer arbeitet danach weiter. */
    @Test
    void limitsBacklogAndResumesDelivery() {
        UUID roomId = UUID.randomUUID();
        ChatMessage repeated = message(roomId, "backlog");
        synchronized (liveMessageService) {
            for (int i = 0; i < 5000; i++) {
                publish(repeated);
            }
            Properties properties = rabbitAdmin.getQueueProperties(deliveryQueue.getName());
            int ready = ((Number) properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).intValue();
            assertTrue(ready > 0);
            assertTrue(ready <= 1000);
        }

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            Properties properties = rabbitAdmin.getQueueProperties(deliveryQueue.getName());
            assertEquals(0, properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT));
        });

        ChatMessage cursor = message(roomId, "cursor");
        ChatMessage control = message(roomId, "control");
        publish(cursor);
        publish(control);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<ChatMessage> replay = replayBuffer.after(roomId, cursor.id());
            assertFalse(replay.isEmpty());
            assertEquals(control.id(), replay.getLast().id());
        });
    }

    /** Veröffentlicht direkt am Fanout, damit `chat.persist` die Rückstaumessung nicht beeinflusst. */
    private void publish(ChatMessage message) {
        rabbitTemplate.convertAndSend(QueueNames.DELIVERY_EXCHANGE, "", message);
    }

    /** Baut eine vollständige JSON-Vertragsnachricht für den echten Broker. */
    private ChatMessage message(UUID roomId, String content) {
        UUID messageId = UUID.randomUUID();
        Instant sentAt = Instant.now();
        return new ChatMessage(messageId, roomId, "backlog", "Backlog", content, sentAt);
    }
}
