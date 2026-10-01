package ch.benedict.m321.batchwriter;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Collection;
import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft den Anwendungsstart mit echten Verbindungen und genau einem Queue-Consumer.
 * Die Testcontainer ersetzen lokale Zugangsdaten und benötigen keine Projektvolumes.
 */
@SpringBootTest(classes = BatchWriterApplication.class)
@Testcontainers
class BatchWriterApplicationTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Container
    private static final RabbitMQContainer rabbitMq = createRabbitMq();

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ConnectionFactory connectionFactory;

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;

    /** Eigene Testzugangsdaten prüfen die Anmeldung ohne Abhängigkeit von guest-Vorgaben. */
    private static RabbitMQContainer createRabbitMq() {
        RabbitMQContainer container = new RabbitMQContainer("rabbitmq:3.13-management");
        container.withAdminUser("broker_test");
        container.withAdminPassword("broker-test-password");
        return container;
    }

    /** Zufällige Container-Ports ersetzen nur im Verbindungstest die festen Produktionsports. */
    @DynamicPropertySource
    static void configureConnections(DynamicPropertyRegistry registry) {
        registry.add("POSTGRES_HOST", postgres::getHost);
        registry.add("POSTGRES_DB", postgres::getDatabaseName);
        registry.add("POSTGRES_USER", postgres::getUsername);
        registry.add("POSTGRES_PASSWORD", postgres::getPassword);
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("RABBITMQ_HOST", rabbitMq::getHost);
        registry.add("RABBITMQ_USER", rabbitMq::getAdminUsername);
        registry.add("RABBITMQ_PASSWORD", rabbitMq::getAdminPassword);
        registry.add("spring.rabbitmq.port", rabbitMq::getAmqpPort);
    }

    /** Der Start muss die Anwendungskonfiguration im Spring-Kontext bereitstellen. */
    @Test
    void startsApplicationContext() {
        BatchWriterApplication application = applicationContext.getBean(BatchWriterApplication.class);

        assertNotNull(application);
    }

    /** Ein normaler Anwendungskontext genügt, weil der Writer keine HTTP-API anbietet. */
    @Test
    void startsWithoutWebApplicationContext() {
        assertInstanceOf(AnnotationConfigApplicationContext.class, applicationContext);
    }

    /** Eine echte Abfrage beweist mehr als die blosse Existenz einer DataSource-Bean. */
    @Test
    void connectsToPostgresWithoutCreatingSchema() {
        Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        assertEquals(1, result);
        String sql = "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'";
        Integer tableCount = jdbcTemplate.queryForObject(sql, Integer.class);
        assertEquals(0, tableCount);
    }

    /** Die Anmeldung startet ab Aufgabe 12 genau einen Consumer auf der Eingangsqueue. */
    @Test
    void connectsToRabbitMqWithOneConsumer() {
        try (Connection connection = connectionFactory.createConnection()) {
            assertTrue(connection.isOpen());
        }
        Collection<MessageListenerContainer> consumers = listenerRegistry.getListenerContainers();
        int consumerCount = consumers.size();
        assertEquals(1, consumerCount);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            try (Connection connection = connectionFactory.createConnection();
                 com.rabbitmq.client.Channel channel = connection.createChannel(false)) {
                long activeConsumers = channel.consumerCount("chat.persist");
                assertEquals(1, activeConsumers);
            }
        });
    }
}
