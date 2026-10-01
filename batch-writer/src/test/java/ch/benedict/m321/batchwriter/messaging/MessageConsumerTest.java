package ch.benedict.m321.batchwriter.messaging;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import ch.benedict.m321.batchwriter.service.BatchWriteService;
import ch.benedict.m321.batchwriter.service.MessageBatch;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Prüft die Commit-/Bestätigungsgrenze mit kontrollierter Zeit ohne Broker oder Datenbank. */
class MessageConsumerTest {

    private BatchWriteService writer;
    private Channel channel;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> deadline;
    private AtomicLong time;
    private MessageConsumer consumer;

    /** Der echte Decoder und Puffer bleiben aktiv; nur Umgebung und Schreibresultat werden ersetzt. */
    @BeforeEach
    void prepareConsumer() throws InterruptedException {
        writer = mock(BatchWriteService.class);
        UUID instanceId = UUID.randomUUID();
        when(writer.instanceId()).thenReturn(instanceId);
        when(writer.write(anyList())).thenReturn(true);
        channel = mock(Channel.class);
        when(channel.isOpen()).thenReturn(true);
        scheduler = mock(ScheduledExecutorService.class);
        deadline = mock(ScheduledFuture.class);
        doReturn(deadline).when(scheduler).schedule(any(Runnable.class), eq(200L), eq(TimeUnit.MILLISECONDS));
        time = new AtomicLong();
        MessageBatch batch = new MessageBatch(time::get);
        MessageDecoder decoder = new MessageDecoder();
        consumer = new MessageConsumer(decoder, writer, batch, scheduler);
        clearInvocations(writer);
    }

    /** Vor der Frist bleibt die erste Lieferung offen; ohne Folgeeingang schreibt der Zeitgeber. */
    @Test
    void writesSingleMessageOnTimeout() throws Exception {
        Message message = message(7);
        consumer.onMessage(message, channel);
        verifyNoInteractions(writer);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        Runnable timeout = scheduledTimeout();
        time.set(200_000_000L);
        timeout.run();
        verify(writer).write(anyList());
        verify(channel).basicAck(7, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    /** Ein voller Stapel schreibt sofort; auch ein später aufgerufener alter Timer schreibt ihn nicht doppelt. */
    @Test
    void writes500MessagesBeforeTimeout() throws Exception {
        doAnswer(invocation -> {
            List<ChatMessage> messages = invocation.getArgument(0);
            int size = messages.size();
            assertEquals(500, size);
            verify(channel, never()).basicAck(anyLong(), anyBoolean());
            return true;
        }).when(writer).write(anyList());
        for (int i = 1; i <= 500; i++) {
            Message message = message(i);
            consumer.onMessage(message, channel);
        }
        Runnable timeout = scheduledTimeout();
        time.set(200_000_000L);
        timeout.run();
        verify(writer).write(anyList());
        for (int i = 1; i <= 500; i++) {
            verify(channel).basicAck(i, false);
        }
        verify(deadline).cancel(false);
    }

    /** Weitere Lieferungen verschieben den einmal gestarteten Zeitgeber nicht. */
    @Test
    void keepsFirstDeadline() throws Exception {
        Message first = message(1);
        consumer.onMessage(first, channel);
        time.set(150_000_000L);
        Message second = message(2);
        consumer.onMessage(second, channel);
        Runnable timeout = scheduledTimeout();
        time.set(200_000_000L);
        timeout.run();
        verify(channel).basicAck(1, false);
        verify(channel).basicAck(2, false);
        verify(writer).write(anyList());
    }

    /** Ein Eingang nach Fristablauf darf den alten Stapel nicht weiter vergrössern. */
    @Test
    void releasesExpiredBatchBeforeAddingNextMessage() throws Exception {
        Message first = message(1);
        consumer.onMessage(first, channel);
        time.set(201_000_000L);
        Message second = message(2);
        consumer.onMessage(second, channel);
        verify(channel).basicAck(1, false);
        verify(channel, never()).basicAck(2, false);
        verify(writer).write(anyList());
    }

    /** Ungültige Bodies erhalten nur für ihren eigenen Tag ein endgültiges NACK. */
    @Test
    void rejectsInvalidMessageWithoutWriting() throws Exception {
        Message valid = message(9);
        MessageProperties properties = valid.getMessageProperties();
        byte[] body = "invalid".getBytes(StandardCharsets.UTF_8);
        Message invalid = new Message(body, properties);
        consumer.onMessage(invalid, channel);
        verify(channel).basicNack(9, false, false);
        verifyNoInteractions(writer, scheduler);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    /** Ein erschöpfter Schreibablauf führt erst nach seiner Rückkehr zu NACKs für alle Einträge. */
    @Test
    void rejectsBatchAfterExhaustedAttempts() throws Exception {
        doAnswer(invocation -> {
            verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
            return false;
        }).when(writer).write(anyList());
        Message first = message(1);
        Message second = message(2);
        consumer.onMessage(first, channel);
        consumer.onMessage(second, channel);
        Runnable timeout = scheduledTimeout();
        time.set(200_000_000L);
        timeout.run();
        verify(channel).basicNack(1, false, false);
        verify(channel).basicNack(2, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    /** Ein ACK-Fehler darf keinen erneuten Datenbankaufruf und keine DLQ-Ablehnung auslösen. */
    @Test
    void closesChannelWhenAcknowledgementFails() throws Exception {
        IOException failure = new IOException("Channel lost");
        doThrow(failure).when(channel).basicAck(1, false);
        Message first = message(1);
        Message second = message(2);
        consumer.onMessage(first, channel);
        consumer.onMessage(second, channel);
        Runnable timeout = scheduledTimeout();
        time.set(200_000_000L);
        timeout.run();
        verify(writer).write(anyList());
        verify(channel).abort();
        verify(channel, never()).basicAck(2, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    /** Geschlossene Empfangskanäle dürfen keine Schreibarbeit mit veralteten Tags beginnen. */
    @Test
    void ignoresDeliveryFromClosedChannel() {
        when(channel.isOpen()).thenReturn(false);
        Message message = message(1);
        consumer.onMessage(message, channel);
        verifyNoInteractions(writer, scheduler);
    }

    /** Ein gestoppter Zeitgeber und neue Eingänge dürfen keine weiteren Stapel beginnen. */
    @Test
    void stopsTimerAndRejectsFurtherWork() {
        Message message = message(1);
        consumer.shutdown();
        consumer.onMessage(message, channel);
        verify(scheduler).shutdownNow();
        verifyNoInteractions(writer);
    }

    /** Liefert die geplante Aufgabe, damit der Test den Fristablauf ohne Schlafen auslösen kann. */
    private Runnable scheduledTimeout() {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(captor.capture(), eq(200L), eq(TimeUnit.MILLISECONDS));
        return captor.getValue();
    }

    /** Rohe JSON-Nachrichten benötigen keinen Java-Typheader; jeder Eingang erhält einen eigenen Tag. */
    private Message message(long deliveryTag) {
        String json = """
                {"id":"123e4567-e89b-12d3-a456-426614174000",
                 "roomId":"123e4567-e89b-12d3-a456-426614174001",
                 "senderId":"anna","senderName":"Anna","content":"Hallo",
                 "sentAt":"2026-10-01T12:00:00Z"}
                """;
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        properties.setDeliveryTag(deliveryTag);
        return new Message(body, properties);
    }
}
