package ch.benedict.m321.batchwriter.messaging;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import ch.benedict.m321.batchwriter.service.BatchWriteService;
import ch.benedict.m321.batchwriter.service.MessageBatch;
import ch.benedict.m321.batchwriter.support.WriterTestStack;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.AdditionalAnswers.delegatesTo;

/** Unterbricht echte Brokerkanäle nach Commit; die Wiederzustellung läuft durch den produktiven Listener. */
class ConsumerLifecycleIntegrationTest {

    /** Stop mit Teilstapel darf nichts schreiben; der echte Broker muss die offene Lieferung erneut bereitstellen. */
    @Test
    void redeliversUncommittedPartialBatchAfterStop() throws Exception {
        try (WriterTestStack stack = new WriterTestStack()) {
            ConfigurableApplicationContext context = stack.startWriter(false);
            BatchWriteService writer = context.getBean(BatchWriteService.class);
            AtomicLong time = new AtomicLong();
            MessageBatch batch = new MessageBatch(time::get);
            ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
            MessageDecoder decoder = new MessageDecoder();
            MessageConsumer consumer = new MessageConsumer(decoder, writer, batch, scheduler);
            List<ChatMessage> messages = stack.messages(1);
            stack.publish(messages);
            Channel channel = stack.newChannel();
            try {
                deliver(consumer, channel);
                long started = System.nanoTime();
                consumer.shutdown();
                long elapsed = System.nanoTime() - started;
                assertTrue(elapsed < 5_000_000_000L);
                Integer count = stack.database.queryForObject("SELECT count(*) FROM message", Integer.class);
                assertEquals(0, count);
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                    stack.assertQueue("chat.persist", 1, 0, 0);
                });
                RabbitListenerEndpointRegistry listeners = context.getBean(RabbitListenerEndpointRegistry.class);
                listeners.start();
                stack.assertCompleted(messages, 1, Duration.ofSeconds(20));
            } finally {
                consumer.shutdown();
                if (channel.isOpen()) {
                    channel.abort();
                }
            }
        }
    }

    /** Verlust vor dem ersten oder nach einem ACK darf weder Doppelzeilen noch DLQ-Einträge erzeugen. */
    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void redeliversAfterCommittedChannelLoss(int successfulAcks) throws Exception {
        try (WriterTestStack stack = new WriterTestStack()) {
            ConfigurableApplicationContext context = stack.startWriter(false);
            BatchWriteService writer = context.getBean(BatchWriteService.class);
            List<ChatMessage> messages = stack.messages(2);
            stack.publish(messages);
            Channel realChannel = stack.newChannel();
            try {
                Channel intercepted = mock(Channel.class, delegatesTo(realChannel));
                AtomicInteger attempts = new AtomicInteger();
                doAnswer(invocation -> {
                    int previous = attempts.getAndIncrement();
                    if (previous == successfulAcks) {
                        // Der reale Commit muss bereits von einer unabhängigen Verbindung sichtbar sein.
                        for (ChatMessage message : messages) {
                            stack.assertStored(message);
                        }
                        realChannel.abort();
                        throw new IOException("Channel interrupted after commit");
                    }
                    return invocation.callRealMethod();
                }).when(intercepted).basicAck(anyLong(), anyBoolean());
                MessageDecoder decoder = new MessageDecoder();
                AtomicLong time = new AtomicLong();
                MessageBatch batch = new MessageBatch(time::get);
                ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
                MessageConsumer consumer = new MessageConsumer(decoder, writer, batch, scheduler);
                try {
                    deliver(consumer, intercepted);
                    deliver(consumer, intercepted);
                    ArgumentCaptor<Runnable> timer = ArgumentCaptor.forClass(Runnable.class);
                    verify(scheduler).schedule(timer.capture(), eq(200L), eq(TimeUnit.MILLISECONDS));
                    Runnable deadline = timer.getValue();
                    time.set(200_000_000L);
                    deadline.run();
                    await().atMost(Duration.ofSeconds(10)).until(() -> !realChannel.isOpen());
                } finally {
                    consumer.shutdown();
                }
            } finally {
                if (realChannel.isOpen()) {
                    realChannel.abort();
                }
            }
            RabbitListenerEndpointRegistry listeners = context.getBean(RabbitListenerEndpointRegistry.class);
            listeners.start();
            stack.assertCompleted(messages, 1, Duration.ofSeconds(20));
            Integer rows = stack.database.queryForObject("SELECT count(*) FROM message", Integer.class);
            assertEquals(2, rows);
        }
    }

    /** Manuelles Abholen erzeugt echte offene Delivery-Tags für den gezielt unterbrochenen Consumer. */
    private void deliver(MessageConsumer consumer, Channel channel) throws Exception {
        GetResponse delivery = channel.basicGet("chat.persist", false);
        assertNotNull(delivery);
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        com.rabbitmq.client.Envelope envelope = delivery.getEnvelope();
        long tag = envelope.getDeliveryTag();
        properties.setDeliveryTag(tag);
        byte[] body = delivery.getBody();
        Message message = new Message(body, properties);
        consumer.onMessage(message, channel);
    }
}
