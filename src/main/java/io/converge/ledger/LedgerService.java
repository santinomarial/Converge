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
        lockAggregate(event.canonicalSkuId(), event.locationId());
        boolean absorbed = event.kind() == EventKind.DELTA && isBehindCurrentAnchor(event);
        Optional<Long> insertedSeq = jdbc.sql("""
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
                        RETURNING seq
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
                .query(Long.class)
                .optional();

        if (insertedSeq.isEmpty()) {
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

        InventoryPosition position = projectAggregate(event.canonicalSkuId(), event.locationId());
        writeOutbox(event, position);
        return new AppendResult(insertedSeq.get(), true, position);
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
                        WHERE (:sku IS NULL OR canonical_sku_id = :sku)
                          AND (:location IS NULL OR location_id = :location)
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
        aggregates.forEach(key -> projectAggregate(key.sku(), key.location()));
        return aggregates.size();
    }

    private void lockAggregate(long sku, long location) {
        jdbc.sql("SELECT pg_advisory_xact_lock(:sku, :location)")
                .param("sku", Math.toIntExact(sku))
                .param("location", Math.toIntExact(location))
                .query(resultSet -> {
                    resultSet.next();
                    return true;
                });
    }

    private boolean isBehindCurrentAnchor(AppendInventoryEvent event) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM inventory_event
                            WHERE canonical_sku_id = :sku AND location_id = :location
                              AND kind = 'SNAPSHOT' AND occurred_at >= :occurredAt
                        )
                        """)
                .param("sku", event.canonicalSkuId())
                .param("location", event.locationId())
                .param("occurredAt", Timestamp.from(event.occurredAt()))
                .query(Boolean.class)
                .single();
    }

    private InventoryPosition projectAggregate(long sku, long location) {
        InventoryPosition calculated = jdbc.sql("""
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
                               now() AS updated_at
                        FROM inventory_event e
                        WHERE e.canonical_sku_id = :sku AND e.location_id = :location
                        """)
                .param("sku", sku)
                .param("location", location)
                .query(this::mapPosition)
                .single();

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
        return calculated;
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

    private record AggregateKey(long sku, long location) {
    }
}
