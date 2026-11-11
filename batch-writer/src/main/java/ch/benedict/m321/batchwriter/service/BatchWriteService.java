package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.sql.SQLException;
import java.sql.Statement;

/** Wiederholt nur Datenbankschreibfehler; Broker-Bestätigungen bleiben Aufgabe des Consumers. */
@Service
@RequiredArgsConstructor
@Slf4j
public class BatchWriteService {

    private final BatchPersistenceService persistenceService;
    private final UUID instanceId = UUID.randomUUID();

    /** Dieselbe Kennung verbindet Empfangs- und Schreiblogs dieser Writer-Instanz. */
    public UUID instanceId() {
        return instanceId;
    }

    /** Die Ergebnisposition bleibt an die Lieferung gebunden, auch bei mehrfach derselben UUID. */
    public synchronized List<WriteOutcome> write(List<ChatMessage> messages) throws InterruptedException {
        List<ChatMessage> batch = List.copyOf(messages);
        if (batch.isEmpty()) {
            return List.of();
        }
        List<WriteOutcome> result = writeWithRetry(batch);
        if (result.getFirst() == WriteOutcome.INVALID && batch.size() > 1) {
            // Der fehlgeschlagene Gesamtversuch ist bereits zurückgerollt, bevor Einzelarbeit beginnt.
            log.warn("Isolating SQL data failure: instance={}, size={}", instanceId, batch.size());
            return writeIndividually(batch);
        }
        return result;
    }

    /** Nur bestätigte Datenfehler beenden die Wiederholung früh; unbekannte Fehler bleiben erneut zustellbar. */
    private List<WriteOutcome> writeWithRetry(List<ChatMessage> batch) throws InterruptedException {
        int batchSize = batch.size();
        for (int attempt = 1; attempt <= 3; attempt++) {
            checkInterrupted();
            long started = System.nanoTime();
            try {
                int[] counts = persistenceService.persist(batch);
                List<WriteOutcome> result = committedOutcomes(counts, batchSize);
                long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
                log.info("Batch committed: instance={}, size={}, attempt={}, durationMs={}",
                        instanceId, batchSize, attempt, elapsedMillis);
                return result;
            } catch (DataAccessException | TransactionException exception) {
                if (isMessageDataFailure(exception)) {
                    log.warn("SQL data failure: instance={}, size={}", instanceId, batchSize);
                    return Collections.nCopies(batchSize, WriteOutcome.INVALID);
                }
                logFailure(batchSize, attempt, exception);
                if (attempt == 3) {
                    return Collections.nCopies(batchSize, WriteOutcome.RETRY);
                }
            }
            waitBeforeRetry();
        }
        return Collections.nCopies(batchSize, WriteOutcome.RETRY);
    }

    /** Jeder persist-Aufruf beginnt eine eigene Transaktion; ein DB-Ausfall stoppt die restlichen Einzelversuche. */
    private List<WriteOutcome> writeIndividually(List<ChatMessage> batch) throws InterruptedException {
        List<WriteOutcome> results = new ArrayList<>();
        for (ChatMessage message : batch) {
            List<ChatMessage> single = List.of(message);
            List<WriteOutcome> result = writeWithRetry(single);
            WriteOutcome outcome = result.getFirst();
            results.add(outcome);
            if (outcome == WriteOutcome.RETRY) {
                while (results.size() < batch.size()) {
                    results.add(WriteOutcome.RETRY);
                }
                break;
            }
        }
        return List.copyOf(results);
    }

    /** Nur nach Commit werden Insert-Zähler zu Ergebnissen; der Zähler 0 kennzeichnet ein Duplikat. */
    private List<WriteOutcome> committedOutcomes(int[] counts, int expectedSize) {
        if (counts == null || counts.length != expectedSize) {
            throw new IllegalStateException("Missing committed JDBC results");
        }
        List<WriteOutcome> results = new ArrayList<>();
        for (int count : counts) {
            if (count == 0) {
                results.add(WriteOutcome.DUPLICATE);
            } else if (count > 0 || count == Statement.SUCCESS_NO_INFO) {
                // SUCCESS_NO_INFO beweist Erfolg, erlaubt aber keine sichere Unterscheidung eines Duplikats.
                results.add(WriteOutcome.STORED);
            } else {
                throw new IllegalStateException("Unexpected JDBC update count");
            }
        }
        return List.copyOf(results);
    }

    /** Eine enge SQLSTATE-Liste verhindert, dass Verbindungs-, Schema- oder Transaktionsfehler als kaputte Daten gelten. */
    private boolean isMessageDataFailure(RuntimeException exception) {
        if (exception instanceof TransactionException) {
            return false;
        }
        Set<Throwable> visited = new HashSet<>();
        Throwable cause = exception;
        boolean dataFailure = false;
        while (cause != null && visited.add(cause)) {
            if (cause instanceof SQLException sqlException) {
                SQLException current = sqlException;
                Set<SQLException> chained = new HashSet<>();
                while (current != null && chained.add(current)) {
                    String state = current.getSQLState();
                    if (isPermanentDataState(state)) {
                        dataFailure = true;
                    } else if (!"25P02".equals(state)) {
                        return false;
                    }
                    current = current.getNextException();
                }
            }
            cause = cause.getCause();
        }
        return dataFailure;
    }

    /** Fremdschlüssel und unbekannte Constraints können von fehlenden Stammdaten oder Konfiguration abhängen. */
    private boolean isPermanentDataState(String state) {
        if (state == null) {
            return false;
        }
        return switch (state) {
            case "22001", "22003", "22007", "22008", "22021", "22P02", "23502", "23514" -> true;
            default -> false;
        };
    }

    /** Protokolliert die Fehlerklasse und das nächste Ziel, ohne Nachrichteninhalte auszugeben. */
    private void logFailure(int batchSize, int attempt, RuntimeException exception) {
        Class<?> errorType = exception.getClass();
        String errorClass = errorType.getSimpleName();
        String nextStep = "retry after 5000 ms";
        if (attempt == 3) {
            nextStep = "requeue unresolved deliveries";
        }
        log.warn("Batch write failed: instance={}, size={}, attempt={}/3, error={}, next={}",
                instanceId, batchSize, attempt, errorClass, nextStep);
        if (attempt == 3) {
            log.error("Batch attempts exhausted: instance={}, size={}, target=chat.persist, error={}",
                    instanceId, batchSize, errorClass);
        }
    }

    /** Eine angeforderte Unterbrechung darf keinen weiteren Datenbankversuch beginnen. */
    private void checkInterrupted() throws InterruptedException {
        Thread currentThread = Thread.currentThread();
        if (currentThread.isInterrupted()) {
            throw new InterruptedException("Batch write interrupted");
        }
    }

    /** Behält das Stop-Signal bei, statt eine unterbrochene Pause als Schreibfehler zu zählen. */
    private void waitBeforeRetry() throws InterruptedException {
        try {
            pause(5000);
        } catch (InterruptedException exception) {
            Thread currentThread = Thread.currentThread();
            currentThread.interrupt();
            throw exception;
        }
    }

    /** Die schmale Teststelle erlaubt kontrollierte Ablaufprüfungen ohne echte Fünf-Sekunden-Pause. */
    void pause(long milliseconds) throws InterruptedException {
        Thread.sleep(milliseconds);
    }
}
