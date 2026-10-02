package ch.benedict.m321.batchwriter.messaging;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import ch.benedict.m321.batchwriter.service.BatchWriteService;
import ch.benedict.m321.batchwriter.service.MessageBatch;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ShutdownListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.ChannelProxy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    /** Fehler zwischen gültigen Einträgen dürfen weder den Puffer leeren noch dessen Frist verschieben. */
    @ParameterizedTest
    @ValueSource(strings = {"json", "type", "field", "contentType", "encoding"})
    void preservesValidBatchAndDeadlineAroundInvalidMessage(String failure) throws Exception {
        Message first = message(1);
        consumer.onMessage(first, channel);
        time.set(150_000_000L);
        Message invalid = invalidMessage(failure);
        consumer.onMessage(invalid, channel);
        verify(channel).basicNack(2, false, false);
        verifyNoInteractions(writer);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        time.set(175_000_000L);
        Message third = message(3);
        consumer.onMessage(third, channel);
        Runnable timeout = scheduledTimeout();
        time.set(200_000_000L);
        timeout.run();

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.captor();
        verify(writer).write(captor.capture());
        List<ChatMessage> stored = captor.getValue();
        int size = stored.size();
        assertEquals(2, size);
        verify(channel).basicAck(1, false);
        verify(channel).basicAck(3, false);
        verify(channel, never()).basicAck(2, false);
        verify(channel, times(1)).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    /** Jede Variante verändert genau eine Vertragsvorgabe einer ansonsten gültigen Lieferung. */
    private Message invalidMessage(String failure) {
        Message original = message(2);
        MessageProperties properties = original.getMessageProperties();
        byte[] originalBody = original.getBody();
        String json = new String(originalBody, StandardCharsets.UTF_8);
        switch (failure) {
            case "json":
                json = "{invalid JSON";
                break;
            case "type":
                json = json.replace("\"content\":\"Hallo\"", "\"content\":42");
                break;
            case "field":
                json = json.replace("\"senderId\":\"anna\",", "");
                break;
            case "contentType":
                properties.setContentType("text/plain");
                break;
            case "encoding":
                properties.setContentEncoding("ISO-8859-1");
                break;
            default:
                throw new IllegalArgumentException("Unknown test case");
        }
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        return new Message(body, properties);
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

    /** Ein wiederverwendeter Proxy darf alte Lieferungsnummern nicht auf den neuen Kanal übertragen. */
    @Test
    void discardsOldTagsWhenProxyChangesTarget() throws Exception {
        ChannelProxy proxy = mock(ChannelProxy.class);
        Channel replacement = mock(Channel.class);
        when(replacement.isOpen()).thenReturn(true);
        when(proxy.getTargetChannel()).thenReturn(channel, replacement);
        Message first = message(71);
        consumer.onMessage(first, proxy);
        Message second = message(1);
        consumer.onMessage(second, proxy);
        ArgumentCaptor<Runnable> tasks = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler, times(2)).schedule(tasks.capture(), eq(200L), eq(TimeUnit.MILLISECONDS));
        List<Runnable> scheduled = tasks.getAllValues();
        Runnable current = scheduled.getLast();
        time.set(200_000_000L);
        current.run();
        verify(writer, times(1)).write(anyList());
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(replacement).basicAck(1, false);
        verify(replacement, never()).basicAck(71, false);
    }

    /** Ein verspätetes Schliessereignis darf den bereits neuen Puffer nicht verwerfen. */
    @Test
    void ignoresDelayedShutdownOfOldChannel() throws Exception {
        Message first = message(71);
        consumer.onMessage(first, channel);
        ArgumentCaptor<ShutdownListener> callbacks = ArgumentCaptor.forClass(ShutdownListener.class);
        verify(channel).addShutdownListener(callbacks.capture());
        Channel replacement = mock(Channel.class);
        when(replacement.isOpen()).thenReturn(true);
        Message second = message(1);
        consumer.onMessage(second, replacement);
        ShutdownListener callback = callbacks.getValue();
        callback.shutdownCompleted(null);
        ArgumentCaptor<Runnable> cleanup = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).execute(cleanup.capture());
        Runnable cleanupTask = cleanup.getValue();
        cleanupTask.run();
        ArgumentCaptor<Runnable> tasks = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler, times(2)).schedule(tasks.capture(), eq(200L), eq(TimeUnit.MILLISECONDS));
        List<Runnable> scheduled = tasks.getAllValues();
        Runnable current = scheduled.getLast();
        time.set(200_000_000L);
        current.run();
        verify(replacement).basicAck(1, false);
        verify(writer, times(1)).write(anyList());
    }

    /** Ein NACK-Sendefehler schliesst den Kanal, ohne gültige Nachbarn zu speichern oder zu bestätigen. */
    @Test
    void closesChannelWhenRejectionFails() throws Exception {
        Message first = message(1);
        consumer.onMessage(first, channel);
        IOException failure = new IOException("Channel lost");
        doThrow(failure).when(channel).basicNack(2, false, false);
        Message invalid = invalidMessage("json");
        consumer.onMessage(invalid, channel);
        Runnable timeout = scheduledTimeout();
        time.set(200_000_000L);
        timeout.run();
        verify(channel).abort();
        verifyNoInteractions(writer);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    /** Nach einzelnen erfolgreichen ACKs darf der Rest bei einem Kanalfehler nicht als DB-Fehler gelten. */
    @Test
    void stopsAcknowledgingAfterPartialSuccess() throws Exception {
        IOException failure = new IOException("Channel lost after first ACK");
        doThrow(failure).when(channel).basicAck(2, false);
        for (int i = 1; i <= 3; i++) {
            Message next = message(i);
            consumer.onMessage(next, channel);
        }
        Runnable timeout = scheduledTimeout();
        time.set(200_000_000L);
        timeout.run();
        verify(channel).basicAck(1, false);
        verify(channel).basicAck(2, false);
        verify(channel, never()).basicAck(3, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        verify(channel).abort();
        verify(writer, times(1)).write(anyList());
    }

    /** Ein ungefüllter Stapel bleibt beim Stop unbestätigt und wird nicht nachträglich gespeichert. */
    @Test
    void discardsPartialBatchOnStop() throws Exception {
        Message first = message(1);
        consumer.onMessage(first, channel);
        Runnable timeout = scheduledTimeout();
        consumer.shutdown();
        time.set(200_000_000L);
        timeout.run();
        consumer.shutdown();
        verifyNoInteractions(writer);
        verify(channel).abort();
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        verify(scheduler, times(1)).shutdownNow();
    }

    /** Innerhalb der Schonfrist ist nur ein bereits erfolgreicher Commit bestätigbar; Stop allein erzeugt keine DLQ. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void finishesActiveWriteDuringStopGracePeriod(boolean committed) throws Exception {
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch stopping = new CountDownLatch(1);
        doAnswer(invocation -> {
            writing.countDown();
            boolean released = release.await(3, TimeUnit.SECONDS);
            assertTrue(released);
            return committed;
        }).when(writer).write(anyList());
        doAnswer(invocation -> {
            stopping.countDown();
            return null;
        }).when(scheduler).shutdown();
        Message first = message(1);
        consumer.onMessage(first, channel);
        Runnable timeout = scheduledTimeout();
        time.set(200_000_000L);
        ExecutorService tasks = Executors.newFixedThreadPool(2);
        try {
            Future<?> processing = tasks.submit(timeout);
            assertTrue(writing.await(2, TimeUnit.SECONDS));
            Future<?> shutdown = tasks.submit(consumer::shutdown);
            assertTrue(stopping.await(2, TimeUnit.SECONDS));
            release.countDown();
            processing.get(3, TimeUnit.SECONDS);
            shutdown.get(3, TimeUnit.SECONDS);
            if (committed) {
                verify(channel).basicAck(1, false);
            } else {
                verify(channel, never()).basicAck(anyLong(), anyBoolean());
            }
            verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        } finally {
            release.countDown();
            tasks.shutdownNow();
        }
    }

    /** Nach fünf Sekunden muss Stop eine hängende Retry-Pause unterbrechen und Bestätigungen sperren. */
    @Test
    void interruptsActiveWriteAfterStopGracePeriod() throws Exception {
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch blocked = new CountDownLatch(1);
        doAnswer(invocation -> {
            writing.countDown();
            blocked.await();
            return true;
        }).when(writer).write(anyList());
        Message first = message(1);
        consumer.onMessage(first, channel);
        Runnable timeout = scheduledTimeout();
        time.set(200_000_000L);
        ExecutorService tasks = Executors.newFixedThreadPool(2);
        try {
            Future<?> processing = tasks.submit(timeout);
            assertTrue(writing.await(2, TimeUnit.SECONDS));
            long started = System.nanoTime();
            Future<?> shutdown = tasks.submit(consumer::shutdown);
            shutdown.get(8, TimeUnit.SECONDS);
            processing.get(2, TimeUnit.SECONDS);
            long elapsed = System.nanoTime() - started;
            assertTrue(elapsed >= 5_000_000_000L);
            assertTrue(elapsed < 8_000_000_000L);
            verify(channel, never()).basicAck(anyLong(), anyBoolean());
            verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
            verify(channel, atLeastOnce()).abort();
        } finally {
            blocked.countDown();
            tasks.shutdownNow();
        }
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
