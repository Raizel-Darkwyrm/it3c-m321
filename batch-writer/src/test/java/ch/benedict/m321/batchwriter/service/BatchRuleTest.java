package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import ch.benedict.m321.batchwriter.messaging.PendingMessage;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** Prüft die Stapelgrenze ohne Broker, Datenbank oder zeitabhängige Wartezeiten. */
class BatchRuleTest {

    private final Channel channel = mock(Channel.class);
    private final AtomicLong time = new AtomicLong();
    private final MessageBatch batch = new MessageBatch(time::get);

    /** Ein einzelner Eintrag wird exakt an der Frist auch ohne weiteren Eingang freigegeben. */
    @Test
    void releasesPartialBatchAt200Milliseconds() {
        PendingMessage pending = message(channel, 1);
        batch.add(pending);
        time.set(199_999_999L);
        List<PendingMessage> early = batch.releaseIfExpired();
        assertTrue(early.isEmpty());
        time.set(200_000_000L);
        List<PendingMessage> ready = batch.releaseIfExpired();
        assertEquals(1, ready.size());
        assertSame(pending, ready.getFirst());
        assertThrows(UnsupportedOperationException.class, () -> ready.add(pending));
        assertEquals(0, batch.size());
        List<PendingMessage> repeated = batch.releaseIfExpired();
        assertTrue(repeated.isEmpty());
    }

    /** Folgeeingänge dürfen den ersten Eintrag nicht durch erneutes Warten verzögern. */
    @Test
    void keepsDeadlineWhenAnotherMessageArrives() {
        PendingMessage first = message(channel, 1);
        batch.add(first);
        time.set(150_000_000L);
        PendingMessage second = message(channel, 2);
        batch.add(second);
        time.set(200_000_000L);
        List<PendingMessage> ready = batch.releaseIfExpired();
        assertEquals(2, ready.size());
    }

    /** Leerlauf erzeugt keine Aufträge und zählt nicht zur Frist des nächsten Stapels. */
    @Test
    void startsDeadlineOnlyWithFirstMessage() {
        time.set(1_000_000_000L);
        List<PendingMessage> empty = batch.releaseIfExpired();
        assertTrue(empty.isEmpty());
        PendingMessage pending = message(channel, 1);
        batch.add(pending);
        time.set(1_199_999_999L);
        List<PendingMessage> early = batch.releaseIfExpired();
        assertTrue(early.isEmpty());
        time.set(1_200_000_000L);
        List<PendingMessage> ready = batch.releaseIfExpired();
        assertEquals(1, ready.size());
    }

    /** Gewinnt die Grössengrenze, darf die gleichzeitige Fristprüfung nichts doppelt liefern. */
    @Test
    void sizeReleaseWinsWithoutDuplicateTimeoutRelease() {
        for (int i = 1; i <= 499; i++) {
            PendingMessage pending = message(channel, i);
            batch.add(pending);
        }
        time.set(200_000_000L);
        PendingMessage last = message(channel, 500);
        List<PendingMessage> ready = batch.add(last);
        List<PendingMessage> expired = batch.releaseIfExpired();
        assertEquals(500, ready.size());
        assertTrue(expired.isEmpty());
    }

    /** Gewinnt die Frist, gehört die nächste Lieferung zu einem Stapel mit neuer Frist. */
    @Test
    void timeoutReleaseStartsFreshBatch() {
        for (int i = 1; i <= 499; i++) {
            PendingMessage pending = message(channel, i);
            batch.add(pending);
        }
        time.set(200_000_000L);
        List<PendingMessage> ready = batch.releaseIfExpired();
        assertEquals(499, ready.size());
        Channel nextChannel = mock(Channel.class);
        PendingMessage next = message(nextChannel, 1);
        List<PendingMessage> immediate = batch.add(next);
        assertTrue(immediate.isEmpty());
        time.set(399_999_999L);
        List<PendingMessage> early = batch.releaseIfExpired();
        assertTrue(early.isEmpty());
        time.set(400_000_000L);
        List<PendingMessage> nextBatch = batch.releaseIfExpired();
        assertEquals(1, nextBatch.size());
        assertSame(next, nextBatch.getFirst());
        assertEquals(499, ready.size());
    }

    /** Ein verspäteter Prüftermin darf keine neue Wartefrist für einen alten Stapel beginnen. */
    @Test
    void releasesOverdueBatchImmediately() {
        time.set(-500_000_000L);
        PendingMessage pending = message(channel, 1);
        batch.add(pending);
        time.set(500_000_000L);
        List<PendingMessage> ready = batch.releaseIfExpired();
        assertEquals(1, ready.size());
        verifyNoInteractions(channel);
    }

    /** Weniger als 500 Lieferungen dürfen keinen grössenbedingten Schreibauftrag auslösen. */
    @Test
    void collects499MessagesWithoutReleasingBatch() {
        assertEquals(0, batch.size());
        for (int i = 1; i <= 499; i++) {
            PendingMessage pending = message(channel, i);
            List<PendingMessage> ready = batch.add(pending);
            assertTrue(ready.isEmpty());
        }
        assertEquals(499, batch.size());
        verifyNoInteractions(channel);
    }

    /** Der 500. Eintrag liefert genau einen vollständigen Stapel in Empfangsreihenfolge. */
    @Test
    void releasesExactly500Messages() {
        List<PendingMessage> ready = fillBatch();
        assertEquals(500, ready.size());
        for (int i = 0; i < ready.size(); i++) {
            PendingMessage pending = ready.get(i);
            assertEquals(i + 1, pending.deliveryTag());
            assertSame(channel, pending.channel());
        }
        assertEquals(0, batch.size());
        verifyNoInteractions(channel);
    }

    /** Ein übergebener Stapel darf weder vom Empfänger noch durch weitere Eingänge wachsen. */
    @Test
    void keepsReleasedBatchUnchanged() {
        List<PendingMessage> ready = fillBatch();
        PendingMessage next = message(channel, 501);
        assertThrows(UnsupportedOperationException.class, () -> ready.add(next));
        List<PendingMessage> nextResult = batch.add(next);
        assertTrue(nextResult.isEmpty());
        assertEquals(1, batch.size());
        assertEquals(500, ready.size());
    }

    /** Wiederholte Grössenauslösung trennt Stapel, ohne Einträge doppelt zu übergeben. */
    @Test
    void splits1000DeliveriesIntoTwoBatches() {
        int releasedCount = 0;
        int deliveredCount = 0;
        for (int i = 1; i <= 1000; i++) {
            PendingMessage pending = message(channel, i);
            List<PendingMessage> ready = batch.add(pending);
            if (!ready.isEmpty()) {
                releasedCount++;
                deliveredCount += ready.size();
                PendingMessage last = ready.getLast();
                assertEquals(i, last.deliveryTag());
            }
        }
        assertEquals(2, releasedCount);
        assertEquals(1000, deliveredCount);
        assertEquals(0, batch.size());
    }

    /** Ein fremder Kanal wird abgelehnt, ohne den bereits gesammelten Eintrag zu verlieren. */
    @Test
    void rejectsMixedChannels() {
        PendingMessage first = message(channel, 1);
        batch.add(first);
        Channel otherChannel = mock(Channel.class);
        PendingMessage other = message(otherChannel, 1);
        assertThrows(IllegalArgumentException.class, () -> batch.add(other));
        assertEquals(1, batch.size());
        List<PendingMessage> ready = List.of();
        for (int i = 2; i <= 500; i++) {
            PendingMessage pending = message(channel, i);
            ready = batch.add(pending);
        }
        assertSame(first, ready.getFirst());
        assertEquals(500, ready.size());
        verifyNoInteractions(channel, otherChannel);
    }

    /** Ein neuer Stapel darf nach abgeschlossener Übergabe einem anderen Kanal gehören. */
    @Test
    void acceptsNewChannelAfterRelease() {
        fillBatch();
        Channel otherChannel = mock(Channel.class);
        PendingMessage pending = message(otherChannel, 1);
        List<PendingMessage> ready = batch.add(pending);
        assertTrue(ready.isEmpty());
        assertEquals(1, batch.size());
    }

    /** Dieselbe Nachrichten-ID kann mehrere separat zu bestätigende Lieferungen haben. */
    @Test
    void retainsDuplicateMessageDeliveries() {
        PendingMessage first = message(channel, 1);
        ChatMessage content = first.message();
        PendingMessage duplicate = new PendingMessage(content, channel, 2);
        batch.add(first);
        batch.add(duplicate);
        List<PendingMessage> ready = List.of();
        for (int i = 3; i <= 500; i++) {
            PendingMessage pending = message(channel, i);
            ready = batch.add(pending);
        }
        assertSame(first, ready.get(0));
        assertSame(duplicate, ready.get(1));
    }

    /** Ungültige lokale Lieferungsreferenzen dürfen den Puffer nicht verändern. */
    @Test
    void rejectsMissingDeliveryInformation() {
        PendingMessage valid = message(channel, 1);
        ChatMessage content = valid.message();
        assertThrows(NullPointerException.class, () -> new PendingMessage(null, channel, 1));
        assertThrows(NullPointerException.class, () -> new PendingMessage(content, null, 1));
        assertThrows(IllegalArgumentException.class, () -> new PendingMessage(content, channel, 0));
        assertThrows(NullPointerException.class, () -> batch.add(null));
        assertEquals(0, batch.size());
    }

    /** Baut einen vollen Stapel, um die Übergabe in mehreren Tests zu prüfen. */
    private List<PendingMessage> fillBatch() {
        List<PendingMessage> ready = List.of();
        for (int i = 1; i <= 500; i++) {
            PendingMessage pending = message(channel, i);
            ready = batch.add(pending);
        }
        return ready;
    }

    /** Liefert gültige Beispieldaten; der Delivery-Tag bleibt getrennt von der UUID. */
    private PendingMessage message(Channel receivingChannel, long deliveryTag) {
        UUID id = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        Instant sentAt = Instant.parse("2026-10-01T12:00:00Z");
        ChatMessage message = new ChatMessage(id, roomId, "anna", "Anna", "Hallo", sentAt);
        return new PendingMessage(message, receivingChannel, deliveryTag);
    }
}
