package io.converge.connectors.warehouse;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import io.converge.connectors.WarehouseFeedRow;

class WarehouseCsvAdapterTest {

    @Test
    void parsesStrictSnapshotFeed() {
        var rows = new WarehouseCsvAdapter().parse(new StringReader("""
                sku_id,location_id,qty,occurred_at
                W-42,MAIN,81,2026-08-30T12:00:00Z
                """));
        assertThat(rows).containsExactly(new WarehouseFeedRow("W-42", "MAIN", 81,
                Instant.parse("2026-08-30T12:00:00Z")));
    }
}

