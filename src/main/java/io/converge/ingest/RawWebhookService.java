package io.converge.ingest;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RawWebhookService {

    private final JdbcClient jdbc;
    private final StringRedisTemplate redis;
    private final ApplicationEventPublisher events;

    public RawWebhookService(JdbcClient jdbc, StringRedisTemplate redis, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.events = events;
    }

    @Transactional
    public CaptureResult captureShopify(String externalEventId, String topic, byte[] rawBody) {
        String cacheKey = "webhook:shopify:" + externalEventId;
        try {
            redis.opsForValue().setIfAbsent(cacheKey, "seen", Duration.ofHours(24));
        } catch (DataAccessException ignored) {
            // Redis is only a latency optimization. Postgres remains the idempotency guarantee.
        }

        UUID id = UUID.randomUUID();
        Optional<UUID> inserted = jdbc.sql("""
                        INSERT INTO raw_webhook (id, source_system, external_event_id, topic, payload)
                        VALUES (:id, 'shopify', :externalId, :topic, :payload)
                        ON CONFLICT (source_system, external_event_id) DO NOTHING
                        RETURNING id
                        """)
                .param("id", id)
                .param("externalId", externalEventId)
                .param("topic", topic)
                .param("payload", rawBody)
                .query(UUID.class)
                .optional();
        if (inserted.isPresent()) {
            events.publishEvent(new RawWebhookCaptured(inserted.get()));
            return new CaptureResult(inserted.get(), true);
        }
        UUID existing = jdbc.sql("""
                        SELECT id FROM raw_webhook
                        WHERE source_system = 'shopify' AND external_event_id = :externalId
                        """)
                .param("externalId", externalEventId)
                .query(UUID.class)
                .single();
        return new CaptureResult(existing, false);
    }

    public record CaptureResult(UUID id, boolean inserted) {
    }
}

