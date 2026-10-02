package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import ch.benedict.m321.batchwriter.support.WriterTestStack;
import ch.benedict.m321.batchwriter.repository.MessageRepository;
import ch.benedict.m321.batchwriter.messaging.MessageConsumer;
import ch.benedict.m321.batchwriter.messaging.MessageDecoder;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import com.rabbitmq.client.Envelope;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.anyList;

/** S6 prüft getrennte produktive Anwendungskontexte an derselben Queue und Datenbank. */
class MultipleWritersIntegrationTest {

    /** Ein beobachteter PostgreSQL-Lock belegt den Konflikt zweier echter Inserts derselben ID. */
    @Test
    void acknowledgesOverlappingDuplicateTransactions() throws Exception {
        try (WriterTestStack stack = new WriterTestStack()) {
            ConfigurableApplicationContext firstContext = stack.startWriter(false);
            ConfigurableApplicationContext secondContext = stack.startWriter(false);
            // Echte Repositories mit den Kontext-Verbindungen, ohne einen Spring-Proxy erneut zu mocken.
            JdbcTemplate firstDatabase = firstContext.getBean(JdbcTemplate.class);
            JdbcTemplate secondDatabase = secondContext.getBean(JdbcTemplate.class);
            MessageRepository firstRepository = new MessageRepository(firstDatabase);
            MessageRepository secondRepository = new MessageRepository(secondDatabase);
            MessageRepository first = spy(firstRepository);
            MessageRepository second = spy(secondRepository);
            CountDownLatch inserted = new CountDownLatch(1);
            AtomicInteger secondPid = new AtomicInteger();
            AtomicBoolean overlapObserved = new AtomicBoolean();
            doAnswer(invocation -> {
                invocation.callRealMethod();
                inserted.countDown();
                // Unterhalb des produktiven lock_timeout von 750 ms bleiben; keine Grenzwerte lockern.
                await().pollInterval(Duration.ofMillis(10)).atMost(Duration.ofMillis(500)).until(() -> {
                    int pid = secondPid.get();
                    Integer waiting = stack.database.queryForObject(
                            "SELECT count(*) FROM pg_stat_activity WHERE pid = ? AND wait_event_type = 'Lock'",
                            Integer.class, pid);
                    return waiting == 1;
                });
                overlapObserved.set(true);
                return null;
            }).when(first).insertBatch(anyList());
            doAnswer(invocation -> {
                boolean ready = inserted.await(1, TimeUnit.SECONDS);
                assertTrue(ready, "First transaction must have inserted before the conflicting insert");
                Integer pid = secondDatabase.queryForObject("SELECT pg_backend_pid()", Integer.class);
                secondPid.set(pid);
                return invocation.callRealMethod();
            }).when(second).insertBatch(anyList());
            ScheduledThreadPoolExecutor timeouts = new ScheduledThreadPoolExecutor(2);
            MessageConsumer firstConsumer = createConsumer(firstContext, first, timeouts);
            MessageConsumer secondConsumer = createConsumer(secondContext, second, timeouts);
            try (Channel firstChannel = stack.newChannel(); Channel secondChannel = stack.newChannel()) {
                List<ChatMessage> expected = stack.messages(1);
                ChatMessage message = expected.getFirst();
                stack.publish(List.of(message, message));
                deliver(firstConsumer, firstChannel);
                deliver(secondConsumer, secondChannel);
                stack.assertCompleted(expected, 0, Duration.ofSeconds(20));
                assertTrue(overlapObserved.get(), "A real conflicting lock must have been observed");
                Integer rows = stack.database.queryForObject("SELECT count(*) FROM message", Integer.class);
                assertEquals(1, rows);
            } finally {
                firstConsumer.shutdown();
                secondConsumer.shutdown();
                timeouts.shutdownNow();
            }
        }
    }

    /** Der Prüfpunkt ersetzt nur das Repository; Transaktion, Zeitgrenzen, Retry und ACK bleiben produktiv. */
    private MessageConsumer createConsumer(ConfigurableApplicationContext context, MessageRepository repository,
                                           ScheduledThreadPoolExecutor timeouts) {
        DataSource source = context.getBean(DataSource.class);
        PlatformTransactionManager manager = context.getBean(PlatformTransactionManager.class);
        BatchPersistenceService persistence = new BatchPersistenceService(repository, manager, source, timeouts);
        BatchWriteService writer = new BatchWriteService(persistence);
        MessageDecoder decoder = new MessageDecoder();
        return new MessageConsumer(decoder, writer);
    }

    /** Zwei eigene Kanäle erhalten echte Lieferungen, deren Tags nur auf ihrem Ursprungskanal gelten. */
    private void deliver(MessageConsumer consumer, Channel channel) throws Exception {
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

    /** Zwei Consumer müssen alle IDs eindeutig verarbeiten, ohne eine gleiche Verteilung zu verlangen. */
    @Test
    void persists1000MessagesWithTwoWriters() throws Exception {
        try (WriterTestStack stack = new WriterTestStack()) {
            stack.startWriter(true);
            stack.startWriter(true);
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                stack.assertQueue("chat.persist", 0, 0, 2);
            });
            List<ChatMessage> messages = stack.messages(1000);
            stack.publish(messages);
            stack.assertCompleted(messages, 2, Duration.ofSeconds(60));
            Integer count = stack.database.queryForObject("SELECT count(*) FROM message", Integer.class);
            assertEquals(1000, count);
        }
    }
}
