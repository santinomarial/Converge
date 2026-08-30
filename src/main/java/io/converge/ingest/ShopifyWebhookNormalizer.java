package io.converge.ingest;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.converge.identity.IdentityResolution;
import io.converge.identity.IdentityService;
import io.converge.ledger.AppendInventoryEvent;
import io.converge.ledger.EventKind;
import io.converge.ledger.InventoryEventType;
import io.converge.ledger.LedgerService;

@Component
public class ShopifyWebhookNormalizer {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final IdentityService identity;
    private final LedgerService ledger;

    public ShopifyWebhookNormalizer(
            JdbcClient jdbc, ObjectMapper objectMapper, IdentityService identity, LedgerService ledger) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.identity = identity;
        this.ledger = ledger;
    }

    @KafkaListener(topics = "inventory.raw")
    @Transactional
    public void normalize(String rawWebhookId) {
        RawWebhook raw = jdbc.sql("""
                        SELECT id, external_event_id, topic, payload
                        FROM raw_webhook WHERE id = :id AND state IN ('CAPTURED', 'PUBLISHED')
                        FOR UPDATE
                        """)
                .param("id", UUID.fromString(rawWebhookId))
                .query(this::mapRaw)
                .optional()
                .orElse(null);
        if (raw == null) {
            return;
        }

        try {
            JsonNode payload = objectMapper.readTree(raw.payload());
            String externalSku = payload.path("inventory_item_id").asText(null);
            String externalLocation = payload.path("location_id").asText(null);
            Map<String, Object> payloadMap = objectMapper.convertValue(payload, new TypeReference<>() { });
            IdentityResolution resolution = identity.resolve(
                    "shopify", externalSku, externalLocation, payloadMap);
            if (resolution instanceof IdentityResolution.Quarantined quarantined) {
                mark(raw.id(), "QUARANTINED", quarantined.reason());
                return;
            }

            var canonical = ((IdentityResolution.Mapped) resolution).identity();
            Instant occurredAt = payload.hasNonNull("updated_at")
                    ? Instant.parse(payload.get("updated_at").asText())
                    : Instant.now();
            ledger.append(new AppendInventoryEvent(
                    UUID.randomUUID(), canonical.canonicalSkuId(), canonical.locationId(),
                    "shopify", raw.externalEventId(), InventoryEventType.COUNT, EventKind.SNAPSHOT,
                    null, payload.path("available").asInt(), occurredAt, null, payloadMap));
            mark(raw.id(), "PROCESSED", null);
        } catch (Exception exception) {
            mark(raw.id(), "FAILED", exception.getMessage());
            throw new IllegalStateException("Failed to normalize Shopify webhook " + raw.id(), exception);
        }
    }

    private void mark(UUID id, String state, String error) {
        jdbc.sql("""
                        UPDATE raw_webhook
                        SET state = :state, error = :error, processed_at = now()
                        WHERE id = :id
                        """)
                .param("state", state)
                .param("error", error)
                .param("id", id)
                .update();
    }

    private RawWebhook mapRaw(ResultSet rs, int rowNum) throws SQLException {
        return new RawWebhook(
                rs.getObject("id", UUID.class),
                rs.getString("external_event_id"),
                rs.getString("topic"),
                rs.getBytes("payload"));
    }

    private record RawWebhook(UUID id, String externalEventId, String topic, byte[] payload) {
    }
}

