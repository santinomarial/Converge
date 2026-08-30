package io.converge.ledger;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

public record InventoryEvent(
        long seq,
        UUID eventId,
        long canonicalSkuId,
        long locationId,
        String sourceSystem,
        String externalEventId,
        InventoryEventType eventType,
        EventKind kind,
        Integer qtyDelta,
        Integer qtyAbsolute,
        Instant occurredAt,
        Instant receivedAt,
        boolean absorbed,
        UUID causationId,
        JsonNode payload) {
}

