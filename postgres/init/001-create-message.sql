-- Wird beim ersten PostgreSQL-Start mit leerem Datenverzeichnis ausgeführt.
-- Die Nachrichten-ID stammt vom chat-service und schützt vor doppelten Zeilen.
CREATE TABLE public.message (
    id uuid PRIMARY KEY,
    room_id uuid NOT NULL,
    sender_id varchar NOT NULL,
    sender_name varchar NOT NULL,
    content text NOT NULL,
    sent_at timestamptz NOT NULL
);

-- Kein Raum-Fremdschlüssel: Bewertung 1 benötigt keine Raum-Stammdaten.
-- Der Index übernimmt die geplante Sortierung, ohne eine Historien-API einzuführen.
CREATE INDEX message_room_sent_at_idx
    ON public.message USING btree (room_id, sent_at DESC);
