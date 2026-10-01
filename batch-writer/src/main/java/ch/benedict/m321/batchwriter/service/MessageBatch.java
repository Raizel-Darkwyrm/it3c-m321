package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.messaging.PendingMessage;
import com.rabbitmq.client.Channel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Sammelt je Instanz begrenzte Stapel; die spätere Steuerung serialisiert deren Schreibvorgänge. */
public class MessageBatch {

    private static final int MAX_BATCH_SIZE = 500;

    private final List<PendingMessage> messages = new ArrayList<>();
    private Channel receivingChannel;

    /** Gibt nur bei 500 Einträgen einen unveränderlichen Stapel zurück; leer bedeutet noch kein Auftrag. */
    public synchronized List<PendingMessage> add(PendingMessage pending) {
        Objects.requireNonNull(pending, "Pending message must not be null");
        Channel channel = pending.channel();
        if (receivingChannel != null && receivingChannel != channel) {
            throw new IllegalArgumentException("Cannot mix receiving channels in one batch");
        }
        receivingChannel = channel;
        messages.add(pending);
        if (messages.size() < MAX_BATCH_SIZE) {
            return List.of();
        }
        List<PendingMessage> ready = List.copyOf(messages);
        messages.clear();
        receivingChannel = null;
        return ready;
    }

    /** Macht den Sammelstand sichtbar, ohne die veränderliche interne Liste herauszugeben. */
    public synchronized int size() {
        return messages.size();
    }
}
