package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import ch.benedict.m321.batchwriter.support.WriterTestStack;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** S7 verwendet zwei weiterlaufende Writer und startet denselben PostgreSQL-Container nach 15 Sekunden. */
@ExtendWith(OutputCaptureExtension.class)
class DatabaseOutageIntegrationTest {

    /** DB/DLQ müssen alle Ausfall-IDs enthalten; anschliessend muss jeder Writer erneut committen. */
    @Test
    void recoversBothWritersAfter15SecondOutage(CapturedOutput output) throws Exception {
        try (WriterTestStack stack = new WriterTestStack(true)) {
            List<UUID> instances = startAndWarmWriters(stack);
            List<ChatMessage> messages = stack.messages(300);
            ChatMessage firstMessage = messages.getFirst();
            UUID roomId = firstMessage.roomId();
            Set<UUID> expected = new HashSet<>();
            for (ChatMessage message : messages) {
                expected.add(message.id());
            }
            DockerClient docker = stack.postgres.getDockerClient();
            String containerId = stack.postgres.getContainerId();
            docker.stopContainerCmd(containerId).withTimeout(0).exec();
            long stopped = System.nanoTime();
            ScheduledExecutorService recovery = Executors.newSingleThreadScheduledExecutor();
            try {
                ScheduledFuture<?> restart = recovery.schedule(() -> {
                    docker.startContainerCmd(containerId).exec();
                }, 15, TimeUnit.SECONDS);
                stack.publish(messages);
                long sendingNanos = System.nanoTime() - stopped;
                assertTrue(sendingNanos < 15_000_000_000L, "All 300 messages must be sent during outage");
                restart.get(25, TimeUnit.SECONDS);
                long remaining = 90_000_000_000L - (System.nanoTime() - stopped);
                assertTrue(remaining > 0);
                Duration limit = Duration.ofNanos(remaining);
                await().ignoreExceptions().pollInterval(Duration.ofMillis(200)).atMost(limit).untilAsserted(() -> {
                    List<UUID> stored = stack.database.queryForList(
                            "SELECT id FROM message WHERE room_id = ?", UUID.class, roomId);
                    Set<UUID> accounted = new HashSet<>(stored);
                    Set<UUID> deadLetters = stack.deadLetterIds();
                    accounted.addAll(deadLetters);
                    assertEquals(expected, accounted);
                    stack.assertQueue("chat.persist", 0, 0, 2);
                });
                assertRecovered(stack, instances, output);
            } finally {
                recovery.shutdownNow();
                // Derselbe Container bleibt erhalten; auch bei einem Prüffehler wird er wieder gestartet.
                InspectContainerResponse inspection = docker.inspectContainerCmd(containerId).exec();
                InspectContainerResponse.ContainerState state = inspection.getState();
                boolean running = Boolean.TRUE.equals(state.getRunning());
                if (!running) {
                    docker.startContainerCmd(containerId).exec();
                }
            }
        }
    }

    /** Vor dem Ausfall muss jede Instanz eine echte Verbindung bereits erfolgreich benutzt haben. */
    private List<UUID> startAndWarmWriters(WriterTestStack stack) throws Exception {
        List<UUID> instances = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            ConfigurableApplicationContext context = stack.startWriter(true);
            BatchWriteService writer = context.getBean(BatchWriteService.class);
            List<ChatMessage> warmup = stack.messages(1);
            boolean committed = writer.write(warmup);
            assertTrue(committed);
            UUID instance = writer.instanceId();
            instances.add(instance);
        }
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            stack.assertQueue("chat.persist", 0, 0, 2);
        });
        return instances;
    }

    /** Kontrollgruppen prüfen gespeicherte IDs und neue Commit-Logs beider unveränderter Instanzen. */
    private void assertRecovered(WriterTestStack stack, List<UUID> instances, CapturedOutput output) throws Exception {
        String previousOutput = output.getAll();
        int offset = previousOutput.length();
        long started = System.nanoTime();
        boolean bothCommitted = false;
        while (!bothCommitted && System.nanoTime() - started < 30_000_000_000L) {
            List<ChatMessage> controls = stack.messages(1000);
            stack.publish(controls);
            long remaining = 30_000_000_000L - (System.nanoTime() - started);
            assertTrue(remaining > 0);
            Duration limit = Duration.ofNanos(Math.min(remaining, 10_000_000_000L));
            await().pollInterval(Duration.ofMillis(200)).atMost(limit).untilAsserted(() -> {
                stack.assertStored(controls);
                stack.assertQueue("chat.persist", 0, 0, 2);
            });
            String allOutput = output.getAll();
            String newOutput = allOutput.substring(offset);
            bothCommitted = true;
            for (UUID instance : instances) {
                boolean committed = newOutput.contains("Batch committed: instance=" + instance);
                if (!committed) {
                    bothCommitted = false;
                }
            }
        }
        assertTrue(bothCommitted, "Both original writers must commit after recovery");
        for (int i = 0; i < instances.size(); i++) {
            ConfigurableApplicationContext context = stack.writers.get(i);
            assertTrue(context.isActive());
            BatchWriteService writer = context.getBean(BatchWriteService.class);
            UUID actual = writer.instanceId();
            assertEquals(instances.get(i), actual);
        }
    }
}
