package ch.benedict.m321.batchwriter.messaging;

import ch.benedict.m321.batchwriter.config.QueueNames;
import ch.benedict.m321.batchwriter.dto.ChatMessage;
import ch.benedict.m321.batchwriter.service.BatchWriteService;
import ch.benedict.m321.batchwriter.service.MessageBatch;
import com.rabbitmq.client.Channel;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.listener.AsyncConsumerStartedEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Serialisiert Empfang, Fristauslösung und Bestätigung; nur der Commit erlaubt ein ACK. */
@Component
@Slf4j
public class MessageConsumer {

    private final MessageDecoder decoder;
    private final BatchWriteService writer;
    private final ScheduledExecutorService scheduler;
    private final UUID instanceId;
    private MessageBatch batch;
    private ScheduledFuture<?> deadline;
    private Channel receivingChannel;
    private volatile boolean stopping;

    /** Ein eigener Zeitgeber darf auch dann auslösen, wenn keine weitere Nachricht eintrifft. */
    @Autowired
    public MessageConsumer(MessageDecoder decoder, BatchWriteService writer) {
        this.decoder = decoder;
        this.writer = writer;
        instanceId = writer.instanceId();
        batch = new MessageBatch();
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        executor.setRemoveOnCancelPolicy(true);
        scheduler = executor;
    }

    /** Kontrollierbare Zeit und Planung ermöglichen Tests derselben Verarbeitung ohne Schlafen. */
    MessageConsumer(MessageDecoder decoder, BatchWriteService writer, MessageBatch batch,
                    ScheduledExecutorService scheduler) {
        this.decoder = decoder;
        this.writer = writer;
        instanceId = writer.instanceId();
        this.batch = batch;
        this.scheduler = scheduler;
    }

    /** Erst das Start-Ereignis des Listener-Containers meldet den Consumer als bereit. */
    @EventListener
    public void onConsumerStarted(AsyncConsumerStartedEvent event) {
        log.info("Consumer started: instance={}, queue=chat.persist, prefetch=500, batchSize=500, batchWaitMs=200",
                instanceId);
    }

    /** Gibt beim Sammeln sofort zurück, statt den Empfang weiterer Lieferungen zu blockieren. */
    @RabbitListener(id = "messageConsumer", queues = QueueNames.PERSIST_QUEUE)
    public synchronized void onMessage(Message message, Channel channel) {
        if (stopping || !channel.isOpen()) {
            return;
        }
        selectChannel(channel);
        List<PendingMessage> expired = batch.releaseIfExpired();
        writeBatch(expired);
        if (stopping || !channel.isOpen()) {
            return;
        }
        PendingMessage pending = decode(message, channel);
        if (pending == null) {
            return;
        }
        int previousSize = batch.size();
        List<PendingMessage> ready = batch.add(pending);
        if (!ready.isEmpty()) {
            writeBatch(ready);
        } else if (previousSize == 0) {
            scheduleDeadline();
        }
    }

    /** Alte Tags dürfen beim nächsten Empfangskanal nicht in einen neuen Stapel gelangen. */
    private void selectChannel(Channel channel) {
        if (receivingChannel != null && receivingChannel != channel) {
            cancelDeadline();
            batch = new MessageBatch();
        }
        receivingChannel = channel;
    }

    /** Nur ungültige Eingaben werden hier abgelehnt; gültige Lieferungen bleiben offen. */
    private PendingMessage decode(Message message, Channel channel) {
        MessageProperties properties = message.getMessageProperties();
        long deliveryTag = properties.getDeliveryTag();
        byte[] body = message.getBody();
        String contentType = properties.getContentType();
        String contentEncoding = properties.getContentEncoding();
        try {
            ChatMessage decoded = decoder.decode(body, contentType, contentEncoding);
            return new PendingMessage(decoded, channel, deliveryTag);
        } catch (IllegalArgumentException exception) {
            String reason = exception.getMessage();
            log.warn("Invalid message: instance={}, tag={}, reason={}, target=chat.dlq",
                    instanceId, deliveryTag, reason);
            reject(channel, deliveryTag);
            return null;
        }
    }

    /** Plant nur die erste Lieferung eines Stapels; Folgeeingänge verschieben die Frist nicht. */
    private void scheduleDeadline() {
        try {
            deadline = scheduler.schedule(this::onTimeout, 200, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException exception) {
            if (!stopping) {
                throw exception;
            }
        }
    }

    /** Dieselbe Sperre wie beim Empfang verhindert doppeltes Schreiben durch konkurrierende Auslöser. */
    private synchronized void onTimeout() {
        if (stopping) {
            return;
        }
        List<PendingMessage> expired = batch.releaseIfExpired();
        writeBatch(expired);
    }

    /** Übergibt nur die Nutzdaten; Empfangskanal und Tags bleiben bis zum Ergebnis lokal erhalten. */
    private void writeBatch(List<PendingMessage> ready) {
        if (ready.isEmpty()) {
            return;
        }
        cancelDeadline();
        PendingMessage first = ready.getFirst();
        Channel channel = first.channel();
        if (stopping || !channel.isOpen()) {
            return;
        }
        List<ChatMessage> messages = new ArrayList<>();
        for (PendingMessage pending : ready) {
            ChatMessage message = pending.message();
            messages.add(message);
        }
        boolean committed;
        try {
            committed = writer.write(messages);
        } catch (InterruptedException exception) {
            Thread currentThread = Thread.currentThread();
            currentThread.interrupt();
            closeChannel(channel);
            return;
        } catch (RuntimeException exception) {
            Class<?> errorType = exception.getClass();
            log.error("Unexpected batch processing failure: instance={}, error={}", instanceId, errorType);
            closeChannel(channel);
            return;
        }
        completeBatch(ready, committed);
    }

    /** Brokerfehler liegen ausserhalb des Schreibaufrufs und können keinen DB-Retry auslösen. */
    private void completeBatch(List<PendingMessage> ready, boolean committed) {
        for (PendingMessage pending : ready) {
            Channel channel = pending.channel();
            long deliveryTag = pending.deliveryTag();
            if (!channel.isOpen()) {
                return;
            }
            try {
                if (committed) {
                    channel.basicAck(deliveryTag, false);
                } else {
                    channel.basicNack(deliveryTag, false, false);
                }
            } catch (IOException | RuntimeException exception) {
                log.warn("Batch acknowledgement failed: instance={}, tag={}", instanceId, deliveryTag);
                closeChannel(channel);
                return;
            }
        }
    }

    /** Ein einzelnes NACK darf keine älteren, noch ungespeicherten Lieferungen einschliessen. */
    private void reject(Channel channel, long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, false);
        } catch (IOException | RuntimeException exception) {
            log.warn("Message rejection failed: instance={}, tag={}", instanceId, deliveryTag);
            closeChannel(channel);
        }
    }

    /** Nach einem Kanalfehler bleiben offene Lieferungen für erneute Zustellung verfügbar. */
    private void closeChannel(Channel channel) {
        log.warn("Closing consumer channel: instance={}, queue=chat.persist", instanceId);
        try {
            channel.abort();
        } catch (IOException | RuntimeException exception) {
            log.warn("Failed to close consumer channel: instance={}", instanceId);
        }
    }

    /** Ein voller oder bereits freigegebener Stapel benötigt keinen weiteren Zeitauftrag. */
    private void cancelDeadline() {
        if (deadline != null) {
            deadline.cancel(false);
            deadline = null;
        }
    }

    /** Verhindert neue Arbeit und beendet den eigenen Zeitgeber beim Zerstören der Bean. */
    @PreDestroy
    public void shutdown() {
        stopping = true;
        scheduler.shutdownNow();
    }
}
