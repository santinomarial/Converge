package io.converge.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.converge.IntegrationTestSupport;

@SpringBootTest
class LedgerServiceIntegrationTest extends IntegrationTestSupport {

    @Autowired
    LedgerService ledger;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clearLedger() {
        jdbc.sql("""
                TRUNCATE inventory_position, inventory_event, sku_mapping, location_mapping,
                         canonical_sku, location RESTART IDENTITY CASCADE
                """).update();
        jdbc.sql("""
                INSERT INTO canonical_sku (id, sku, style, color, size)
                VALUES (1, 'SYNTH-1', 'SYNTH', 'BLACK', 'ONE')
                """).update();
        jdbc.sql("""
                INSERT INTO location (id, code, name, location_type)
                VALUES (10, 'SYNTH-LOC', 'Synthetic location', 'WAREHOUSE')
                """).update();
    }

    @Test
    void projectsOutOfOrderDeltasAroundLatestSnapshotAndReplaysExactly() {
        append("delta-after", EventKind.DELTA, 5, null, "2026-01-02T12:00:00Z");
        append("old-delta", EventKind.DELTA, -30, null, "2026-01-01T12:00:00Z");
        append("count", EventKind.SNAPSHOT, null, 40, "2026-01-02T00:00:00Z");
        AppendResult late = append("late-before-anchor", EventKind.DELTA, 10, null, "2026-01-01T18:00:00Z");

        assertThat(late.position().qty()).isEqualTo(45);
        assertThat(ledger.history(1, 10))
                .filteredOn(InventoryEvent::absorbed)
                .extracting(InventoryEvent::externalEventId)
                .containsExactly("late-before-anchor");

        InventoryPosition before = ledger.getPosition(1, 10).orElseThrow();
        assertThat(ledger.replay()).isEqualTo(1);
        InventoryPosition after = ledger.getPosition(1, 10).orElseThrow();
        assertThat(after.qty()).isEqualTo(before.qty());
        assertThat(after.anchorSeq()).isEqualTo(before.anchorSeq());
        assertThat(after.lastAppliedSeq()).isEqualTo(before.lastAppliedSeq());
    }

    @Test
    void duplicateExternalEventIsIdempotentAndLogRejectsMutation() {
        AppendResult first = append("same", EventKind.DELTA, -2, null, "2026-01-01T00:00:00Z");
        AppendResult duplicate = append("same", EventKind.DELTA, -2, null, "2026-01-01T00:00:00Z");

        assertThat(first.inserted()).isTrue();
        assertThat(duplicate.inserted()).isFalse();
        assertThat(duplicate.seq()).isEqualTo(first.seq());
        assertThat(jdbc.sql("SELECT count(*) FROM inventory_event").query(Long.class).single()).isOne();
        assertThatThrownBy(() -> jdbc.sql("UPDATE inventory_event SET absorbed = true").update())
                .hasMessageContaining("append-only");
    }

    private AppendResult append(String externalId, EventKind kind, Integer delta, Integer absolute, String time) {
        return ledger.append(new AppendInventoryEvent(
                UUID.randomUUID(), 1, 10, "synthetic", externalId,
                kind == EventKind.SNAPSHOT ? InventoryEventType.COUNT : InventoryEventType.ADJUSTMENT,
                kind, delta, absolute, Instant.parse(time), null, Map.of("test", true)));
    }
}
