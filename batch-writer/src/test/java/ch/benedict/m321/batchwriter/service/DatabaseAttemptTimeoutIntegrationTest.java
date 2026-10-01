package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.config.DatabaseConfig;
import ch.benedict.m321.batchwriter.dto.ChatMessage;
import ch.benedict.m321.batchwriter.repository.MessageRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Misst echte Fehlerpfade einschliesslich Verbindungsbeschaffung und prüft anschliessende Erholung. */
@Testcontainers
class DatabaseAttemptTimeoutIntegrationTest {

    @Container
    private final PostgreSQLContainer<?> postgres = createPostgres();

    /** Jeder Fall bekommt einen frischen Server mit dem unveränderten Produktionsschema. */
    private PostgreSQLContainer<?> createPostgres() {
        String moduleDirectory = System.getProperty("basedir");
        Path script = Path.of(moduleDirectory, "..", "postgres", "init", "001-create-message.sql");
        MountableFile initFile = MountableFile.forHostPath(script);
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:16");
        container.withCopyFileToContainer(initFile, "/docker-entrypoint-initdb.d/001-create-message.sql");
        return container;
    }

    /** Ein leerer Pool darf bei nicht erreichbarem Server nicht unbegrenzt warten. */
    @Test
    void boundsUnavailableDatabase() {
        ApplicationContextRunner runner = contextRunner();
        runner.run(context -> {
            BatchPersistenceService service = context.getBean(BatchPersistenceService.class);
            postgres.stop();
            List<ChatMessage> messages = testBatch();
            assertBoundedFailure(service, messages);
        });
    }

    /** Eine Tabellensperre muss rechtzeitig enden; danach muss derselbe Service wieder schreiben. */
    @Test
    void boundsLockedInsertAndRecovers() {
        ApplicationContextRunner runner = contextRunner();
        runner.run(context -> {
            BatchPersistenceService service = context.getBean(BatchPersistenceService.class);
            HikariDataSource dataSource = context.getBean(HikariDataSource.class);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            List<ChatMessage> messages = testBatch();
            try (Connection lockConnection = dataSource.getConnection();
                 Statement lockStatement = lockConnection.createStatement()) {
                lockConnection.setAutoCommit(false);
                lockStatement.execute("LOCK TABLE public.message IN ACCESS EXCLUSIVE MODE");
                try {
                    assertBoundedFailure(service, messages);
                } finally {
                    lockConnection.rollback();
                }
            }
            assertNoRows(jdbc);
            service.persist(messages);
            Integer count = jdbc.queryForObject("SELECT count(*) FROM public.message", Integer.class);
            assertEquals(1, count);
        });
    }

    /** Auch eine beim Commit ausgeführte Prüfung darf den Versuch nicht unbegrenzt blockieren. */
    @Test
    void boundsBlockedCommitAndRecovers() {
        ApplicationContextRunner runner = contextRunner();
        runner.run(context -> {
            BatchPersistenceService service = context.getBean(BatchPersistenceService.class);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            jdbc.execute("""
                    CREATE FUNCTION delay_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN PERFORM pg_sleep(10); RETURN NEW; END $$
                    """);
            jdbc.execute("""
                    CREATE CONSTRAINT TRIGGER delay_commit_trigger AFTER INSERT ON public.message
                    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION delay_commit()
                    """);
            List<ChatMessage> messages = testBatch();
            assertBoundedFailure(service, messages);
            // Die alte Sitzung muss beendet sein; andernfalls hängt dieser DDL-Aufruf an ihrer Sperre.
            jdbc.execute("DROP TRIGGER delay_commit_trigger ON public.message");
            assertNoRows(jdbc);
            service.persist(messages);
            Integer count = jdbc.queryForObject("SELECT count(*) FROM public.message", Integer.class);
            assertEquals(1, count);
        });
    }

    /** Ein bereits benutzter Socket kann ausfallen; der Pool muss danach eine neue Verbindung liefern. */
    @Test
    void boundsUnresponsiveExistingConnection() {
        ApplicationContextRunner runner = contextRunner();
        runner.run(context -> {
            BatchPersistenceService service = context.getBean(BatchPersistenceService.class);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            Integer result = jdbc.queryForObject("SELECT 1", Integer.class);
            assertEquals(1, result);
            String containerId = postgres.getContainerId();
            var dockerClient = postgres.getDockerClient();
            var pause = dockerClient.pauseContainerCmd(containerId);
            pause.exec();
            List<ChatMessage> messages = testBatch();
            try {
                assertBoundedFailure(service, messages);
            } finally {
                var unpause = dockerClient.unpauseContainerCmd(containerId);
                unpause.exec();
            }
            service.persist(messages);
            Integer count = jdbc.queryForObject("SELECT count(*) FROM public.message", Integer.class);
            assertEquals(1, count);
        });
    }

    /** Dieselbe produktive YAML wird gebunden; nur die Adresse des Testcontainers ersetzt Compose. */
    private ApplicationContextRunner contextRunner() {
        AutoConfigurations autoConfiguration = AutoConfigurations.of(DataSourceAutoConfiguration.class,
                DataSourceTransactionManagerAutoConfiguration.class, JdbcTemplateAutoConfiguration.class);
        ConfigDataApplicationContextInitializer initializer = new ConfigDataApplicationContextInitializer();
        ApplicationContextRunner runner = new ApplicationContextRunner();
        runner = runner.withInitializer(initializer);
        runner = runner.withConfiguration(autoConfiguration);
        runner = runner.withUserConfiguration(DatabaseConfig.class, MessageRepository.class, BatchPersistenceService.class);
        String url = postgres.getJdbcUrl();
        String username = postgres.getUsername();
        String password = postgres.getPassword();
        return runner.withPropertyValues("spring.datasource.url=" + url,
                "POSTGRES_USER=" + username, "POSTGRES_PASSWORD=" + password);
    }

    /** Der Messbeginn liegt vor persist und damit vor der Verbindungsbeschaffung. */
    private void assertBoundedFailure(BatchPersistenceService service, List<ChatMessage> messages) {
        long started = System.nanoTime();
        RuntimeException failure = assertThrows(RuntimeException.class, () -> service.persist(messages));
        long elapsed = System.nanoTime() - started;
        assertNotNull(failure);
        assertTrue(elapsed <= 5_000_000_000L, "Der gesamte Versuch muss innerhalb von fünf Sekunden enden.");
    }

    /** Eine abgebrochene Transaktion darf keine Testnachricht als Teilresultat hinterlassen. */
    private void assertNoRows(JdbcTemplate jdbc) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM public.message", Integer.class);
        assertEquals(0, count);
    }

    /** Eine einzige gültige Nachricht genügt, um einen blockierenden Schreibversuch auszulösen. */
    private List<ChatMessage> testBatch() {
        UUID id = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        Instant sentAt = Instant.parse("2026-10-01T12:00:00Z");
        ChatMessage message = new ChatMessage(id, roomId, "test", "Timeout Test", "Timeout", sentAt);
        return List.of(message);
    }
}
