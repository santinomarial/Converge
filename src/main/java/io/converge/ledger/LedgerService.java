package io.converge.ledger;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class LedgerService {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public LedgerService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AppendResult append(AppendInventoryEvent event) {
        boolean absorbed = lockAggregateAndDetermineAbsorption(event);
        Optional<StoredEvent> inserted = jdbc.sql("""
                        INSERT INTO inventory_event (
                            event_id, canonical_sku_id, location_id, source_system,
                            external_event_id, event_type, kind, qty_delta, qty_absolute,
                            occurred_at, absorbed, causation_id, payload
                        ) VALUES (
                            :eventId, :sku, :location, :source, :externalId, :eventType,
                            :kind, :delta, :absolute, :occurredAt, :absorbed, :causationId,
                            CAST(:payload AS jsonb)
                        )
                        ON CONFLICT (source_system, external_event_id) DO NOTHING
                        RETURNING seq, received_at
                        """)
                .param("eventId", event.eventId())
                .param("sku", event.canonicalSkuId())
                .param("location", event.locationId())
                .param("source", event.sourceSystem())
                .param("externalId", event.externalEventId())
                .param("eventType", event.eventType().name())
                .param("kind", event.kind().name())
                .param("delta", event.qtyDelta())
                .param("absolute", event.qtyAbsolute())
                .param("occurredAt", Timestamp.from(event.occurredAt()))
                .param("absorbed", absorbed)
                .param("causationId", event.causationId())
                .param("payload", toJson(event))
                .query((rs, row) -> new StoredEvent(rs.getLong("seq"),
                        rs.getTimestamp("received_at").toInstant()))
                .optional();

        if (inserted.isEmpty()) {
            long existingSeq = jdbc.sql("""
                            SELECT seq FROM inventory_event
                            WHERE source_system = :source AND external_event_id IS NOT DISTINCT FROM :externalId
                            """)
                    .param("source", event.sourceSystem())
                    .param("externalId", event.externalEventId())
                    .query(Long.class)
                    .single();
            return new AppendResult(existingSeq, false,
                    getPosition(event.canonicalSkuId(), event.locationId()).orElseThrow());
        }

        StoredEvent stored = inserted.get();
        InventoryPosition position = applyIncrementally(event, stored, absorbed);
        writeOutbox(event, position);
        return new AppendResult(stored.seq(), true, position);
    }

    public Optional<InventoryPosition> getPosition(long sku, long location) {
        return jdbc.sql("""
                        SELECT canonical_sku_id, location_id, qty, anchor_seq, last_applied_seq, updated_at
                        FROM inventory_position
                        WHERE canonical_sku_id = :sku AND location_id = :location
                        """)
                .param("sku", sku)
                .param("location", location)
                .query(this::mapPosition)
                .optional();
    }

    public List<InventoryPosition> findPositions(Long sku, Long location) {
        return jdbc.sql("""
                        SELECT canonical_sku_id, location_id, qty, anchor_seq, last_applied_seq, updated_at
                        FROM inventory_position
                        WHERE (CAST(:sku AS bigint) IS NULL OR canonical_sku_id = :sku)
                          AND (CAST(:location AS bigint) IS NULL OR location_id = :location)
                        ORDER BY canonical_sku_id, location_id
                        """)
                .param("sku", sku)
                .param("location", location)
                .query(this::mapPosition)
                .list();
    }

    public List<InventoryEvent> history(long sku, long location) {
        return jdbc.sql("""
                        SELECT seq, event_id, canonical_sku_id, location_id, source_system,
                               external_event_id, event_type, kind, qty_delta, qty_absolute,
                               occurred_at, received_at, absorbed, causation_id, payload::text
                        FROM inventory_event
                        WHERE canonical_sku_id = :sku AND location_id = :location
                        ORDER BY occurred_at, seq
                        """)
                .param("sku", sku)
                .param("location", location)
                .query(this::mapEvent)
                .list();
    }

    @Transactional
    public int replay() {
        jdbc.sql("TRUNCATE inventory_position").update();
        List<AggregateKey> aggregates = jdbc.sql("""
                        SELECT DISTINCT canonical_sku_id, location_id
                        FROM inventory_event
                        ORDER BY canonical_sku_id, location_id
                        """)
                .query((rs, row) -> new AggregateKey(rs.getLong(1), rs.getLong(2)))
                .list();
        aggregates.forEach(key -> projectAggregateFromHistory(key.sku(), key.location()));
        return aggregates.size();
    }

    /**
     * Independently reduces the complete event history and compares it with the incremental
     * checkpoint. The scheduled shadow verifier uses this path to detect projector defects.
     */
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ProjectionVerification verifyProjection(long sku, long location) {
        InventoryPosition actual = getPosition(sku, location).orElse(null);
        InventoryPosition expected = calculateFromHistory(sku, location);
        return new ProjectionVerification(actual, expected, expected.equals(actual));
    }

    List<AggregateKey> aggregateKeysAfter(long sku, long location, int limit) {
        return jdbc.sql("""
                SELECT canonical_sku_id, location_id
                FROM inventory_position
                WHERE (canonical_sku_id, location_id) > (:sku, :location)
                ORDER BY canonical_sku_id, location_id
                LIMIT :limit
                """).param("sku", sku).param("location", location).param("limit", limit)
                .query((rs, row) -> new AggregateKey(rs.getLong(1), rs.getLong(2))).list();
    }

    private boolean lockAggregateAndDetermineAbsorption(AppendInventoryEvent event) {
        return jdbc.sql("""
                        WITH aggregate_lock AS MATERIALIZED (
                            SELECT pg_advisory_xact_lock(hashtextextended(
                                CAST(:sku AS text) || ':' || CAST(:location AS text), 0))
                        )
                        SELECT :isDelta AND EXISTS (
                            SELECT 1 FROM inventory_event
                            WHERE canonical_sku_id = :sku AND location_id = :location
                              AND kind = 'SNAPSHOT' AND occurred_at >= :occurredAt
                        )
                        FROM aggregate_lock
                        """)
                .param("sku", event.canonicalSkuId())
                .param("location", event.locationId())
                .param("isDelta", event.kind() == EventKind.DELTA)
                .param("occurredAt", Timestamp.from(event.occurredAt()))
                .query(Boolean.class)
                .single();
    }

    private InventoryPosition applyIncrementally(AppendInventoryEvent event, StoredEvent stored, boolean absorbed) {
        return event.kind() == EventKind.DELTA
                ? applyDelta(event, stored, absorbed)
                : applySnapshot(event, stored);
    }

    private InventoryPosition applyDelta(AppendInventoryEvent event, StoredEvent stored, boolean absorbed) {
        return jdbc.sql("""
                INSERT INTO inventory_position (
                    canonical_sku_id, location_id, qty, anchor_seq, last_applied_seq, updated_at
                ) VALUES (:sku, :location, :delta, 0, :seq, :updatedAt)
                ON CONFLICT (canonical_sku_id, location_id) DO UPDATE SET
                    qty = inventory_position.qty + EXCLUDED.qty,
                    last_applied_seq = EXCLUDED.last_applied_seq,
                    updated_at = EXCLUDED.updated_at
                RETURNING canonical_sku_id, location_id, qty, anchor_seq, last_applied_seq, updated_at
                """).param("sku", event.canonicalSkuId()).param("location", event.locationId())
                .param("delta", absorbed ? 0 : event.qtyDelta()).param("seq", stored.seq())
                .param("updatedAt", Timestamp.from(stored.receivedAt()))
                .query(this::mapPosition).single();
    }

    private InventoryPosition applySnapshot(AppendInventoryEvent event, StoredEvent stored) {
        return jdbc.sql("""
                WITH latest_anchor AS (
                    SELECT seq, occurred_at, qty_absolute
                    FROM inventory_event
                    WHERE canonical_sku_id = :sku AND location_id = :location AND kind = 'SNAPSHOT'
                    ORDER BY occurred_at DESC, seq DESC LIMIT 1
                ), calculated AS (
                    SELECT a.seq AS anchor_seq,
                           a.qty_absolute + COALESCE(SUM(e.qty_delta), 0)::integer AS qty
                    FROM latest_anchor a
                    LEFT JOIN inventory_event e
                      ON e.canonical_sku_id = :sku AND e.location_id = :location
                     AND e.kind = 'DELTA' AND e.occurred_at > a.occurred_at
                    GROUP BY a.seq, a.qty_absolute
                )
                INSERT INTO inventory_position (
                    canonical_sku_id, location_id, qty, anchor_seq, last_applied_seq, updated_at
                ) SELECT :sku, :location, qty, anchor_seq, :seq, :updatedAt FROM calculated
                ON CONFLICT (canonical_sku_id, location_id) DO UPDATE SET
                    qty = CASE WHEN EXCLUDED.anchor_seq = :seq
                               THEN EXCLUDED.qty ELSE inventory_position.qty END,
                    anchor_seq = CASE WHEN EXCLUDED.anchor_seq = :seq
                                      THEN EXCLUDED.anchor_seq ELSE inventory_position.anchor_seq END,
                    last_applied_seq = EXCLUDED.last_applied_seq,
                    updated_at = EXCLUDED.updated_at
                RETURNING canonical_sku_id, location_id, qty, anchor_seq, last_applied_seq, updated_at
                """).param("sku", event.canonicalSkuId()).param("location", event.locationId())
                .param("seq", stored.seq()).param("updatedAt", Timestamp.from(stored.receivedAt()))
                .query(this::mapPosition).single();
    }

    private InventoryPosition projectAggregateFromHistory(long sku, long location) {
        InventoryPosition calculated = calculateFromHistory(sku, location);
        upsertPosition(calculated);
        return calculated;
    }

    private InventoryPosition calculateFromHistory(long sku, long location) {
        return jdbc.sql("""
                        WITH anchor AS (
                            SELECT seq, occurred_at, qty_absolute
                            FROM inventory_event
                            WHERE canonical_sku_id = :sku AND location_id = :location AND kind = 'SNAPSHOT'
                            ORDER BY occurred_at DESC, seq DESC
                            LIMIT 1
                        )
                        SELECT :sku AS canonical_sku_id, :location AS location_id,
                               COALESCE((SELECT qty_absolute FROM anchor), 0)
                                 + COALESCE(SUM(e.qty_delta) FILTER (
                                     WHERE e.kind = 'DELTA'
                                       AND ((SELECT occurred_at FROM anchor) IS NULL
                                            OR e.occurred_at > (SELECT occurred_at FROM anchor))
                                   ), 0) AS qty,
                               COALESCE((SELECT seq FROM anchor), 0) AS anchor_seq,
                               MAX(e.seq) AS last_applied_seq,
                               MAX(e.received_at) AS updated_at
                        FROM inventory_event e
                        WHERE e.canonical_sku_id = :sku AND e.location_id = :location
                        """)
                .param("sku", sku)
                .param("location", location)
                .query(this::mapPosition).single();
    }

    private void upsertPosition(InventoryPosition calculated) {
        jdbc.sql("""
                        INSERT INTO inventory_position (
                            canonical_sku_id, location_id, qty, anchor_seq, last_applied_seq, updated_at
                        ) VALUES (:sku, :location, :qty, :anchorSeq, :lastSeq, :updatedAt)
                        ON CONFLICT (canonical_sku_id, location_id) DO UPDATE SET
                            qty = EXCLUDED.qty,
                            anchor_seq = EXCLUDED.anchor_seq,
                            last_applied_seq = EXCLUDED.last_applied_seq,
                            updated_at = EXCLUDED.updated_at
                        """)
                .param("sku", calculated.canonicalSkuId())
                .param("location", calculated.locationId())
                .param("qty", calculated.qty())
                .param("anchorSeq", calculated.anchorSeq())
                .param("lastSeq", calculated.lastAppliedSeq())
                .param("updatedAt", Timestamp.from(calculated.updatedAt()))
                .update();
    }

    private InventoryPosition mapPosition(ResultSet rs, int rowNum) throws SQLException {
        return new InventoryPosition(
                rs.getLong("canonical_sku_id"),
                rs.getLong("location_id"),
                rs.getInt("qty"),
                rs.getLong("anchor_seq"),
                rs.getLong("last_applied_seq"),
                rs.getTimestamp("updated_at").toInstant());
    }

    private InventoryEvent mapEvent(ResultSet rs, int rowNum) throws SQLException {
        try {
            return new InventoryEvent(
                    rs.getLong("seq"),
                    rs.getObject("event_id", java.util.UUID.class),
                    rs.getLong("canonical_sku_id"),
                    rs.getLong("location_id"),
                    rs.getString("source_system"),
                    rs.getString("external_event_id"),
                    InventoryEventType.valueOf(rs.getString("event_type")),
                    EventKind.valueOf(rs.getString("kind")),
                    (Integer) rs.getObject("qty_delta"),
                    (Integer) rs.getObject("qty_absolute"),
                    rs.getTimestamp("occurred_at").toInstant(),
                    rs.getTimestamp("received_at").toInstant(),
                    rs.getBoolean("absorbed"),
                    rs.getObject("causation_id", java.util.UUID.class),
                    objectMapper.readTree(rs.getString("payload")));
        } catch (JsonProcessingException exception) {
            throw new SQLException("Stored event payload is not valid JSON", exception);
        }
    }

    private String toJson(AppendInventoryEvent event) {
        try {
            return objectMapper.writeValueAsString(event.payload());
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Event payload cannot be serialized", exception);
        }
    }

    private void writeOutbox(AppendInventoryEvent event, InventoryPosition position) {
        boolean compensation = Boolean.TRUE.equals(event.payload().get("compensation"));
        try {
            String payload = objectMapper.writeValueAsString(java.util.Map.of(
                    "eventId", event.eventId().toString(),
                    "canonicalSkuId", event.canonicalSkuId(),
                    "locationId", event.locationId(),
                    "sourceSystem", event.sourceSystem(),
                    "targetQty", position.qty(),
                    "compensation", compensation));
            jdbc.sql("""
                    INSERT INTO outbox (id, aggregate_id, topic, payload)
                    VALUES (:id, :aggregateId, 'inventory.position.changed', CAST(:payload AS jsonb))
                    """).param("id", UUID.randomUUID())
                    .param("aggregateId", event.canonicalSkuId() + ":" + event.locationId())
                    .param("payload", payload).update();
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Outbox payload cannot be serialized", exception);
        }
    }

    record AggregateKey(long sku, long location) {
    }

    private record StoredEvent(long seq, Instant receivedAt) { }

    public record ProjectionVerification(
            InventoryPosition actual, InventoryPosition expected, boolean matches) { }
}
