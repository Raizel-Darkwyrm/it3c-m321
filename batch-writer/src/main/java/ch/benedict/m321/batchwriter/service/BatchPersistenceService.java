package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import ch.benedict.m321.batchwriter.repository.MessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.TransactionTimedOutException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Ein Schreibversuch kehrt erst nach Commit zurück und bestätigt selbst keine Queue-Lieferung. */
@Service
public class BatchPersistenceService {

    private final MessageRepository messageRepository;
    private final TransactionTemplate transactionTemplate;
    private final DataSource dataSource;
    private final ScheduledExecutorService timeoutExecutor;

    /** Eine eigene Transaktion verhindert einen erst später erfolgenden Commit durch den Aufrufer. */
    public BatchPersistenceService(MessageRepository messageRepository,
                                   PlatformTransactionManager transactionManager,
                                   DataSource dataSource, ScheduledExecutorService timeoutExecutor) {
        this.messageRepository = messageRepository;
        this.dataSource = dataSource;
        this.timeoutExecutor = timeoutExecutor;
        transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactionTemplate.setTimeout(2);
    }

    /** Leere Stapel verursachen keine Transaktion; Fehler verlassen die Methode nach dem Rollback. */
    public void persist(List<ChatMessage> messages) {
        if (messages.isEmpty()) {
            return;
        }
        List<ChatMessage> batch = List.copyOf(messages);
        long started = System.nanoTime();
        DatabaseAttemptTimeout timeout = new DatabaseAttemptTimeout(dataSource);
        // Nach vier Sekunden abbrechen; eine Sekunde Reserve bleibt für Fehlerbehandlung und Aufräumen.
        ScheduledFuture<?> deadline = timeoutExecutor.schedule(timeout::expire, 4, TimeUnit.SECONDS);
        try {
            transactionTemplate.executeWithoutResult(status -> insertWithDeadline(batch, timeout));
            timeout.checkExpired();
            long elapsed = System.nanoTime() - started;
            if (elapsed >= 5_000_000_000L) {
                throw new TransactionTimedOutException("Database attempt exceeded five seconds");
            }
        } finally {
            timeout.finish();
            deadline.cancel(false);
        }
    }

    /** Überwacht dieselbe Verbindung, die JdbcTemplate innerhalb der Transaktion verwendet. */
    private void insertWithDeadline(List<ChatMessage> batch, DatabaseAttemptTimeout timeout) {
        TransactionSynchronizationManager.registerSynchronization(timeout);
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            timeout.watch(connection);
            messageRepository.insertBatch(batch);
        } finally {
            // Bei gebundener Transaktion bleibt die Verbindung bis Commit/Rollback geöffnet.
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }
}
