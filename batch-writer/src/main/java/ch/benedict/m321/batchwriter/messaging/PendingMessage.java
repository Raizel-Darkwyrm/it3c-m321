package ch.benedict.m321.batchwriter.messaging;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import com.rabbitmq.client.Channel;

import java.util.Objects;

/** Verbindet die geprüfte Nachricht mit der später auf ihrem Empfangskanal zu bestätigenden Lieferung. */
public record PendingMessage(ChatMessage message, Channel channel, long deliveryTag) {

    /** Fehlende Referenzen und der für Sammelbestätigungen reservierte Tag 0 sind unzulässig. */
    public PendingMessage {
        Objects.requireNonNull(message, "Message must not be null");
        Objects.requireNonNull(channel, "Channel must not be null");
        if (deliveryTag <= 0) {
            throw new IllegalArgumentException("Delivery tag must be positive");
        }
    }
}
