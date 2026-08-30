package io.converge.reconcile;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;

@Component
class DriftMetrics {

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, AtomicInteger> values = new ConcurrentHashMap<>();

    DriftMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void record(String system, int drift) {
        values.computeIfAbsent(system, key -> {
            AtomicInteger value = new AtomicInteger();
            registry.gauge("inventory.drift", java.util.List.of(
                    io.micrometer.core.instrument.Tag.of("system", key)), value);
            return value;
        }).set(drift);
    }
}

