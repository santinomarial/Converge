package io.converge.ingest;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
class RawWebhookPublisher {

    private final KafkaTemplate<String, String> kafka;

    RawWebhookPublisher(KafkaTemplate<String, String> kafka) {
        this.kafka = kafka;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void publish(RawWebhookCaptured event) {
        kafka.send("inventory.raw", event.id().toString());
    }
}

