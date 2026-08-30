package io.converge.exceptions;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import io.converge.ledger.AppendInventoryEvent;
import io.converge.ledger.EventKind;
import io.converge.ledger.InventoryEventType;
import io.converge.ledger.LedgerService;

@Service
public class ExceptionQueueService {

    private final JdbcClient jdbc;
    private final LedgerService ledger;

    public ExceptionQueueService(JdbcClient jdbc, LedgerService ledger) {
        this.jdbc = jdbc;
        this.ledger = ledger;
    }

    public List<ReconciliationException> find(String state, String severity) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, canonical_sku_id, location_id, target_system, type, severity,
                       ledger_qty, observed, detected_at, state, claimed_by, claimed_at,
                       resolution, resolution_note, resolved_at, resolution_event_id
                FROM reconciliation_exception WHERE 1 = 1
                """);
        if (state != null) sql.append(" AND state = :state");
        if (severity != null) sql.append(" AND severity = :severity");
        sql.append(" ORDER BY CASE severity WHEN 'CRITICAL' THEN 3 WHEN 'WARNING' THEN 2 ELSE 1 END DESC, detected_at");
        JdbcClient.StatementSpec query = jdbc.sql(sql.toString());
        if (state != null) query = query.param("state", state);
        if (severity != null) query = query.param("severity", severity);
        return query.query(this::mapException).list();
    }

    @Transactional
    public Optional<ReconciliationException> claimNext(String actor) {
        return jdbc.sql("""
                WITH candidate AS (
                    SELECT id FROM reconciliation_exception WHERE state = 'OPEN'
                    ORDER BY CASE severity WHEN 'CRITICAL' THEN 3 WHEN 'WARNING' THEN 2 ELSE 1 END DESC,
                             detected_at
                    FOR UPDATE SKIP LOCKED LIMIT 1
                )
                UPDATE reconciliation_exception e
                SET state = 'CLAIMED', claimed_by = :actor, claimed_at = now()
                FROM candidate WHERE e.id = candidate.id
                RETURNING e.*
                """).param("actor", actor).query(this::mapException).optional();
    }

    @Transactional
    public ReconciliationException claim(UUID id, String actor) {
        return jdbc.sql("""
                WITH candidate AS (
                    SELECT id FROM reconciliation_exception WHERE id = :id AND state = 'OPEN'
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE reconciliation_exception e
                SET state = 'CLAIMED', claimed_by = :actor, claimed_at = now()
                FROM candidate WHERE e.id = candidate.id
                RETURNING e.*
                """).param("id", id).param("actor", actor).query(this::mapException).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Exception is not open or is currently locked"));
    }

    @Transactional
    public ReconciliationException resolve(UUID id, ResolutionAction action, Integer qty, String note, String actor) {
        ReconciliationException exception = jdbc.sql("""
                SELECT * FROM reconciliation_exception WHERE id = :id FOR UPDATE
                """).param("id", id).query(this::mapException).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!"CLAIMED".equals(exception.state())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Exception must be claimed before resolution");
        }
        if (exception.claimedBy() != null && actor != null && !exception.claimedBy().equals(actor)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Exception is claimed by another actor");
        }

        UUID resolutionEventId = null;
        String nextState = "RESOLVED";
        if (action == ResolutionAction.ADJUST_TO) {
            if (qty == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "qty is required");
            int current = ledger.getPosition(exception.canonicalSkuId(), exception.locationId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Ledger position is missing"))
                    .qty();
            resolutionEventId = UUID.randomUUID();
            ledger.append(new AppendInventoryEvent(resolutionEventId, exception.canonicalSkuId(), exception.locationId(),
                    "manual", "exception-resolution:" + id, InventoryEventType.ADJUSTMENT, EventKind.DELTA,
                    qty - current, null, Instant.now(), id,
                    Map.of("exceptionId", id.toString(), "note", note == null ? "" : note)));
        } else if (action == ResolutionAction.DISMISS) {
            nextState = "DISMISSED";
        }

        return jdbc.sql("""
                UPDATE reconciliation_exception
                SET state = :state, resolution = :resolution, resolution_note = :note,
                    resolved_at = now(), resolution_event_id = :eventId
                WHERE id = :id
                RETURNING *
                """).param("state", nextState).param("resolution", action.name()).param("note", note)
                .param("eventId", resolutionEventId).param("id", id)
                .query(this::mapException).single();
    }

    private ReconciliationException mapException(ResultSet rs, int row) throws SQLException {
        return new ReconciliationException(rs.getObject("id", UUID.class), rs.getLong("canonical_sku_id"),
                rs.getLong("location_id"), rs.getString("target_system"), rs.getString("type"),
                rs.getString("severity"), rs.getInt("ledger_qty"), (Integer) rs.getObject("observed"),
                rs.getTimestamp("detected_at").toInstant(), rs.getString("state"), rs.getString("claimed_by"),
                instant(rs, "claimed_at"), rs.getString("resolution"), rs.getString("resolution_note"),
                instant(rs, "resolved_at"), rs.getObject("resolution_event_id", UUID.class));
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public enum ResolutionAction { ADJUST_TO, DISMISS }
}

