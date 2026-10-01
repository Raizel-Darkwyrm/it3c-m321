package ch.benedict.m321.batchwriter.service;

import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionSynchronization;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/** Überwacht genau einen Versuch und löst seine Verbindung vor der Rückgabe an den Pool ab. */
@RequiredArgsConstructor
@Slf4j
class DatabaseAttemptTimeout implements TransactionSynchronization {

    private final DataSource dataSource;
    private Connection connection;
    private boolean expired;
    private boolean finished;

    /** Eine nach Fristablauf erhaltene Verbindung wird sofort beendet statt erneut benutzt. */
    synchronized void watch(Connection connection) {
        this.connection = connection;
        if (expired) {
            abortConnection();
            checkExpired();
        }
    }

    /** Der Timer beendet die echte JDBC-Verbindung, nicht bloss das Warten eines Future-Aufrufers. */
    synchronized void expire() {
        if (finished || expired) {
            return;
        }
        expired = true;
        abortConnection();
    }

    /** Ein knapp nach der Frist erfolgter Commit darf nicht als rechtzeitiger Erfolg gemeldet werden. */
    synchronized void checkExpired() {
        if (expired) {
            throw new TransactionTimedOutException("Database attempt deadline exceeded");
        }
    }

    /** Spring ruft dies vor seiner Ressourcenbereinigung auf; der Pool darf die Verbindung danach weitergeben. */
    @Override
    public void afterCompletion(int status) {
        finish();
    }

    /** Koordiniert Timer und Schreibthread auch bei Fehlern vor Beginn der Transaktion. */
    synchronized void finish() {
        finished = true;
        connection = null;
    }

    /** Abgebrochene Poolverbindungen werden ausdrücklich verworfen und nicht wiederverwendet. */
    private void abortConnection() {
        if (connection == null) {
            return;
        }
        Connection activeConnection = connection;
        connection = null;
        try {
            activeConnection.abort(Runnable::run);
        } catch (SQLException exception) {
            log.warn("Could not abort database connection", exception);
        } finally {
            if (dataSource instanceof HikariDataSource pool) {
                pool.evictConnection(activeConnection);
            }
        }
    }
}
