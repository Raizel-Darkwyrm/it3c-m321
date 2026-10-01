package ch.benedict.m321.batchwriter.database;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prüft das spätere Compose-Init-Skript mit PostgreSQL 16 ohne abweichende SQL-Kopie. */
class SchemaIntegrationTest {

    private static PostgreSQLContainer<?> postgres;

    /** Ein frischer Container führt dieselbe Init-Datei wie der spätere Stack aus. */
    @BeforeAll
    static void startDatabase() {
        String moduleDirectory = System.getProperty("basedir");
        Path script = Path.of(moduleDirectory, "..", "postgres", "init", "001-create-message.sql");
        assertTrue(Files.isRegularFile(script), "Die versionierte Init-Datei muss vorhanden sein.");
        MountableFile initFile = MountableFile.forHostPath(script);
        postgres = new PostgreSQLContainer<>("postgres:16");
        postgres.withCopyFileToContainer(initFile, "/docker-entrypoint-initdb.d/001-create-message.sql");
        postgres.start();
    }

    /** Der Test hinterlässt weder einen laufenden Container noch ein Projekt-Datenvolume. */
    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    /** Alle sechs Spalten müssen exakt zum Nachrichtenvertrag passen. */
    @Test
    void createsRequiredColumns() throws SQLException {
        String sql = """
                SELECT column_name, udt_name, is_nullable, character_maximum_length,
                       column_default, is_identity
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'message'
                ORDER BY ordinal_position
                """;
        String[] names = {"id", "room_id", "sender_id", "sender_name", "content", "sent_at"};
        String[] types = {"uuid", "uuid", "varchar", "varchar", "text", "timestamptz"};
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet columns = statement.executeQuery(sql)) {
            for (int i = 0; i < names.length; i++) {
                assertTrue(columns.next(), "Eine Pflichtspalte fehlt.");
                assertEquals(names[i], columns.getString("column_name"));
                assertEquals(types[i], columns.getString("udt_name"));
                assertEquals("NO", columns.getString("is_nullable"));
                assertNull(columns.getObject("character_maximum_length"));
                assertNull(columns.getString("column_default"));
                assertEquals("NO", columns.getString("is_identity"));
            }
            assertFalse(columns.next(), "Zusätzliche Spalten sind nicht vorgesehen.");
        }
    }

    /** Nur message wird angelegt; Raum-Stammdaten und Fremdschlüssel sind ausgeschlossen. */
    @Test
    void createsNoRoomTablesOrForeignKeys() throws SQLException {
        String tables = """
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                """;
        String foreignKeys = """
                SELECT count(*) FROM information_schema.table_constraints
                WHERE table_schema = 'public' AND table_name = 'message'
                  AND constraint_type = 'FOREIGN KEY'
                """;
        assertEquals(1, queryCount(tables));
        assertEquals(0, queryCount(foreignKeys));
    }

    /** Der Primärschlüssel muss allein die gelieferte Nachrichten-ID schützen. */
    @Test
    void createsPrimaryKeyOnId() throws SQLException {
        String sql = """
                SELECT key.column_name
                FROM information_schema.table_constraints AS constraint_info
                JOIN information_schema.key_column_usage AS key
                  ON key.constraint_catalog = constraint_info.constraint_catalog
                 AND key.constraint_schema = constraint_info.constraint_schema
                 AND key.constraint_name = constraint_info.constraint_name
                WHERE constraint_info.table_schema = 'public'
                  AND constraint_info.table_name = 'message'
                  AND constraint_info.constraint_type = 'PRIMARY KEY'
                ORDER BY key.ordinal_position
                """;
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet columns = statement.executeQuery(sql)) {
            assertTrue(columns.next());
            assertEquals("id", columns.getString("column_name"));
            assertFalse(columns.next());
        }
    }

    /** Genau der geplante B-Tree-Index ergänzt den automatisch erzeugten PK-Index. */
    @Test
    void createsRoomAndTimeIndex() throws SQLException {
        String sql = """
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = 'public' AND tablename = 'message'
                  AND indexname = 'message_room_sent_at_idx'
                """;
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet indexes = statement.executeQuery(sql)) {
            assertTrue(indexes.next());
            assertEquals("CREATE INDEX message_room_sent_at_idx ON public.message USING btree (room_id, sent_at DESC)",
                    indexes.getString("indexdef"));
            assertFalse(indexes.next());
        }
        String count = "SELECT count(*) FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'message'";
        assertEquals(2, queryCount(count));
    }

    /** Beliebige Räume sind erlaubt, gleiche Inhalte mit neuer ID ebenfalls; doppelte IDs nicht. */
    @Test
    void acceptsUnknownRoomButRejectsDuplicateId() throws SQLException {
        UUID messageId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        insertMessage(messageId, roomId);
        UUID secondId = UUID.randomUUID();
        insertMessage(secondId, roomId);

        SQLException duplicate = assertThrows(SQLException.class, () -> insertMessage(messageId, roomId));
        assertEquals("23505", duplicate.getSQLState());
        String count = "SELECT count(*) FROM public.message";
        assertEquals(2, queryCount(count));
    }

    /** Jede Verbindung verwendet ausschliesslich den isolierten Testcontainer. */
    private Connection openConnection() throws SQLException {
        String url = postgres.getJdbcUrl();
        String username = postgres.getUsername();
        String password = postgres.getPassword();
        return DriverManager.getConnection(url, username, password);
    }

    /** Zählabfragen halten die Prüfung von Schemaeigenschaften übersichtlich. */
    private int queryCount(String sql) throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    /** Gebundene Werte prüfen echte Inserts, ohne bereits den späteren Writer zu bauen. */
    private void insertMessage(UUID messageId, UUID roomId) throws SQLException {
        String sql = """
                INSERT INTO public.message (id, room_id, sender_id, sender_name, content, sent_at)
                VALUES (?, ?, ?, ?, ?, ?::timestamptz)
                """;
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, messageId);
            statement.setObject(2, roomId);
            statement.setString(3, "schema-test");
            statement.setString(4, "Schema Test");
            statement.setString(5, "Gleicher Inhalt ist mit neuer ID erlaubt.");
            statement.setString(6, "2026-10-01T12:00:00Z");
            assertEquals(1, statement.executeUpdate());
        }
    }
}
