package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.messaging.PendingMessage;
import com.rabbitmq.client.Channel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Sammelt je Instanz begrenzte Stapel; die spätere Steuerung serialisiert deren Schreibvorgänge. */
public class MessageBatch {

    private static final int MAX_BATCH_SIZE = 500;
    private static final long MAX_WAIT_NANOS = 200_000_000L;

    private final List<PendingMessage> messages = new ArrayList<>();
    private final LongSupplier nanoTime;
    private Channel receivingChannel;
    private long startedAtNanos;

    /** Die monotone Zeitquelle macht die Sammeldauer unabhängig von Uhrzeitkorrekturen. */
    public MessageBatch() {
        this(System::nanoTime);
    }

    /** Eine kontrollierbare Zeitquelle ermöglicht Grenztests ohne echte Wartezeiten. */
    public MessageBatch(LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "Time source must not be null");
    }

    /** Gibt nur bei 500 Einträgen einen unveränderlichen Stapel zurück; leer bedeutet noch kein Auftrag. */
    public synchronized List<PendingMessage> add(PendingMessage pending) {
        Objects.requireNonNull(pending, "Pending message must not be null");
        Channel channel = pending.channel();
        if (receivingChannel != null && receivingChannel != channel) {
            throw new IllegalArgumentException("Cannot mix receiving channels in one batch");
        }
        if (messages.isEmpty()) {
            startedAtNanos = nanoTime.getAsLong();
        }
        receivingChannel = channel;
        messages.add(pending);
        if (messages.size() < MAX_BATCH_SIZE) {
            return List.of();
        }
        return release();
    }

    /** Wird später periodisch aufgerufen und gibt abgelaufene Teilstapel auch ohne neue Lieferung frei. */
    public synchronized List<PendingMessage> releaseIfExpired() {
        if (messages.isEmpty()) {
            return List.of();
        }
        long now = nanoTime.getAsLong();
        long elapsed = now - startedAtNanos;
        if (elapsed < MAX_WAIT_NANOS) {
            return List.of();
        }
        return release();
    }

    /** Beide Auslöser übergeben unter derselben Sperre eine Kopie und setzen den Sammelzustand zurück. */
    private List<PendingMessage> release() {
        List<PendingMessage> ready = List.copyOf(messages);
        messages.clear();
        receivingChannel = null;
        startedAtNanos = 0;
        return ready;
    }

    /** Macht den Sammelstand sichtbar, ohne die veränderliche interne Liste herauszugeben. */
    public synchronized int size() {
        return messages.size();
    }
}
