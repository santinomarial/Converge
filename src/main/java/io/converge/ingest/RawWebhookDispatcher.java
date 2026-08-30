package io.converge.ingest;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class RawWebhookDispatcher {

    private final JdbcClient jdbc;
    private final Map<String, WebhookNormalizer> normalizers;

    RawWebhookDispatcher(JdbcClient jdbc, List<WebhookNormalizer> normalizers) {
        this.jdbc = jdbc;
        this.normalizers = normalizers.stream()
                .collect(Collectors.toUnmodifiableMap(WebhookNormalizer::sourceSystem, Function.identity()));
    }

    @KafkaListener(topics = "inventory.raw")
    void dispatch(String id) {
        UUID rawId = UUID.fromString(id);
        String source = jdbc.sql("SELECT source_system FROM raw_webhook WHERE id = :id")
                .param("id", rawId)
                .query(String.class)
                .optional()
                .orElse(null);
        if (source == null) {
            return;
        }
        WebhookNormalizer normalizer = normalizers.get(source);
        if (normalizer == null) {
            jdbc.sql("UPDATE raw_webhook SET state = 'FAILED', error = 'No normalizer registered' WHERE id = :id")
                    .param("id", rawId)
                    .update();
            return;
        }
        normalizer.normalize(rawId);
    }
}

