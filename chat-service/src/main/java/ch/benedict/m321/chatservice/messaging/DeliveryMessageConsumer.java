package ch.benedict.m321.chatservice.messaging;

import ch.benedict.m321.chatservice.dto.ChatMessage;
import ch.benedict.m321.chatservice.service.LiveMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/** Übergibt die Fanout-Kopie dieser Instanz an ihren lokalen Replay- und SSE-Zustand. */
@Component
@RequiredArgsConstructor
@Slf4j
public class DeliveryMessageConsumer {

    private final LiveMessageService liveMessageService;

    /** Ein einzelner Consumer bewahrt die Empfangsreihenfolge innerhalb der Instanzqueue. */
    @RabbitListener(queues = "#{deliveryQueue.name}", concurrency = "1")
    public void receive(ChatMessage message) {
        liveMessageService.deliver(message);
        log.debug("Live message handled: id={}, room={}", message.id(), message.roomId());
    }
}
