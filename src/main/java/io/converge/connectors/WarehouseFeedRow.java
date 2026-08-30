package io.converge.connectors;

import java.time.Instant;

public record WarehouseFeedRow(String externalSkuId, String externalLocationId, int qty, Instant occurredAt) {
}

