package io.converge.ingest;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
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
public class SquareWebhookNormalizer implements WebhookNormalizer {

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final IdentityService identity;
    private final LedgerService ledger;

    public SquareWebhookNormalizer(JdbcClient jdbc, ObjectMapper json, IdentityService identity, LedgerService ledger) {
        this.jdbc = jdbc;
        this.json = json;
        this.identity = identity;
        this.ledger = ledger;
    }

    @Override
    public String sourceSystem() { return "square"; }

    @Override
    @Transactional
    public void normalize(UUID rawWebhookId) {
        Map<String, Object> row = jdbc.sql("""
                SELECT external_event_id, payload FROM raw_webhook
                WHERE id = :id AND source_system = 'square' AND state IN ('CAPTURED', 'PUBLISHED')
                FOR UPDATE
                """).param("id", rawWebhookId).query().singleRow();
        try {
            JsonNode payload = json.readTree((byte[]) row.get("payload"));
            JsonNode count = payload.path("data").path("object").path("inventory_counts").path(0);
            Map<String, Object> payloadMap = json.convertValue(payload, new TypeReference<>() { });
            IdentityResolution resolution = identity.resolve("square",
                    count.path("catalog_object_id").asText(null), count.path("location_id").asText(null), payloadMap);
            if (resolution instanceof IdentityResolution.Quarantined quarantine) {
                mark(rawWebhookId, "QUARANTINED", quarantine.reason());
                return;
            }
            var canonical = ((IdentityResolution.Mapped) resolution).identity();
            ledger.append(new AppendInventoryEvent(UUID.randomUUID(), canonical.canonicalSkuId(), canonical.locationId(),
                    "square", (String) row.get("external_event_id"), InventoryEventType.COUNT, EventKind.SNAPSHOT,
                    null, Integer.parseInt(count.path("quantity").asText()),
                    Instant.parse(count.path("calculated_at").asText()), null, payloadMap));
            mark(rawWebhookId, "PROCESSED", null);
        } catch (Exception exception) {
            mark(rawWebhookId, "FAILED", exception.getMessage());
            throw new IllegalStateException("Failed to normalize Square webhook " + rawWebhookId, exception);
        }
    }

    private void mark(UUID id, String state, String error) {
        jdbc.sql("UPDATE raw_webhook SET state = :state, error = :error, processed_at = now() WHERE id = :id")
                .param("state", state).param("error", error).param("id", id).update();
    }
}

