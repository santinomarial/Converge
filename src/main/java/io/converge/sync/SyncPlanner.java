package io.converge.sync;

import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class SyncPlanner {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public SyncPlanner(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @KafkaListener(topics = "inventory.position.changed", groupId = "converge-sync-planner")
    public void onPositionChanged(String payload,
            @Header(org.springframework.kafka.support.KafkaHeaders.RECEIVED_KEY) String outboxId) throws Exception {
        Map<String, Object> data = json.readValue(payload, new TypeReference<>() { });
        plan(UUID.fromString(outboxId), ((Number) data.get("canonicalSkuId")).longValue(),
                ((Number) data.get("locationId")).longValue(), (String) data.get("sourceSystem"),
                ((Number) data.get("targetQty")).intValue(), Boolean.TRUE.equals(data.get("compensation")));
    }

    @Transactional
    public int plan(UUID outboxId, long sku, long location, String sourceSystem, int targetQty, boolean compensation) {
        return jdbc.sql("""
                INSERT INTO sync_attempt (
                    id, outbox_id, canonical_sku_id, location_id, target_system,
                    external_sku_id, external_location_id, target_qty, compensation
                )
                SELECT gen_random_uuid(), :outboxId, :sku, :location, sm.system,
                       sm.external_id, lm.external_id, :targetQty, :compensation
                FROM sku_mapping sm
                JOIN location_mapping lm ON lm.location_id = :location AND lm.system = sm.system
                WHERE sm.canonical_sku_id = :sku AND sm.system <> :sourceSystem
                ON CONFLICT (outbox_id, target_system) DO NOTHING
                """).param("outboxId", outboxId).param("sku", sku).param("location", location)
                .param("sourceSystem", sourceSystem).param("targetQty", targetQty)
                .param("compensation", compensation).update();
    }

    @Transactional
    public int planManual(long sku, long location) {
        int targetQty = jdbc.sql("SELECT qty FROM inventory_position WHERE canonical_sku_id = :sku AND location_id = :loc")
                .param("sku", sku).param("loc", location).query(Integer.class).single();
        return plan(UUID.randomUUID(), sku, location, "manual", targetQty, false);
    }
}

