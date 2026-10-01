package ch.benedict.m321.batchwriter.config;

import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Prüft die Writer-Deklaration gegen einen echten Broker ohne Datenbank oder Consumer. */
@Testcontainers
class RabbitConfigIntegrationTest {

    @Container
    private final RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    /** Nach dem Kontextstart müssen beide Queues ohne Publish oder Consumer existieren. */
    @Test
    void declaresQueuesAtStartup() throws Exception {
        try (AnnotationConfigApplicationContext context = startContext()) {
            CachingConnectionFactory factory = context.getBean(CachingConnectionFactory.class);
            try (Connection connection = factory.createConnection();
                 Channel channel = connection.createChannel(false)) {
                assertNotNull(channel.queueDeclarePassive("chat.persist"));
                assertNotNull(channel.queueDeclarePassive("chat.dlq"));
            }
        }
    }

    /** Aktive Wiederdeklaration prüft Eigenschaften und Argumente auf Broker-Kompatibilität. */
    @Test
    void matchesProducerTopologyOnRepeatedDeclaration() throws Exception {
        try (AnnotationConfigApplicationContext context = startContext()) {
            CachingConnectionFactory factory = context.getBean(CachingConnectionFactory.class);
            try (Connection connection = factory.createConnection();
                 Channel channel = connection.createChannel(false)) {
                // Vertrag aus chat-service/RabbitConfig: keine Abhängigkeit auf dessen Java-Modul.
                Map<String, Object> arguments = new HashMap<>();
                arguments.put("x-dead-letter-exchange", "");
                arguments.put("x-dead-letter-routing-key", "chat.dlq");
                for (int i = 0; i < 2; i++) {
                    channel.queueDeclare("chat.persist", true, false, false, arguments);
                    channel.queueDeclare("chat.dlq", true, false, false, null);
                }
                long consumerCount = channel.consumerCount("chat.persist");
                assertEquals(0, consumerCount);
            }
        }
    }

    /** Ein Konflikt darf nicht durch Löschen der vorhandenen Queue versteckt werden. */
    @Test
    void rejectsConflictingQueueProperties() throws Exception {
        try (AnnotationConfigApplicationContext context = startContext()) {
            CachingConnectionFactory factory = context.getBean(CachingConnectionFactory.class);
            try (Connection connection = factory.createConnection()) {
                Channel conflictingChannel = connection.createChannel(false);
                try {
                    assertThrows(IOException.class,
                            () -> conflictingChannel.queueDeclare("chat.dlq", false, false, false, null));
                } finally {
                    if (conflictingChannel.isOpen()) {
                        conflictingChannel.close();
                    }
                }
                try (Channel channel = connection.createChannel(false)) {
                    assertNotNull(channel.queueDeclare("chat.dlq", true, false, false, null));
                }
            }
        }
    }

    /** Nur die produktive Queue-Konfiguration wird geladen; der Test deklariert nicht vorab. */
    private AnnotationConfigApplicationContext startContext() {
        String host = rabbitMq.getHost();
        int port = rabbitMq.getAmqpPort();
        CachingConnectionFactory factory = new CachingConnectionFactory(host, port);
        String username = rabbitMq.getAdminUsername();
        String password = rabbitMq.getAdminPassword();
        factory.setUsername(username);
        factory.setPassword(password);
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(CachingConnectionFactory.class, () -> factory);
        context.register(RabbitConfig.class);
        try {
            context.refresh();
            return context;
        } catch (RuntimeException exception) {
            context.close();
            throw exception;
        }
    }
}
