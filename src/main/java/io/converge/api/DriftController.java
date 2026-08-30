package io.converge.api;

import java.time.Duration;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.converge.reconcile.DriftSample;
import io.converge.reconcile.DriftService;

@RestController
@RequestMapping("/api/drift")
public class DriftController {
    private final DriftService drift;

    public DriftController(DriftService drift) { this.drift = drift; }

    @GetMapping
    public List<DriftSample> samples(
            @RequestParam(required = false) String system,
            @RequestParam(defaultValue = "PT24H") Duration window) {
        return drift.samples(system, window);
    }
}

