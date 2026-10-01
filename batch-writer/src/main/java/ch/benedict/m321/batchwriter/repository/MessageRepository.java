package ch.benedict.m321.batchwriter.repository;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** Bindet Nachrichtendaten an einen JDBC-Batch; die Transaktionsgrenze setzt der Service. */
@Repository
@RequiredArgsConstructor
public class MessageRepository {

    private static final String INSERT_SQL = """
            INSERT INTO public.message (id, room_id, sender_id, sender_name, content, sent_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;

    /** Konflikte sind erfolgreiche Wiederholungen; ein Update-Zähler von null ist kein Fehler. */
    public void insertBatch(List<ChatMessage> messages) {
        if (messages.isEmpty()) {
            return;
        }
        List<Object[]> parameters = new ArrayList<>();
        for (ChatMessage message : messages) {
            Object[] values = parametersFor(message);
            parameters.add(values);
        }
        jdbcTemplate.batchUpdate(INSERT_SQL, parameters);
    }

    /** Werte bleiben SQL-Parameter; Mikrosekunden werden ohne Aufrunden an PostgreSQL übergeben. */
    private Object[] parametersFor(ChatMessage message) {
        Instant originalTime = message.sentAt();
        Instant microsecondTime = originalTime.truncatedTo(ChronoUnit.MICROS);
        OffsetDateTime sentAt = microsecondTime.atOffset(ZoneOffset.UTC);
        return new Object[] {
                message.id(), message.roomId(), message.senderId(),
                message.senderName(), message.content(), sentAt
        };
    }
}
