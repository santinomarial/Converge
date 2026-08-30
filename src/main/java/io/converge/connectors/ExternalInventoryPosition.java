package io.converge.connectors;

import java.time.Instant;

public record ExternalInventoryPosition(
        String system,
        String externalSkuId,
        String externalLocationId,
        int qty,
        Instant observedAt) {
}

