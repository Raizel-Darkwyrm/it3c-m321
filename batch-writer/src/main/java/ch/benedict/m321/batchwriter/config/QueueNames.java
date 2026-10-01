package ch.benedict.m321.batchwriter.config;

/** Gemeinsame Namen verhindern Tippfehler zwischen Queue-Deklaration und späterem Empfang. */
public final class QueueNames {

    /** Eingangsqueue für die spätere Stapelspeicherung. */
    public static final String PERSIST_QUEUE = "chat.persist";

    /** Fehlerziel für endgültig abgelehnte Lieferungen. */
    public static final String DEAD_LETTER_QUEUE = "chat.dlq";

    /** Die reine Namenssammlung benötigt keine Instanzen. */
    private QueueNames() {
    }
}
