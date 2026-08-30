package io.converge.sync;

import org.springframework.stereotype.Service;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

@Service
public class SyncHealthService {
    private final CircuitBreakerRegistry breakers;
    public SyncHealthService(CircuitBreakerRegistry breakers) { this.breakers = breakers; }
    public String breakerState(String system) {
        return breakers.circuitBreaker("sync-" + system).getState().name();
    }
}

