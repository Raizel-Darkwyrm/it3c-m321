package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import ch.benedict.m321.batchwriter.repository.MessageRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** Prüft Commit, Rollback und Konflikte mit dem echten Schema in einem isolierten PostgreSQL. */
@Testcontainers
class MessagePersistenceIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = createPostgres();

    private static DriverManagerDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private DataSourceTransactionManager transactionManager;
    private BatchPersistenceService persistenceService;
    private ScheduledThreadPoolExecutor timeoutExecutor;

    /** Jeder Test beendet seinen eigenen Zeitgeber, damit keine Hintergrundarbeit zurückbleibt. */
    @AfterEach
    void stopTimeoutExecutor() {
        if (timeoutExecutor != null) {
            timeoutExecutor.shutdownNow();
        }
    }

    /** Der Datenbanktest verwendet dieselbe Init-Datei wie Compose, ohne eigene Schemakopie. */
    private static PostgreSQLContainer<?> createPostgres() {
        String moduleDirectory = System.getProperty("basedir");
        Path script = Path.of(moduleDirectory, "..", "postgres", "init", "001-create-message.sql");
        MountableFile initFile = MountableFile.forHostPath(script);
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:16");
        container.withCopyFileToContainer(initFile, "/docker-entrypoint-initdb.d/001-create-message.sql");
        return container;
    }

    /** Neue JDBC-Verbindungen ermöglichen die Sichtbarkeitsprüfung nach dem Commit. */
    @BeforeAll
    static void configureDataSource() {
        dataSource = new DriverManagerDataSource();
        String url = postgres.getJdbcUrl();
        dataSource.setUrl(url);
        String username = postgres.getUsername();
        dataSource.setUsername(username);
        String password = postgres.getPassword();
        dataSource.setPassword(password);
    }

    /** Jeder Fall beginnt mit leerer Testtabelle und verwendet die produktiven Schreibklassen. */
    @BeforeEach
    void preparePersistence() {
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("TRUNCATE public.message");
        transactionManager = new DataSourceTransactionManager(dataSource);
        MessageRepository repository = new MessageRepository(jdbcTemplate);
        timeoutExecutor = new ScheduledThreadPoolExecutor(1);
        timeoutExecutor.setRemoveOnCancelPolicy(true);
        persistenceService = new BatchPersistenceService(repository, transactionManager, dataSource, timeoutExecutor);
    }

    /** Alle sechs Werte müssen nach Rückkehr auf einer neuen Verbindung sichtbar sein. */
    @Test
    void commitsAllFieldsWithoutChangingText() {
        ChatMessage first = newMessage("  Grüezi 🌍 ' ;  ");
        ChatMessage second = newMessage("Zweite Nachricht");
        List<ChatMessage> messages = List.of(first, second);
        persistenceService.persist(messages);
        assertStored(first);
        assertStored(second);
        assertEquals(2, rowCount());
    }

    /** Gleiche xmin-Werte belegen eine gemeinsame Insert-Transaktion für alle 500 Zeilen. */
    @Test
    void writes500RowsInOneTransaction() {
        List<ChatMessage> messages = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            UUID id = UUID.randomUUID();
            ChatMessage message = message(id, "Batch-" + i);
            messages.add(message);
        }
        persistenceService.persist(messages);
        assertEquals(500, rowCount());
        Integer transactionCount = jdbcTemplate.queryForObject(
                "SELECT count(DISTINCT xmin::text) FROM public.message", Integer.class);
        assertEquals(1, transactionCount);
    }

    /** Ein absichtlicher NOT-NULL-Fehler darf keinen gültigen Teil des Stapels zurücklassen. */
    @Test
    void rollsBackWholeBatchOnDatabaseError() {
        ChatMessage valid = newMessage("Gültiger erster Eintrag");
        // Umgeht absichtlich den Decoder, damit der Fehler erst in PostgreSQL entsteht.
        ChatMessage invalid = newMessage(null);
        List<ChatMessage> messages = List.of(valid, invalid);
        assertThrows(DataAccessException.class, () -> persistenceService.persist(messages));
        assertEquals(0, rowCount());
    }

    /** Doppelte IDs im selben Stapel verhindern neue eindeutige Nachrichten nicht. */
    @Test
    void ignoresDuplicatesWithinBatch() {
        ChatMessage first = newMessage("Original");
        ChatMessage second = newMessage("Neue ID");
        List<ChatMessage> messages = List.of(first, first, second);
        persistenceService.persist(messages);
        assertEquals(2, rowCount());
        assertStored(first);
        assertStored(second);
    }

    /** Konflikte nach einem Commit sind Erfolg und dürfen die vorhandenen Daten nicht überschreiben. */
    @Test
    void ignoresDuplicatesAfterCommitWithoutUpdatingOriginal() {
        ChatMessage first = newMessage("Original");
        List<ChatMessage> originalBatch = List.of(first);
        persistenceService.persist(originalBatch);
        persistenceService.persist(originalBatch);
        UUID existingId = first.id();
        ChatMessage changed = message(existingId, "Darf Original nicht ersetzen");
        ChatMessage newMessage = newMessage("Neue Nachricht");
        List<ChatMessage> nextBatch = List.of(changed, newMessage);
        persistenceService.persist(nextBatch);
        assertEquals(2, rowCount());
        assertStored(first);
        assertStored(newMessage);
    }

    /** Ein äusserer Aufrufer darf die zugesagte Commit-Grenze nicht bis später verschieben. */
    @Test
    void commitsIndependentlyOfCallerTransaction() {
        ChatMessage message = newMessage("Eigener Commit");
        List<ChatMessage> messages = List.of(message);
        TransactionTemplate outerTransaction = new TransactionTemplate(transactionManager);
        outerTransaction.executeWithoutResult(status -> {
            persistenceService.persist(messages);
            status.setRollbackOnly();
        });
        assertStored(message);
    }

    /** Ein leerer Stapel darf weder eine Transaktion noch einen Repository-Aufruf erzeugen. */
    @Test
    void acceptsEmptyBatch() {
        MessageRepository repository = mock(MessageRepository.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        BatchPersistenceService service = new BatchPersistenceService(repository, manager, dataSource, timeoutExecutor);
        List<ChatMessage> empty = List.of();
        service.persist(empty);
        verifyNoInteractions(repository, manager);
        assertEquals(0, rowCount());
    }

    /** Nach einem Rollback muss die Wiederholung mit unverändertem Stapel eine neue Transaktion erhalten. */
    @Test
    void retriesInNewTransactionAfterRollback() throws InterruptedException {
        MessageRepository realRepository = new MessageRepository(jdbcTemplate);
        MessageRepository repository = spy(realRepository);
        List<Long> transactionIds = new ArrayList<>();
        doAnswer(invocation -> {
            Long transactionId = jdbcTemplate.queryForObject("SELECT txid_current()", Long.class);
            transactionIds.add(transactionId);
            // Im zweiten Versuch darf der zurückgerollte erste Insert nicht sichtbar sein.
            int rowsBeforeInsert = rowCount();
            assertEquals(0, rowsBeforeInsert);
            invocation.callRealMethod();
            if (transactionIds.size() == 1) {
                throw new DataAccessResourceFailureException("Failure after insert before commit");
            }
            return null;
        }).when(repository).insertBatch(anyList());
        BatchPersistenceService persistence = new BatchPersistenceService(
                repository, transactionManager, dataSource, timeoutExecutor);
        BatchWriteService service = new BatchWriteService(persistence);
        BatchWriteService writer = spy(service);
        doNothing().when(writer).pause(anyLong());
        ChatMessage message = newMessage("Wiederholung nach Rollback");
        List<ChatMessage> messages = List.of(message);

        boolean committed = writer.write(messages);

        assertTrue(committed);
        int attemptCount = transactionIds.size();
        assertEquals(2, attemptCount);
        Long firstTransaction = transactionIds.get(0);
        Long secondTransaction = transactionIds.get(1);
        assertNotEquals(firstTransaction, secondTransaction);
        int storedRows = rowCount();
        assertEquals(1, storedRows);
        assertStored(message);
    }

    /** Prüft Inhalt und Typabbildung aus einer unabhängig geöffneten JDBC-Verbindung. */
    private void assertStored(ChatMessage expected) {
        UUID id = expected.id();
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM public.message WHERE id = ?", id);
        assertEquals(id, row.get("id"));
        assertEquals(expected.roomId(), row.get("room_id"));
        assertEquals(expected.senderId(), row.get("sender_id"));
        assertEquals(expected.senderName(), row.get("sender_name"));
        assertEquals(expected.content(), row.get("content"));
        Timestamp timestamp = (Timestamp) row.get("sent_at");
        Instant storedTime = timestamp.toInstant();
        Instant originalTime = expected.sentAt();
        Instant expectedTime = originalTime.truncatedTo(ChronoUnit.MICROS);
        assertEquals(expectedTime, storedTime);
    }

    /** Die Testtabelle gehört nur diesem Container und darf vollständig gezählt werden. */
    private int rowCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM public.message", Integer.class);
    }

    /** Erzeugt Nachrichten mit Submikrosekunden, um die vereinbarte DB-Präzision zu prüfen. */
    private ChatMessage message(UUID id, String content) {
        UUID roomId = UUID.randomUUID();
        Instant sentAt = Instant.parse("2026-10-01T12:00:00.123456789Z");
        return new ChatMessage(id, roomId, "anna", "Anna Muster", content, sentAt);
    }

    /** Neue Testnachrichten erhalten eine eigene ID; Konflikttests verwenden bewusst die andere Methode. */
    private ChatMessage newMessage(String content) {
        UUID id = UUID.randomUUID();
        return message(id, content);
    }
}
