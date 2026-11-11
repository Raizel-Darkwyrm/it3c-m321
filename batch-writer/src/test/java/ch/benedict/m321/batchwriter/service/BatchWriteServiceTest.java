package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionSystemException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.sql.SQLException;
import java.sql.BatchUpdateException;
import org.springframework.dao.DataIntegrityViolationException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Prüft den Wiederholungsablauf ohne Datenbank und ohne echte Wartezeiten. */
class BatchWriteServiceTest {

    private BatchPersistenceService persistence;
    private BatchWriteService writer;
    private List<ChatMessage> messages;

    /** Nur die Pause wird ersetzt; die produktive Schleife bleibt unverändert aktiv. */
    @BeforeEach
    void prepareWriter() throws InterruptedException {
        persistence = mock(BatchPersistenceService.class);
        BatchWriteService service = new BatchWriteService(persistence);
        writer = spy(service);
        doNothing().when(writer).pause(anyLong());
        UUID id = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        Instant sentAt = Instant.parse("2026-10-01T12:00:00Z");
        ChatMessage message = new ChatMessage(id, roomId, "anna", "Anna", "Test", sentAt);
        messages = new ArrayList<>();
        messages.add(message);
        when(persistence.persist(anyList())).thenReturn(new int[] {1});
    }

    /** Jeder mögliche Erfolgsversuch beendet den Ablauf sofort und pausiert nur davor. */
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void stopsAfterSuccessfulAttempt(int successfulAttempt) throws InterruptedException {
        int[] attempts = {0};
        doAnswer(invocation -> {
            attempts[0]++;
            if (attempts[0] < successfulAttempt) {
                throw new DataAccessResourceFailureException("Database unavailable");
            }
            return new int[] {1};
        }).when(persistence).persist(anyList());

        List<WriteOutcome> committed = writer.write(messages);

        assertEquals(List.of(WriteOutcome.STORED), committed);
        InOrder order = inOrder(persistence, writer);
        order.verify(writer).write(messages);
        order.verify(persistence).persist(messages);
        for (int attempt = 2; attempt <= successfulAttempt; attempt++) {
            order.verify(writer).pause(5000);
            order.verify(persistence).persist(messages);
        }
        order.verifyNoMoreInteractions();
    }

    /** Nach genau drei Fehlern folgt weder eine vierte Ausführung noch eine weitere Pause. */
    @Test
    void reportsExhaustedAttempts() throws InterruptedException {
        doThrow(new DataAccessResourceFailureException("Database unavailable"))
                .when(persistence).persist(anyList());

        List<WriteOutcome> committed = writer.write(messages);

        assertEquals(List.of(WriteOutcome.RETRY), committed);
        InOrder order = inOrder(persistence, writer);
        order.verify(writer).write(messages);
        order.verify(persistence).persist(messages);
        order.verify(writer).pause(5000);
        order.verify(persistence).persist(messages);
        order.verify(writer).pause(5000);
        order.verify(persistence).persist(messages);
        order.verifyNoMoreInteractions();
    }

    /** Auch ein fehlgeschlagener Commit gehört zu den Datenbankschreibfehlern. */
    @Test
    void retriesTransactionFailure() throws InterruptedException {
        doThrow(new TransactionSystemException("Commit failed")).doReturn(new int[] {1})
                .when(persistence).persist(anyList());
        List<WriteOutcome> committed = writer.write(messages);
        assertEquals(List.of(WriteOutcome.STORED), committed);
        verify(persistence, times(2)).persist(messages);
        verify(writer).pause(5000);
    }

    /** Eine feste Kopie schützt Wiederholungen vor Änderungen der ursprünglichen Liste. */
    @Test
    void preservesBatchAcrossAttempts() throws InterruptedException {
        List<ChatMessage> expected = List.copyOf(messages);
        List<List<ChatMessage>> received = new ArrayList<>();
        doAnswer(invocation -> {
            List<ChatMessage> batch = invocation.getArgument(0);
            received.add(batch);
            if (received.size() == 1) {
                messages.clear();
                throw new DataAccessResourceFailureException("Connection lost");
            }
            return new int[] {1};
        }).when(persistence).persist(anyList());

        List<WriteOutcome> committed = writer.write(messages);
        assertEquals(List.of(WriteOutcome.STORED), committed);
        List<ChatMessage> first = received.get(0);
        List<ChatMessage> second = received.get(1);
        assertEquals(expected, first);
        assertSame(first, second);
        assertThrows(UnsupportedOperationException.class, first::clear);
    }

    /** Programmierfehler dürfen nicht als drei erschöpfte DB-Versuche verschleiert werden. */
    @Test
    void propagatesUnrelatedFailure() throws InterruptedException {
        doThrow(new IllegalStateException("Unexpected failure")).when(persistence).persist(anyList());
        assertThrows(IllegalStateException.class, () -> writer.write(messages));
        verify(persistence).persist(messages);
        verify(writer, never()).pause(anyLong());
    }

    /** Ein Stop während der Pause ist kein erschöpfter Stapel und beginnt keinen neuen Versuch. */
    @Test
    void preservesInterruption() throws InterruptedException {
        doThrow(new DataAccessResourceFailureException("Database unavailable"))
                .when(persistence).persist(anyList());
        doThrow(new InterruptedException("Stopping")).when(writer).pause(5000);
        try {
            assertThrows(InterruptedException.class, () -> writer.write(messages));
            Thread currentThread = Thread.currentThread();
            boolean interrupted = currentThread.isInterrupted();
            assertTrue(interrupted);
            verify(persistence).persist(messages);
        } finally {
            Thread.interrupted();
        }
    }

    /** Leere Stapel erzeugen weder Schreibversuche noch Pausen. */
    @Test
    void ignoresEmptyBatch() throws InterruptedException {
        List<ChatMessage> empty = List.of();
        List<WriteOutcome> committed = writer.write(empty);
        assertEquals(List.of(), committed);
        verifyNoInteractions(persistence);
        verify(writer, never()).pause(anyLong());
    }

    /** Ein neuer Stapel startet wieder beim ersten Versuch, auch nach drei vorherigen Fehlern. */
    @Test
    void resetsAttemptsForNextBatch() throws InterruptedException {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("Offline");
        doThrow(failure).doThrow(failure).doThrow(failure).doReturn(new int[] {1})
                .when(persistence).persist(anyList());
        List<WriteOutcome> firstCommitted = writer.write(messages);
        List<WriteOutcome> nextCommitted = writer.write(messages);
        assertEquals(List.of(WriteOutcome.RETRY), firstCommitted);
        assertEquals(List.of(WriteOutcome.STORED), nextCommitted);
        verify(persistence, times(4)).persist(messages);
        verify(writer, times(2)).pause(5000);
    }

    /** Ein bereits gesetztes Stop-Signal verhindert schon den ersten Schreibversuch. */
    @Test
    void rejectsAttemptWhenAlreadyInterrupted() {
        Thread currentThread = Thread.currentThread();
        currentThread.interrupt();
        try {
            assertThrows(InterruptedException.class, () -> writer.write(messages));
            verifyNoInteractions(persistence);
        } finally {
            Thread.interrupted();
        }
    }

    /** Ein echter Datenfehler löst Einzeltransaktionen aus; nur der isoliert fehlerhafte Eintrag bleibt ungültig. */
    @Test
    void isolatesDataFailureAndKeepsDuplicateOutcome() throws InterruptedException {
        ChatMessage first = messages.getFirst();
        ChatMessage invalid = new ChatMessage(UUID.randomUUID(), first.roomId(), "test", "Test", "bad", first.sentAt());
        List<ChatMessage> batch = List.of(first, invalid, first);
        SQLException sql = new SQLException("Check failed", "23514");
        DataIntegrityViolationException failure = new DataIntegrityViolationException("Invalid data", sql);
        when(persistence.persist(batch)).thenThrow(failure);
        List<ChatMessage> validSingle = List.of(first);
        List<ChatMessage> invalidSingle = List.of(invalid);
        when(persistence.persist(validSingle)).thenReturn(new int[] {1}, new int[] {0});
        when(persistence.persist(invalidSingle)).thenThrow(failure);

        List<WriteOutcome> results = writer.write(batch);

        assertEquals(List.of(WriteOutcome.STORED, WriteOutcome.INVALID, WriteOutcome.DUPLICATE), results);
        verify(persistence).persist(batch);
        verify(persistence, times(2)).persist(validSingle);
        verify(persistence).persist(invalidSingle);
        verify(writer, never()).pause(anyLong());
    }

    /** Unklare SQLSTATEs, Verbindungsfehler und Schemafehler dürfen keine Einzelprüfung oder DLQ auslösen. */
    @ParameterizedTest
    @ValueSource(strings = {"08006", "40001", "40P01", "57014", "42P01", "23503", "23505", "25P02", "unknown"})
    void retriesWholeBatchForUncertainSqlFailure(String state) throws InterruptedException {
        ChatMessage message = messages.getFirst();
        List<ChatMessage> batch = java.util.Collections.nCopies(500, message);
        SQLException sql = new SQLException("Uncertain failure", state);
        DataIntegrityViolationException failure = new DataIntegrityViolationException("Database problem", sql);
        when(persistence.persist(batch)).thenThrow(failure);

        List<WriteOutcome> results = writer.write(batch);

        assertEquals(java.util.Collections.nCopies(500, WriteOutcome.RETRY), results);
        verify(persistence, times(3)).persist(batch);
        verifyNoMoreInteractions(persistence);
        verify(writer, times(2)).pause(5000);
    }

    /** Auch nach einem erkannten Datenfehler hat ein nachfolgender Verbindungsfehler Vorrang. */
    @Test
    void treatsMixedSqlExceptionChainConservatively() throws InterruptedException {
        BatchUpdateException sql = new BatchUpdateException("Check failed", "23514", new int[0]);
        sql.setNextException(new SQLException("Connection lost", "08006"));
        DataIntegrityViolationException failure = new DataIntegrityViolationException("Mixed failure", sql);
        when(persistence.persist(messages)).thenThrow(failure);
        List<WriteOutcome> results = writer.write(messages);
        assertEquals(List.of(WriteOutcome.RETRY), results);
        verify(persistence, times(3)).persist(messages);
    }

    /** Ein Ausfall während der Einzelprüfung darf nicht drei Versuche für jeden weiteren Eintrag erzeugen. */
    @Test
    void stopsIsolationAfterInfrastructureRetriesAreExhausted() throws InterruptedException {
        ChatMessage first = messages.getFirst();
        ChatMessage second = new ChatMessage(UUID.randomUUID(), first.roomId(), "test", "Test", "second", first.sentAt());
        List<ChatMessage> batch = List.of(first, second, first);
        SQLException sql = new SQLException("Check failed", "23514");
        when(persistence.persist(batch)).thenThrow(new DataIntegrityViolationException("Data", sql));
        List<ChatMessage> firstSingle = List.of(first);
        List<ChatMessage> secondSingle = List.of(second);
        when(persistence.persist(secondSingle)).thenThrow(new DataAccessResourceFailureException("Offline"));

        List<WriteOutcome> results = writer.write(batch);

        assertEquals(List.of(WriteOutcome.STORED, WriteOutcome.RETRY, WriteOutcome.RETRY), results);
        verify(persistence).persist(firstSingle);
        verify(persistence, times(3)).persist(secondSingle);
        verify(writer, times(2)).pause(5000);
    }
}
