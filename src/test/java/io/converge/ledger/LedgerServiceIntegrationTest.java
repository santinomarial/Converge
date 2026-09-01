package io.converge.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.Random;
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
        assertThat(after).isEqualTo(before);
    }

    @Test
    void incrementalCheckpointMatchesIndependentFullHistoryReducerAfterEveryAppend() {
        append("future-delta", EventKind.DELTA, 5, null, "2026-01-05T00:00:00Z");
        assertShadowMatches();
        append("initial-count", EventKind.SNAPSHOT, null, 100, "2026-01-02T00:00:00Z");
        assertShadowMatches();
        append("absorbed-delta", EventKind.DELTA, -20, null, "2026-01-01T00:00:00Z");
        assertShadowMatches();
        append("new-anchor", EventKind.SNAPSHOT, null, 40, "2026-01-04T00:00:00Z");
        assertShadowMatches();
        append("historical-snapshot", EventKind.SNAPSHOT, null, 999, "2026-01-03T00:00:00Z");
        assertShadowMatches();
        AppendResult last = append("new-sale", EventKind.DELTA, -3, null, "2026-01-06T00:00:00Z");
        assertShadowMatches();

        assertThat(last.position().qty()).isEqualTo(42);
        assertThat(last.position().lastAppliedSeq()).isEqualTo(last.seq());
        assertThat(ledger.findPositions(null, null)).singleElement()
                .extracting(InventoryPosition::qty).isEqualTo(42);
    }

    @Test
    void shadowReducerDetectsCorruptionAndReplayRepairsIt() {
        append("count", EventKind.SNAPSHOT, null, 25, "2026-01-01T00:00:00Z");
        append("sale", EventKind.DELTA, -4, null, "2026-01-02T00:00:00Z");
        jdbc.sql("UPDATE inventory_position SET qty = 9 WHERE canonical_sku_id = 1 AND location_id = 10")
                .update();

        assertThat(ledger.verifyProjection(1, 10).matches()).isFalse();
        assertThat(ledger.replay()).isEqualTo(1);
        assertShadowMatches();
        assertThat(ledger.getPosition(1, 10).orElseThrow().qty()).isEqualTo(21);
    }

    @Test
    void replayRecoversWhenProjectorCrashesAtARandomSequence() {
        Random random = new Random(0xC0FFEE);
        int[] deltas = new int[200];
        int expected = 0;
        for (int i = 0; i < deltas.length; i++) {
            deltas[i] = random.nextInt(-5, 6);
            expected += deltas[i];
            append("random-" + i, EventKind.DELTA, deltas[i], null,
                    Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i).toString());
        }
        int crashIndex = random.nextInt(1, deltas.length - 1);
        int prefixQty = java.util.Arrays.stream(deltas, 0, crashIndex + 1).sum();
        long crashSeq = ledger.history(1, 10).get(crashIndex).seq();
        jdbc.sql("""
                UPDATE inventory_position SET qty = :qty, last_applied_seq = :seq
                WHERE canonical_sku_id = 1 AND location_id = 10
                """).param("qty", prefixQty).param("seq", crashSeq).update();

        assertThat(ledger.verifyProjection(1, 10).matches()).isFalse();
        assertThat(ledger.replay()).isOne();
        assertThat(ledger.getPosition(1, 10).orElseThrow().qty()).isEqualTo(expected);
        assertShadowMatches();
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

    private void assertShadowMatches() {
        assertThat(ledger.verifyProjection(1, 10).matches()).isTrue();
    }
}
