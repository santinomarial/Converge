package io.converge.exceptions;

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
import io.converge.exceptions.ExceptionQueueService.ResolutionAction;
import io.converge.ledger.AppendInventoryEvent;
import io.converge.ledger.EventKind;
import io.converge.ledger.InventoryEventType;
import io.converge.ledger.LedgerService;

@SpringBootTest
class ExceptionQueueServiceIntegrationTest extends IntegrationTestSupport {
    @Autowired ExceptionQueueService queue;
    @Autowired LedgerService ledger;
    @Autowired JdbcClient jdbc;
    UUID criticalId;

    @BeforeEach
    void seed() {
        jdbc.sql("""
                TRUNCATE sync_attempt, outbox, reconciliation_exception, drift_sample, external_position,
                         inventory_position, inventory_event, raw_webhook, identity_quarantine,
                         sku_mapping, location_mapping, canonical_sku, location RESTART IDENTITY CASCADE
                """).update();
        jdbc.sql("""
                INSERT INTO canonical_sku (id, sku, style, color, size) VALUES (1, 'E-1', 'E', 'RED', 'M');
                INSERT INTO location (id, code, name, location_type) VALUES (10, 'E-LOC', 'Exception', 'STORE');
                """).update();
        ledger.append(new AppendInventoryEvent(UUID.randomUUID(), 1, 10, "manual", "seed",
                InventoryEventType.COUNT, EventKind.SNAPSHOT, null, 10, Instant.now(), null, Map.of()));
        criticalId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO reconciliation_exception (
                    id, canonical_sku_id, location_id, type, severity, ledger_qty, observed
                ) VALUES (:critical, 1, 10, 'COUNT_MISMATCH', 'CRITICAL', 10, 8),
                         (:warning, 1, 10, 'PERSISTENT_DRIFT', 'WARNING', 10, 9)
                """).param("critical", criticalId).param("warning", UUID.randomUUID()).update();
    }

    @Test
    void claimsHighestSeverityAndResolvesByAppendingCausalAdjustment() {
        ReconciliationException claimed = queue.claimNext("operator-1").orElseThrow();
        assertThat(claimed.id()).isEqualTo(criticalId);

        ReconciliationException resolved = queue.resolve(
                criticalId, ResolutionAction.ADJUST_TO, 8, "physical recount", "operator-1");

        assertThat(resolved.state()).isEqualTo("RESOLVED");
        assertThat(ledger.getPosition(1, 10).orElseThrow().qty()).isEqualTo(8);
        assertThat(ledger.history(1, 10).getLast().causationId()).isEqualTo(criticalId);
        assertThat(jdbc.sql("SELECT count(*) FROM inventory_event").query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void dismissalClosesExceptionWithoutChangingLedger() {
        queue.claim(criticalId, "operator-1");
        queue.resolve(criticalId, ResolutionAction.DISMISS, null, "expected lag", "operator-1");
        assertThat(ledger.getPosition(1, 10).orElseThrow().qty()).isEqualTo(10);
        assertThat(jdbc.sql("SELECT count(*) FROM inventory_event").query(Long.class).single()).isOne();
    }
}

