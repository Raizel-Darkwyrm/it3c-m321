package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import ch.benedict.m321.batchwriter.repository.MessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/** Ein Schreibversuch kehrt erst nach Commit zurück und bestätigt selbst keine Queue-Lieferung. */
@Service
public class BatchPersistenceService {

    private final MessageRepository messageRepository;
    private final TransactionTemplate transactionTemplate;

    /** Eine eigene Transaktion verhindert einen erst später erfolgenden Commit durch den Aufrufer. */
    public BatchPersistenceService(MessageRepository messageRepository,
                                   PlatformTransactionManager transactionManager) {
        this.messageRepository = messageRepository;
        transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Leere Stapel verursachen keine Transaktion; Fehler verlassen die Methode nach dem Rollback. */
    public void persist(List<ChatMessage> messages) {
        if (messages.isEmpty()) {
            return;
        }
        List<ChatMessage> batch = List.copyOf(messages);
        transactionTemplate.executeWithoutResult(status -> messageRepository.insertBatch(batch));
    }
}
