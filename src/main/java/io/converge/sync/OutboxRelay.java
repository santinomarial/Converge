package io.converge.sync;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxRelay {
    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;

    public OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka) {
        this.jdbc = jdbc;
        this.kafka = kafka;
    }

    @Scheduled(fixedDelayString = "${sync.relay-delay}", initialDelayString = "${sync.relay-initial-delay}")
    @Transactional
    public void relay() {
        List<OutboxMessage> messages = jdbc.sql("""
                SELECT id, topic, payload::text FROM outbox
                WHERE published_at IS NULL ORDER BY created_at
                FOR UPDATE SKIP LOCKED LIMIT 100
                """).query((rs, row) -> new OutboxMessage(
                        rs.getObject("id", UUID.class), rs.getString("topic"), rs.getString("payload"))).list();
        for (OutboxMessage message : messages) {
            kafka.send(message.topic(), message.id().toString(), message.payload()).join();
            jdbc.sql("UPDATE outbox SET published_at = now() WHERE id = :id")
                    .param("id", message.id()).update();
        }
    }

    private record OutboxMessage(UUID id, String topic, String payload) { }
}

