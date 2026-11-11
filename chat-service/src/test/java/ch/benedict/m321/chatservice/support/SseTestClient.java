package ch.benedict.m321.chatservice.support;

import ch.benedict.m321.chatservice.dto.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.awaitility.Awaitility;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Liest einen echten SSE-HTTP-Stream für mehrere Integrationsszenarien. */
public class SseTestClient implements AutoCloseable {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final HttpRequest request;
    private final List<ChatMessage> messages = new ArrayList<>();
    private final Thread readerThread;
    private volatile InputStream responseBody;
    private volatile Throwable failure;

    /** Bereitet eine Raumverbindung mit optionalem Last-Event-ID-Header vor. */
    public SseTestClient(ObjectMapper objectMapper, URI baseUri, UUID roomId, UUID lastEventId) {
        this.objectMapper = objectMapper;
        httpClient = HttpClient.newHttpClient();
        String streamPath = "/stream?roomId=" + roomId;
        URI streamUri = baseUri.resolve(streamPath);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(streamUri)
                .header("Accept", "text/event-stream")
                .GET();
        if (lastEventId != null) {
            requestBuilder.header("Last-Event-ID", lastEventId.toString());
        }
        request = requestBuilder.build();
        readerThread = new Thread(this::readStream, "sse-test-reader");
        readerThread.setDaemon(true);
    }

    /** Startet das blockierende Lesen ausserhalb des JUnit-Threads. */
    public void start() {
        readerThread.start();
    }

    /** Wartet auf eine exakte Mindestzahl und meldet gleichzeitig Lesefehler. */
    public void awaitMessages(int expected, Duration timeout) {
        Awaitility.await().atMost(timeout).untilAsserted(() -> {
            assertNull(failure);
            assertEquals(expected, size());
        });
    }

    /** Liefert eine unveränderliche Momentaufnahme für ID- und Reihenfolgeprüfungen. */
    public synchronized List<ChatMessage> snapshot() {
        return List.copyOf(messages);
    }

    /** Entfernt nur bereits ausgewertete Testereignisse, nicht den produktiven Replay-Puffer. */
    public synchronized void clear() {
        messages.clear();
    }

    /** Schliesst die HTTP-Antwort und beendet einen noch wartenden Lesethread. */
    @Override
    public void close() {
        InputStream body = responseBody;
        if (body != null) {
            try {
                body.close();
            } catch (IOException exception) {
                failure = exception;
            }
        }
        readerThread.interrupt();
    }

    /** Verbindet sich und zerlegt den einfachen id/data-Vertrag zeilenweise. */
    private void readStream() {
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, response.statusCode());
            responseBody = response.body();
            readEvents(responseBody);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Throwable throwable) {
            failure = throwable;
        }
    }

    /** Eine Leerzeile schliesst jeweils ein SSE-Ereignis ab. */
    private void readEvents(InputStream body) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        String eventId = null;
        String eventData = null;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("id:")) {
                eventId = line.substring(3).trim();
            } else if (line.startsWith("data:")) {
                eventData = line.substring(5).trim();
            } else if (line.isEmpty() && eventData != null) {
                addEvent(eventId, eventData);
                eventId = null;
                eventData = null;
            }
        }
    }

    /** Prüft, dass SSE-ID und JSON-ID dieselbe Vertragsnachricht bezeichnen. */
    private void addEvent(String eventId, String eventData) throws IOException {
        ChatMessage message = objectMapper.readValue(eventData, ChatMessage.class);
        assertEquals(message.id().toString(), eventId);
        synchronized (this) {
            messages.add(message);
        }
    }

    /** Liest die aktuelle Anzahl unter demselben Schutz wie der Reader. */
    private synchronized int size() {
        return messages.size();
    }
}
