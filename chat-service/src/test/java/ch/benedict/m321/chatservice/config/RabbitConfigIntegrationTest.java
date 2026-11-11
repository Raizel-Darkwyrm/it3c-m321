package ch.benedict.m321.chatservice.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft gegen einen ECHTEN RabbitMQ, dass unsere Queues beim Start
 * tatsächlich angelegt werden. Ein Mock würde hier nichts beweisen:
 * die Deklaration passiert im Broker, nicht in unserem Code.
 */
@SpringBootTest
@Testcontainers
class RabbitConfigIntegrationTest {

    /**
     * ServiceConnection setzt spring.rabbitmq.host und -port automatisch
     * auf den gestarteten Container. Wir müssen nichts konfigurieren.
     */
    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    @Qualifier("deliveryQueue")
    private Queue deliveryQueue;

    @Autowired
    @Qualifier("deliveryBinding")
    private Binding deliveryBinding;

    @Test
    void declaresPersistQueue() {
        Properties properties = rabbitAdmin.getQueueProperties(QueueNames.PERSIST_QUEUE);

        assertNotNull(properties);
    }

    @Test
    void declaresDeadLetterQueue() {
        Properties properties = rabbitAdmin.getQueueProperties(QueueNames.DEAD_LETTER_QUEUE);

        assertNotNull(properties);
    }

    /** Die lokale Zustellqueue darf weder dauerhaft noch zwischen Instanzen geteilt sein. */
    @Test
    void declaresLimitedExclusiveDeliveryQueue() {
        rabbitAdmin.declareQueue(deliveryQueue);
        Properties properties = rabbitAdmin.getQueueProperties(deliveryQueue.getName());

        assertNotNull(properties);
        assertFalse(deliveryQueue.isDurable());
        assertTrue(deliveryQueue.isExclusive());
        assertTrue(deliveryQueue.isAutoDelete());
        assertEquals(1000, deliveryQueue.getArguments().get("x-max-length"));
        assertEquals("drop-head", deliveryQueue.getArguments().get("x-overflow"));
    }

    /** Das Binding muss genau die Instanzqueue mit dem bestehenden Fanout-Exchange verbinden. */
    @Test
    void bindsDeliveryQueueToFanoutExchange() {
        assertEquals(deliveryQueue.getName(), deliveryBinding.getDestination());
        assertEquals(QueueNames.DELIVERY_EXCHANGE, deliveryBinding.getExchange());
    }
}
