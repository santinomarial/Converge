package io.converge.reconcile;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.converge.connectors.InventorySource;

@Component
public class DriftPoller {
    private static final Logger LOGGER = LoggerFactory.getLogger(DriftPoller.class);

    private final JdbcClient jdbc;
    private final DriftService drift;
    private final Map<String, InventorySource> sources;

    public DriftPoller(JdbcClient jdbc, DriftService drift, List<InventorySource> sources) {
        this.jdbc = jdbc;
        this.drift = drift;
        this.sources = sources.stream().collect(
                Collectors.toUnmodifiableMap(InventorySource::system, Function.identity()));
    }

    @Scheduled(fixedDelayString = "${reconciliation.poll-delay}",
            initialDelayString = "${reconciliation.initial-delay}")
    public void poll() {
        for (Mapping mapping : mappings()) {
            InventorySource source = sources.get(mapping.system());
            if (source == null) continue;
            try {
                var observed = source.fetchPosition(mapping.externalSku(), mapping.externalLocation());
                drift.observe(mapping.sku(), mapping.location(), mapping.system(), observed.qty(), observed.observedAt());
            } catch (RuntimeException exception) {
                LOGGER.warn("Inventory poll failed for {} sku={} location={}",
                        mapping.system(), mapping.sku(), mapping.location(), exception);
            }
        }
    }

    private List<Mapping> mappings() {
        return jdbc.sql("""
                SELECT p.canonical_sku_id, p.location_id, sm.system,
                       sm.external_id AS external_sku, lm.external_id AS external_location
                FROM inventory_position p
                JOIN sku_mapping sm ON sm.canonical_sku_id = p.canonical_sku_id
                JOIN location_mapping lm ON lm.location_id = p.location_id AND lm.system = sm.system
                """).query(this::mapMapping).list();
    }

    private Mapping mapMapping(ResultSet rs, int row) throws SQLException {
        return new Mapping(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5));
    }

    private record Mapping(long sku, long location, String system, String externalSku, String externalLocation) { }
}

