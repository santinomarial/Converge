package io.converge.ledger;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Continuously samples incremental checkpoints against the independent full-history reducer.
 * A mismatch is observable but never silently "fixed" here; replay remains an explicit operation.
 */
@Component
class ProjectionShadowVerifier {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectionShadowVerifier.class);

    private final LedgerService ledger;
    private final int batchSize;
    private final Counter verified;
    private final Counter mismatches;
    private long afterSku = Long.MIN_VALUE;
    private long afterLocation = Long.MIN_VALUE;

    ProjectionShadowVerifier(LedgerService ledger, MeterRegistry meters,
            @Value("${ledger.shadow-verification-batch-size}") int batchSize) {
        this.ledger = ledger;
        this.batchSize = batchSize;
        this.verified = Counter.builder("inventory.projection.shadow.verified")
                .description("Incremental projections checked against full event replay")
                .register(meters);
        this.mismatches = Counter.builder("inventory.projection.shadow.mismatches")
                .description("Incremental projections that differ from full event replay")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${ledger.shadow-verification-delay}",
            initialDelayString = "${ledger.shadow-verification-initial-delay}")
    void verifyNextBatch() {
        List<LedgerService.AggregateKey> keys = ledger.aggregateKeysAfter(afterSku, afterLocation, batchSize);
        if (keys.isEmpty()) {
            afterSku = Long.MIN_VALUE;
            afterLocation = Long.MIN_VALUE;
            return;
        }
        for (LedgerService.AggregateKey key : keys) {
            LedgerService.ProjectionVerification result = ledger.verifyProjection(key.sku(), key.location());
            verified.increment();
            if (!result.matches()) {
                mismatches.increment();
                LOGGER.error("Projection shadow mismatch sku={} location={} actual={} expected={}",
                        key.sku(), key.location(), result.actual(), result.expected());
            }
            afterSku = key.sku();
            afterLocation = key.location();
        }
    }
}
