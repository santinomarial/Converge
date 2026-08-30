package io.converge.reconcile;

import java.time.Instant;

public record DriftSample(
        long canonicalSkuId,
        long locationId,
        String system,
        int drift,
        Instant sampledAt) {
}

