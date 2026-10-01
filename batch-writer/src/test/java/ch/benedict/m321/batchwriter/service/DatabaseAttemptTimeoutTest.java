package ch.benedict.m321.batchwriter.service;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionSynchronization;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;

/** Prüft die Abbruch-Zustände kontrolliert, ohne Docker oder echte Wartezeiten. */
class DatabaseAttemptTimeoutTest {

    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final DatabaseAttemptTimeout timeout = new DatabaseAttemptTimeout(dataSource);

    /** Eine abgelaufene Frist muss die aktive Verbindung tatsächlich abbrechen. */
    @Test
    void abortsActiveConnection() throws SQLException {
        timeout.watch(connection);
        timeout.expire();
        timeout.expire();
        verify(connection, times(1)).abort(any(Executor.class));
        assertThrows(TransactionTimedOutException.class, timeout::checkExpired);
    }

    /** Eine verspätet beschaffte Verbindung darf nach Fristablauf keine Arbeit beginnen. */
    @Test
    void rejectsConnectionAcquiredAfterDeadline() throws SQLException {
        timeout.expire();
        assertThrows(TransactionTimedOutException.class, () -> timeout.watch(connection));
        verify(connection).abort(any(Executor.class));
    }

    /** Vor Pool-Rückgabe wird die Referenz entfernt, damit spätere Benutzer nicht abgebrochen werden. */
    @Test
    void doesNotAbortConnectionAfterCompletion() {
        timeout.watch(connection);
        timeout.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        timeout.expire();
        timeout.checkExpired();
        verifyNoInteractions(connection);
    }

    /** Auch ein Fehler vor der Transaktion muss den späteren Timer-Aufruf unschädlich machen. */
    @Test
    void finishesWithoutConnection() {
        timeout.finish();
        timeout.expire();
        timeout.checkExpired();
        verifyNoInteractions(dataSource, connection);
    }
}
