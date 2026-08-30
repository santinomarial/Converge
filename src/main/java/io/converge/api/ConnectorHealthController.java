package io.converge.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.converge.connectors.InventorySource;

@RestController
@RequestMapping("/api/connectors")
public class ConnectorHealthController {
    private final List<InventorySource> sources;
    public ConnectorHealthController(List<InventorySource> sources) { this.sources = sources; }

    @GetMapping("/health")
    public List<ConnectorHealth> health() {
        return sources.stream().map(source -> new ConnectorHealth(source.system(), "UP", "CLOSED", 0)).toList();
    }

    public record ConnectorHealth(String system, String status, String breakerState, long lag) { }
}

