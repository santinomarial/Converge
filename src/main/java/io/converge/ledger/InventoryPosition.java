package io.converge.ledger;

import java.time.Instant;

public record InventoryPosition(
        long canonicalSkuId,
        long locationId,
        int qty,
        long anchorSeq,
        long lastAppliedSeq,
        Instant updatedAt) {
}

