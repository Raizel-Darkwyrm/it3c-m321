package ch.benedict.m321.batchwriter.messaging;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import ch.benedict.m321.batchwriter.support.WriterTestStack;
import ch.benedict.m321.batchwriter.service.BatchWriteService;
import ch.benedict.m321.batchwriter.service.MessageBatch;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import com.rabbitmq.client.Envelope;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;

/** S5 prüft echte rohe AMQP-Duplikate vor und nach Commit sowie zusammen mit einer neuen ID. */
class DuplicateMessageIntegrationTest {

    /** Ein Producer-Typheader darf keine Abhängigkeit zur Producer-Javaklasse erzeugen. */
    @Test
    void acceptsProducerTypeHeaderWithoutLoadingProducerClass() throws Exception {
        try (WriterTestStack stack = new WriterTestStack()) {
            stack.startWriter(true);
            List<ChatMessage> messages = stack.messages(1);
            stack.publish(messages, true);
            stack.assertCompleted(messages, 1, Duration.ofSeconds(20));
        }
    }

    /** Kontrollierte Zeit erzwingt denselben Stapel; Datenbank, Kanal und ACKs bleiben echt. */
    @Test
    void commitsRawDuplicatesAndNewIdInSameBatch() throws Exception {
        try (WriterTestStack stack = new WriterTestStack()) {
            ConfigurableApplicationContext context = stack.startWriter(false);
            BatchWriteService writer = context.getBean(BatchWriteService.class);
            AtomicLong time = new AtomicLong();
            MessageBatch batch = new MessageBatch(time::get);
            ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
            MessageDecoder decoder = new MessageDecoder();
            MessageConsumer consumer = new MessageConsumer(decoder, writer, batch, scheduler);
            List<ChatMessage> messages = stack.messages(2);
            ChatMessage first = messages.getFirst();
            ChatMessage second = messages.getLast();
            stack.publish(List.of(first, first, second));
            try (Channel channel = stack.newChannel()) {
                for (int i = 0; i < 3; i++) {
                    GetResponse delivery = channel.basicGet("chat.persist", false);
                    assertNotNull(delivery);
                    MessageProperties properties = new MessageProperties();
                    properties.setContentType("application/json");
                    Envelope envelope = delivery.getEnvelope();
                    long tag = envelope.getDeliveryTag();
                    properties.setDeliveryTag(tag);
                    byte[] body = delivery.getBody();
                    Message message = new Message(body, properties);
                    consumer.onMessage(message, channel);
                }
                int buffered = batch.size();
                assertEquals(3, buffered);
                ArgumentCaptor<Runnable> timer = ArgumentCaptor.forClass(Runnable.class);
                verify(scheduler).schedule(timer.capture(), eq(200L), eq(TimeUnit.MILLISECONDS));
                Runnable timeout = timer.getValue();
                time.set(200_000_000L);
                timeout.run();
                stack.assertCompleted(messages, 0, Duration.ofSeconds(20));
            } finally {
                consumer.shutdown();
            }
        }
    }

    /** Alle Lieferungen müssen abgeschlossen werden, auch wenn ein Insert keine neue Zeile erzeugt. */
    @Test
    void acceptsRawDuplicatesBeforeAndAfterCommit() throws Exception {
        try (WriterTestStack stack = new WriterTestStack()) {
            List<ChatMessage> messages = stack.messages(2);
            ChatMessage first = messages.getFirst();
            ChatMessage second = messages.getLast();
            List<ChatMessage> duplicates = List.of(first, first);
            stack.publish(duplicates);
            stack.startWriter(true);
            Duration limit = Duration.ofSeconds(20);
            stack.assertCompleted(List.of(first), 1, limit);
            stack.publish(List.of(first));
            stack.assertCompleted(List.of(first), 1, limit);
            stack.publish(List.of(first, second));
            stack.assertCompleted(messages, 1, limit);
            Integer rows = stack.database.queryForObject("SELECT count(*) FROM message", Integer.class);
            assertEquals(2, rows);
        }
    }
}
