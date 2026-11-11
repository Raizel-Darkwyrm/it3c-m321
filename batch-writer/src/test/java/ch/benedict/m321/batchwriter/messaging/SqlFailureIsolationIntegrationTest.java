package ch.benedict.m321.batchwriter.messaging;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import ch.benedict.m321.batchwriter.service.BatchWriteService;
import ch.benedict.m321.batchwriter.service.MessageBatch;
import ch.benedict.m321.batchwriter.support.WriterTestStack;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

/** Ein zusätzlicher Test-Constraint provoziert echte INSERT-Fehler hinter dem unveränderten JSON-Decoder. */
class SqlFailureIsolationIntegrationTest {

    /** Ohne Datenfehler bleibt der effiziente Weg mit einer einzigen Transaktion und leerer DLQ erhalten. */
    @Test
    void commits500ValidDeliveriesInOneTransaction() throws Exception {
        try (WriterTestStack stack = new WriterTestStack()) {
            List<ChatMessage> messages = stack.messages(500);
            deliverOneBatch(stack, messages);
            stack.assertCompleted(messages, 0, Duration.ofSeconds(20));
            Integer transactions = stack.database.queryForObject(
                    "SELECT count(DISTINCT xmin::text) FROM message", Integer.class);
            assertEquals(1, transactions);
        }
    }

    /** Anfang, Mitte und Ende müssen denselben Rollback und danach 499 unabhängige erfolgreiche Commits ergeben. */
    @ParameterizedTest
    @ValueSource(ints = {0, 249, 499})
    void stores499AndDeadLettersOnlySqlFailure(int invalidPosition) throws Exception {
        try (WriterTestStack stack = new WriterTestStack()) {
            addTestConstraint(stack);
            List<ChatMessage> messages = stack.messages(500);
            ChatMessage original = messages.get(invalidPosition);
            ChatMessage invalid = invalidMessage(original);
            messages.set(invalidPosition, invalid);
            List<ChatMessage> expected = new ArrayList<>(messages);
            expected.remove(invalidPosition);

            deliverOneBatch(stack, messages);

            assertMixedCompleted(stack, expected, invalid, 0);
            Integer transactions = stack.database.queryForObject(
                    "SELECT count(DISTINCT xmin::text) FROM message", Integer.class);
            assertEquals(499, transactions);
        }
    }

    /** Echte konkurrierende Consumer müssen Duplikate bestätigen und nur den SQL-Datenfehler ablehnen. */
    @Test
    void isolatesFailureWithTwoWritersAndDuplicate() throws Exception {
        try (WriterTestStack stack = new WriterTestStack()) {
            addTestConstraint(stack);
            stack.startWriter(true);
            stack.startWriter(true);
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                stack.assertQueue("chat.persist", 0, 0, 2);
            });
            List<ChatMessage> expected = stack.messages(500);
            List<ChatMessage> source = stack.messages(1);
            ChatMessage original = source.getFirst();
            ChatMessage invalid = invalidMessage(original);
            List<ChatMessage> deliveries = new ArrayList<>(expected);
            ChatMessage duplicate = expected.getFirst();
            deliveries.add(duplicate);
            deliveries.add(invalid);
            stack.publish(deliveries);
            assertMixedCompleted(stack, expected, invalid, 2);
        }
    }

    /** Nur der isolierte PostgreSQL-Testcontainer erhält die zusätzliche fachliche Einschränkung. */
    private void addTestConstraint(WriterTestStack stack) {
        stack.database.execute("ALTER TABLE message ADD CONSTRAINT test_content CHECK (content <> 'SQL-invalid')");
    }

    /** Alle sechs JSON-Felder bleiben gültig; ausschliesslich PostgreSQL lehnt diesen Inhalt ab. */
    private ChatMessage invalidMessage(ChatMessage original) {
        UUID id = original.id();
        UUID roomId = original.roomId();
        Instant sentAt = original.sentAt();
        return new ChatMessage(id, roomId, "review", "Review", "SQL-invalid", sentAt);
    }

    /** Kontrollierte Sammelzeit erzwingt exakt 500 Lieferungen im selben Stapel; DB, Decoder und ACKs bleiben echt. */
    private void deliverOneBatch(WriterTestStack stack, List<ChatMessage> messages) throws Exception {
        ConfigurableApplicationContext context = stack.startWriter(false);
        BatchWriteService writer = context.getBean(BatchWriteService.class);
        MessageDecoder decoder = new MessageDecoder();
        AtomicLong time = new AtomicLong();
        MessageBatch batch = new MessageBatch(time::get);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        MessageConsumer consumer = new MessageConsumer(decoder, writer, batch, scheduler);
        stack.publish(messages);
        try (Channel channel = stack.newChannel()) {
            for (int i = 0; i < messages.size(); i++) {
                GetResponse delivery = channel.basicGet("chat.persist", false);
                assertNotNull(delivery);
                Envelope envelope = delivery.getEnvelope();
                long tag = envelope.getDeliveryTag();
                MessageProperties properties = new MessageProperties();
                properties.setContentType("application/json");
                properties.setDeliveryTag(tag);
                byte[] body = delivery.getBody();
                Message message = new Message(body, properties);
                consumer.onMessage(message, channel);
            }
            // Vor Kanalschluss prüfen: Offene Tags dürfen nicht durch automatisches Requeue versteckt werden.
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                stack.assertQueue("chat.persist", 0, 0, 0);
            });
        } finally {
            consumer.shutdown();
        }
    }

    /** Vollständige Feldprüfung und konkrete DLQ-ID verhindern, dass ein gültiger Nachbar unbemerkt verloren geht. */
    private void assertMixedCompleted(WriterTestStack stack, List<ChatMessage> expected,
                                      ChatMessage invalid, int consumers) {
        UUID invalidId = invalid.id();
        Set<UUID> expectedDeadLetters = Set.of(invalidId);
        int expectedCount = expected.size();
        await().pollInterval(Duration.ofMillis(200)).atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            stack.assertStored(expected);
            Integer rows = stack.database.queryForObject("SELECT count(*) FROM message", Integer.class);
            assertEquals(expectedCount, rows);
            stack.assertQueue("chat.persist", 0, 0, consumers);
            stack.assertQueue("chat.dlq", 1, 0, 0);
            Set<UUID> deadLetters = stack.deadLetterIds();
            assertEquals(expectedDeadLetters, deadLetters);
        });
    }
}
