package io.converge.api;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/operations")
public class OperationsController {
    private final JdbcTemplate jdbc;

    public OperationsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/positions")
    public List<PositionView> positions() {
        return jdbc.query("""
                SELECT p.canonical_sku_id, p.location_id, s.sku, l.name AS location,
                       p.qty AS ledger, p.updated_at,
                       MAX(e.qty) FILTER (WHERE lower(e.system) = 'shopify') AS shopify,
                       MAX(e.qty) FILTER (WHERE lower(e.system) = 'square') AS square,
                       MAX(e.qty) FILTER (WHERE lower(e.system) = 'warehouse') AS warehouse
                FROM inventory_position p
                JOIN canonical_sku s ON s.id = p.canonical_sku_id
                JOIN location l ON l.id = p.location_id
                LEFT JOIN external_position e ON e.canonical_sku_id = p.canonical_sku_id
                    AND e.location_id = p.location_id
                GROUP BY p.canonical_sku_id, p.location_id, s.sku, l.name, p.qty, p.updated_at
                ORDER BY s.sku, l.name
                """, (rs, row) -> new PositionView(
                rs.getLong("canonical_sku_id"), rs.getLong("location_id"),
                rs.getString("sku"), rs.getString("location"), rs.getInt("ledger"),
                (Integer) rs.getObject("shopify"), (Integer) rs.getObject("square"),
                (Integer) rs.getObject("warehouse"),
                rs.getObject("updated_at", Timestamp.class).toInstant()));
    }

    @GetMapping("/summary")
    public Summary summary() {
        return jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM inventory_event
                            WHERE received_at >= (date_trunc('day', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC')) AS events_today,
                       (SELECT count(DISTINCT location_id) FROM inventory_position) AS locations
                """, (rs, row) -> new Summary(rs.getLong("events_today"), rs.getLong("locations")));
    }

    public record PositionView(long canonicalSkuId, long locationId, String sku, String location,
            int ledger, Integer shopify, Integer square, Integer warehouse, Instant updatedAt) { }
    public record Summary(long eventsToday, long locations) { }
}
