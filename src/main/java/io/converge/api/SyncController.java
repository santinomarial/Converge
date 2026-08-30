package io.converge.api;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.converge.sync.SyncPlanner;

@RestController
@RequestMapping("/api/sync")
public class SyncController {
    private final SyncPlanner planner;
    public SyncController(SyncPlanner planner) { this.planner = planner; }

    @PostMapping("/{sku}/{location}")
    public SyncResponse force(@PathVariable long sku, @PathVariable long location) {
        return new SyncResponse(planner.planManual(sku, location));
    }

    public record SyncResponse(int targetsQueued) { }
}

