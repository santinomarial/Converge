package io.converge.reconcile;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DriftService {

    private final JdbcClient jdbc;
    private final DriftMetrics metrics;

    public DriftService(JdbcClient jdbc, DriftMetrics metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    @Transactional
    public DriftObservation observe(long sku, long location, String system, int externalQty, Instant observedAt) {
        int ledgerQty = jdbc.sql("""
                        SELECT qty FROM inventory_position
                        WHERE canonical_sku_id = :sku AND location_id = :location
                        FOR SHARE
                        """)
                .param("sku", sku).param("location", location).query(Integer.class).single();
        Integer previousCycles = jdbc.sql("""
                        SELECT consecutive_drift_cycles FROM external_position
                        WHERE canonical_sku_id = :sku AND location_id = :location AND system = :system
                        FOR UPDATE
                        """)
                .param("sku", sku).param("location", location).param("system", system)
                .query(Integer.class).optional().orElse(0);
        int drift = externalQty - ledgerQty;
        int cycles = drift == 0 ? 0 : previousCycles + 1;

        jdbc.sql("""
                INSERT INTO external_position (
                    canonical_sku_id, location_id, system, qty, observed_at, consecutive_drift_cycles
                ) VALUES (:sku, :location, :system, :qty, :observedAt, :cycles)
                ON CONFLICT (canonical_sku_id, location_id, system) DO UPDATE SET
                    qty = EXCLUDED.qty, observed_at = EXCLUDED.observed_at,
                    consecutive_drift_cycles = EXCLUDED.consecutive_drift_cycles
                """).param("sku", sku).param("location", location).param("system", system)
                .param("qty", externalQty).param("observedAt", Timestamp.from(observedAt))
                .param("cycles", cycles).update();
        jdbc.sql("""
                INSERT INTO drift_sample (canonical_sku_id, location_id, system, drift, sampled_at)
                VALUES (:sku, :location, :system, :drift, :sampledAt)
                """).param("sku", sku).param("location", location).param("system", system)
                .param("drift", drift).param("sampledAt", Timestamp.from(observedAt)).update();
        metrics.record(system, drift);

        boolean exceptionOpened = cycles >= 2;
        if (exceptionOpened) {
            jdbc.sql("""
                    INSERT INTO reconciliation_exception (
                        id, canonical_sku_id, location_id, target_system, type, severity, ledger_qty, observed
                    ) VALUES (:id, :sku, :location, :system, 'PERSISTENT_DRIFT', :severity, :ledger, :observed)
                    ON CONFLICT (canonical_sku_id, location_id, target_system, type)
                        WHERE state IN ('OPEN', 'CLAIMED')
                    DO UPDATE SET ledger_qty = EXCLUDED.ledger_qty, observed = EXCLUDED.observed,
                                  severity = EXCLUDED.severity
                    """).param("id", UUID.randomUUID()).param("sku", sku).param("location", location)
                    .param("system", system).param("severity", Math.abs(drift) > 10 ? "CRITICAL" : "WARNING")
                    .param("ledger", ledgerQty).param("observed", externalQty).update();
        }
        return new DriftObservation(drift, cycles, exceptionOpened);
    }

    public List<DriftSample> samples(String system, Duration window) {
        Instant since = Instant.now().minus(window);
        return jdbc.sql("""
                SELECT canonical_sku_id, location_id, system, drift, sampled_at
                FROM drift_sample
                WHERE (CAST(:system AS text) IS NULL OR system = :system) AND sampled_at >= :since
                ORDER BY sampled_at
                """).param("system", system).param("since", Timestamp.from(since))
                .query(this::mapSample).list();
    }

    private DriftSample mapSample(ResultSet rs, int row) throws SQLException {
        return new DriftSample(rs.getLong("canonical_sku_id"), rs.getLong("location_id"),
                rs.getString("system"), rs.getInt("drift"), rs.getTimestamp("sampled_at").toInstant());
    }

    public record DriftObservation(int drift, int consecutiveCycles, boolean exceptionOpened) { }
}
