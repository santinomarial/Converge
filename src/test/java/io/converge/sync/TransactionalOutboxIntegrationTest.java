package io.converge.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.converge.IntegrationTestSupport;
import io.converge.ledger.AppendInventoryEvent;
import io.converge.ledger.EventKind;
import io.converge.ledger.InventoryEventType;
import io.converge.ledger.LedgerService;

@SpringBootTest
class TransactionalOutboxIntegrationTest extends IntegrationTestSupport {
    @Autowired LedgerService ledger;
    @Autowired JdbcClient jdbc;
    @Autowired SyncPlanner planner;

    @BeforeEach
    void seed() {
        jdbc.sql("""
                TRUNCATE sync_attempt, outbox, reconciliation_exception, drift_sample, external_position,
                         inventory_position, inventory_event, raw_webhook, identity_quarantine,
                         sku_mapping, location_mapping, canonical_sku, location RESTART IDENTITY CASCADE
                """).update();
        jdbc.sql("""
                INSERT INTO canonical_sku (id, sku, style, color, size) VALUES (1, 'O-1', 'O', 'RED', 'M');
                INSERT INTO location (id, code, name, location_type) VALUES (10, 'O-LOC', 'Outbox', 'STORE');
                INSERT INTO sku_mapping (canonical_sku_id, system, external_id)
                    VALUES (1, 'shopify', 's-1'), (1, 'square', 'q-1');
                INSERT INTO location_mapping (location_id, system, external_id)
                    VALUES (10, 'shopify', 's-loc'), (10, 'square', 'q-loc');
                """).update();
    }

    @Test
    void eventProjectionAndOutboxAreCommittedTogetherAndPlanningIsIdempotent() {
        ledger.append(new AppendInventoryEvent(UUID.randomUUID(), 1, 10, "shopify", "sale-1",
                InventoryEventType.SALE, EventKind.DELTA, -2, null, Instant.now(), null, Map.of()));

        Map<String, Object> outbox = jdbc.sql("SELECT id, payload::text AS payload FROM outbox").query().singleRow();
        assertThat(jdbc.sql("SELECT count(*) FROM inventory_event").query(Long.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM inventory_position").query(Long.class).single()).isOne();
        assertThat(jdbc.sql("SELECT (payload ->> 'targetQty')::integer FROM outbox")
                .query(Integer.class).single()).isEqualTo(-2);

        UUID outboxId = (UUID) outbox.get("id");
        assertThat(planner.plan(outboxId, 1, 10, "shopify", -2, false)).isEqualTo(1);
        assertThat(planner.plan(outboxId, 1, 10, "shopify", -2, false)).isZero();
        assertThat(jdbc.sql("SELECT target_system FROM sync_attempt").query(String.class).single())
                .isEqualTo("square");
    }
}
