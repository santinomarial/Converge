package io.converge.ledger;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AppendInventoryEvent(
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
        UUID causationId,
        Map<String, Object> payload) {

    public AppendInventoryEvent {
        eventId = eventId == null ? UUID.randomUUID() : eventId;
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        if (sourceSystem == null || sourceSystem.isBlank()) {
            throw new IllegalArgumentException("sourceSystem is required");
        }
        if (kind == EventKind.DELTA && (qtyDelta == null || qtyAbsolute != null)) {
            throw new IllegalArgumentException("DELTA requires only qtyDelta");
        }
        if (kind == EventKind.SNAPSHOT && (qtyAbsolute == null || qtyDelta != null)) {
            throw new IllegalArgumentException("SNAPSHOT requires only qtyAbsolute");
        }
    }
}

