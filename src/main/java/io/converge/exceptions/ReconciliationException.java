package io.converge.exceptions;

import java.time.Instant;
import java.util.UUID;

public record ReconciliationException(
        UUID id,
        long canonicalSkuId,
        long locationId,
        String targetSystem,
        String type,
        String severity,
        int ledgerQty,
        Integer observed,
        Instant detectedAt,
        String state,
        String claimedBy,
        Instant claimedAt,
        String resolution,
        String resolutionNote,
        Instant resolvedAt,
        UUID resolutionEventId) {
}

