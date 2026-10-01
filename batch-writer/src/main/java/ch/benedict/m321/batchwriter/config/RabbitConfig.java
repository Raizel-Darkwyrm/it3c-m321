package ch.benedict.m321.batchwriter.config;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Stellt die beiden Writer-Queues unabhängig vom Start des chat-service bereit. */
@Configuration
public class RabbitConfig {

    /** Gleiche Eigenschaften wie beim Producer erlauben wiederholte Deklarationen. */
    @Bean
    public Queue persistQueue() {
        QueueBuilder builder = QueueBuilder.durable(QueueNames.PERSIST_QUEUE);
        builder.deadLetterExchange("");
        builder.deadLetterRoutingKey(QueueNames.DEAD_LETTER_QUEUE);
        return builder.build();
    }

    /** Endgültig abgelehnte Lieferungen bleiben in einer dauerhaften Fehlerqueue erhalten. */
    @Bean
    public Queue deadLetterQueue() {
        QueueBuilder builder = QueueBuilder.durable(QueueNames.DEAD_LETTER_QUEUE);
        return builder.build();
    }

    /** Erzwingt die Deklaration beim Start, auch solange noch kein Consumer existiert. */
    @Bean(initMethod = "initialize")
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }
}
