package io.converge.api;

import java.util.List;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.converge.connectors.InventorySource;
import io.converge.sync.SyncHealthService;

@RestController
@RequestMapping("/api/connectors")
public class ConnectorHealthController {
    private final List<InventorySource> sources;
    private final SyncHealthService syncHealth;
    private final JdbcTemplate jdbc;
    public ConnectorHealthController(List<InventorySource> sources, SyncHealthService syncHealth, JdbcTemplate jdbc) {
        this.sources = sources;
        this.syncHealth = syncHealth;
        this.jdbc = jdbc;
    }

    @GetMapping("/health")
    public List<ConnectorHealth> health() {
        return sources.stream().map(source -> {
            String breaker = syncHealth.breakerState(source.system());
            return jdbc.queryForObject("""
                    SELECT count(*) FILTER (WHERE state IN ('QUEUED', 'RUNNING')) AS lag,
                           max(updated_at) FILTER (WHERE state = 'SUCCEEDED') AS last_sync
                    FROM sync_attempt WHERE target_system = ?
                    """, (rs, row) -> {
                        var lastSync = rs.getTimestamp("last_sync");
                        return new ConnectorHealth(source.system(),
                                "OPEN".equals(breaker) ? "DEGRADED" : lastSync == null ? "UNVERIFIED" : "READY",
                                breaker, rs.getLong("lag"), lastSync == null ? null : lastSync.toInstant());
                    }, source.system());
        }).toList();
    }

    public record ConnectorHealth(String system, String status, String breakerState, long lag, Instant lastSync) { }
}
