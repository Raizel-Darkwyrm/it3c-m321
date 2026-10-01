package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

import java.util.List;
import java.util.UUID;

/** Wiederholt nur Datenbankschreibfehler; Broker-Bestätigungen bleiben Aufgabe des Consumers. */
@Service
@RequiredArgsConstructor
@Slf4j
public class BatchWriteService {

    private final BatchPersistenceService persistenceService;
    private final UUID instanceId = UUID.randomUUID();

    /** Liefert Commit-Erfolg oder drei Fehlversuche; Unterbrechungen bleiben getrennt erkennbar. */
    public synchronized boolean write(List<ChatMessage> messages) throws InterruptedException {
        List<ChatMessage> batch = List.copyOf(messages);
        if (batch.isEmpty()) {
            return true;
        }
        int batchSize = batch.size();
        for (int attempt = 1; attempt <= 3; attempt++) {
            checkInterrupted();
            long started = System.nanoTime();
            try {
                persistenceService.persist(batch);
                long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
                log.info("Batch committed: instance={}, size={}, attempt={}, durationMs={}",
                        instanceId, batchSize, attempt, elapsedMillis);
                return true;
            } catch (DataAccessException | TransactionException exception) {
                logFailure(batchSize, attempt, exception);
                if (attempt == 3) {
                    return false;
                }
            }
            waitBeforeRetry();
        }
        return false;
    }

    /** Protokolliert die Fehlerklasse und das nächste Ziel, ohne Nachrichteninhalte auszugeben. */
    private void logFailure(int batchSize, int attempt, RuntimeException exception) {
        Class<?> errorType = exception.getClass();
        String errorClass = errorType.getSimpleName();
        String nextStep = "retry after 5000 ms";
        if (attempt == 3) {
            nextStep = "return exhausted result";
        }
        log.warn("Batch write failed: instance={}, size={}, attempt={}/3, error={}, next={}",
                instanceId, batchSize, attempt, errorClass, nextStep);
        if (attempt == 3) {
            log.error("Batch attempts exhausted: instance={}, size={}, target=chat.dlq, error={}",
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
