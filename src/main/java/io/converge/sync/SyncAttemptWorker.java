package io.converge.sync;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import io.converge.connectors.InventorySink;
import io.converge.connectors.InventorySource;
import io.converge.ledger.AppendInventoryEvent;
import io.converge.ledger.EventKind;
import io.converge.ledger.InventoryEventType;
import io.converge.ledger.LedgerService;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

@Component
public class SyncAttemptWorker {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final RedisTokenBucket rateLimiter;
    private final CircuitBreakerRegistry breakers;
    private final Map<String, InventorySink> sinks;
    private final Map<String, InventorySource> sources;
    private final LedgerService ledger;
    private final SyncWriteObserver writeObserver;
    private final int maxAttempts;
    private final Duration runningLease;

    public SyncAttemptWorker(JdbcClient jdbc, TransactionTemplate transactions, RedisTokenBucket rateLimiter,
            CircuitBreakerRegistry breakers, List<InventorySink> sinks, List<InventorySource> sources,
            LedgerService ledger, ObjectProvider<SyncWriteObserver> writeObserver,
            @Value("${sync.max-attempts}") int maxAttempts,
            @Value("${sync.running-lease}") Duration runningLease) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.rateLimiter = rateLimiter;
        this.breakers = breakers;
        this.sinks = sinks.stream().collect(Collectors.toUnmodifiableMap(InventorySink::system, Function.identity()));
        this.sources = sources.stream().collect(Collectors.toUnmodifiableMap(InventorySource::system, Function.identity()));
        this.ledger = ledger;
        this.writeObserver = writeObserver.getIfAvailable(() -> attemptId -> { });
        this.maxAttempts = maxAttempts;
        this.runningLease = runningLease;
    }

    @Scheduled(fixedDelayString = "${sync.worker-delay}", initialDelayString = "${sync.worker-initial-delay}")
    public void work() {
        Attempt attempt = transactions.execute(status -> claim());
        if (attempt == null) return;
        if (!rateLimiter.tryAcquire(attempt.system())) {
            requeue(attempt.id(), attempt.attempt(), "rate limited", 1);
            return;
        }
        InventorySink sink = sinks.get(attempt.system());
        if (sink == null) {
            fail(attempt, new IllegalStateException("No inventory sink registered"));
            return;
        }
        try {
            breakers.circuitBreaker("sync-" + attempt.system()).executeRunnable(() -> {
                if (attempt.attempt() > 0 && remoteAlreadyMatches(attempt)) {
                    return;
                }
                sink.pushPosition(attempt.externalSku(), attempt.externalLocation(), attempt.targetQty(),
                        attempt.id().toString());
                writeObserver.afterExternalWrite(attempt.id());
            });
            markSucceeded(attempt.id());
        } catch (CallNotPermittedException open) {
            // An open breaker queues work for half-open recovery; it never drops the write or burns an attempt.
            requeue(attempt.id(), attempt.attempt(), "circuit breaker open", 2);
        } catch (RuntimeException failure) {
            fail(attempt, failure);
        }
    }

    private Attempt claim() {
        jdbc.sql("""
                UPDATE sync_attempt SET state = 'QUEUED', attempt = attempt + 1,
                    last_error = 'running lease expired after ambiguous external result',
                    next_attempt_at = now(), updated_at = now()
                WHERE state = 'RUNNING'
                  AND updated_at < now() - (:leaseMillis * interval '1 millisecond')
                """).param("leaseMillis", runningLease.toMillis()).update();
        return jdbc.sql("""
                UPDATE sync_attempt SET state = 'RUNNING', updated_at = now()
                WHERE id = (SELECT id FROM sync_attempt WHERE state = 'QUEUED' AND next_attempt_at <= now()
                            ORDER BY next_attempt_at, created_at FOR UPDATE SKIP LOCKED LIMIT 1)
                RETURNING id, outbox_id, canonical_sku_id, location_id, target_system,
                          external_sku_id, external_location_id, target_qty, attempt, compensation
                """).query((rs, row) -> new Attempt(rs.getObject("id", UUID.class),
                        rs.getObject("outbox_id", UUID.class), rs.getLong("canonical_sku_id"),
                        rs.getLong("location_id"), rs.getString("target_system"),
                        rs.getString("external_sku_id"), rs.getString("external_location_id"),
                        rs.getInt("target_qty"), rs.getInt("attempt"), rs.getBoolean("compensation")))
                .optional().orElse(null);
    }

    private boolean remoteAlreadyMatches(Attempt attempt) {
        InventorySource source = sources.get(attempt.system());
        return source != null
                && source.fetchPosition(attempt.externalSku(), attempt.externalLocation()).qty() == attempt.targetQty();
    }

    private void markSucceeded(UUID id) {
        jdbc.sql("UPDATE sync_attempt SET state = 'SUCCEEDED', updated_at = now() WHERE id = :id")
                .param("id", id).update();
    }

    private void fail(Attempt attempt, RuntimeException failure) {
        int nextAttempt = attempt.attempt() + 1;
        if (nextAttempt < maxAttempts) {
            long jitteredDelay = Math.min(60, 1L << Math.min(nextAttempt, 6))
                    + java.util.concurrent.ThreadLocalRandom.current().nextLong(0, 3);
            requeue(attempt.id(), nextAttempt, failure.getMessage(), jitteredDelay);
            return;
        }
        jdbc.sql("""
                UPDATE sync_attempt SET state = 'FAILED', attempt = :attempt,
                    last_error = :error, updated_at = now() WHERE id = :id
                """).param("attempt", nextAttempt).param("error", failure.getMessage()).param("id", attempt.id()).update();
        UUID exceptionId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO reconciliation_exception (
                    id, canonical_sku_id, location_id, target_system, type, severity, ledger_qty, observed
                ) VALUES (:id, :sku, :location, :system, 'SYNC_FAILED', 'CRITICAL', :qty, NULL)
                ON CONFLICT (canonical_sku_id, location_id, target_system, type)
                    WHERE state IN ('OPEN', 'CLAIMED') DO NOTHING
                """).param("id", exceptionId).param("sku", attempt.sku()).param("location", attempt.location())
                .param("system", attempt.system()).param("qty", attempt.targetQty()).update();

        Long successes = attempt.outboxId() == null ? 0L : jdbc.sql("""
                SELECT count(*) FROM sync_attempt WHERE outbox_id = :outboxId AND state = 'SUCCEEDED'
                """).param("outboxId", attempt.outboxId()).query(Long.class).single();
        if (successes > 0 && !attempt.compensation()) {
            /*
             * Honest compensation: the successful external write cannot be rolled back. Append a zero-delta
             * correction carrying the authoritative position, then let a fresh outbox cycle re-push every target.
             * A failed compensation opens an exception and stops; it does not recurse forever.
             */
            ledger.append(new AppendInventoryEvent(UUID.randomUUID(), attempt.sku(), attempt.location(), "manual",
                    "compensation:" + attempt.id(), InventoryEventType.ADJUSTMENT, EventKind.DELTA,
                    0, null, Instant.now(), exceptionId, Map.of("compensation", true, "failedAttempt", attempt.id().toString())));
        }
    }

    private void requeue(UUID id, int attempt, String error, long delaySeconds) {
        jdbc.sql("""
                UPDATE sync_attempt SET state = 'QUEUED', attempt = :attempt, last_error = :error,
                    next_attempt_at = now() + (:delay * interval '1 second'), updated_at = now()
                WHERE id = :id
                """).param("attempt", attempt).param("error", error).param("delay", delaySeconds).param("id", id).update();
    }

    private record Attempt(UUID id, UUID outboxId, long sku, long location, String system,
            String externalSku, String externalLocation, int targetQty, int attempt, boolean compensation) { }
}
