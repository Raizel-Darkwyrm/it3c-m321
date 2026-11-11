package ch.benedict.m321.chatservice.config;

import ch.benedict.m321.chatservice.ChatServiceApplication;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Properties;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Prüft die Queue-Lebensdauer über zwei echte, voneinander unabhängige Dienstinstanzen. */
@Testcontainers
class DeliveryQueueLifecycleIntegrationTest {

    @Container
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    /** Jede Instanz muss ihre eigene Queue besitzen und beim Ende nur diese freigeben. */
    @Test
    void removesOnlyQueueOfClosedInstance() {
        ConfigurableApplicationContext first = startInstance();
        ConfigurableApplicationContext second = startInstance();
        try {
            Queue firstQueue = first.getBean("deliveryQueue", Queue.class);
            Queue secondQueue = second.getBean("deliveryQueue", Queue.class);
            RabbitAdmin firstAdmin = first.getBean(RabbitAdmin.class);
            RabbitAdmin secondAdmin = second.getBean(RabbitAdmin.class);
            firstAdmin.initialize();
            secondAdmin.initialize();

            assertNotEquals(firstQueue.getName(), secondQueue.getName());
            assertNotNull(firstAdmin.getQueueProperties(firstQueue.getName()));
            assertNotNull(secondAdmin.getQueueProperties(secondQueue.getName()));

            second.close();

            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                Properties firstProperties = firstAdmin.getQueueProperties(firstQueue.getName());
                Properties secondProperties = firstAdmin.getQueueProperties(secondQueue.getName());
                assertNotNull(firstProperties);
                assertNull(secondProperties);
            });
        } finally {
            if (second.isActive()) {
                second.close();
            }
            first.close();
        }
    }

    /** Startet eine eigenständige Anwendung mit einer eigenen AMQP-Verbindung zum Testbroker. */
    private ConfigurableApplicationContext startInstance() {
        String host = rabbitMq.getHost();
        int port = rabbitMq.getAmqpPort();
        return new SpringApplicationBuilder(ChatServiceApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.rabbitmq.host=" + host,
                        "--spring.rabbitmq.port=" + port,
                        "--spring.rabbitmq.username=guest",
                        "--spring.rabbitmq.password=guest",
                        "--spring.main.banner-mode=off",
                        "--logging.level.root=WARN");
    }
}
