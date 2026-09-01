package io.converge.chaos;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;

import com.github.tomakehurst.wiremock.WireMockServer;

import io.converge.IntegrationTestSupport;
import io.converge.sync.SyncAttemptWorker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

@Tag("chaos")
@SpringBootTest(properties = {
        "sync.max-attempts=20",
        "sync.worker-initial-delay=1h",
        "sync.relay-initial-delay=1h"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ShopifyRateLimitRecoveryChaosTest extends IntegrationTestSupport {
    private static final WireMockServer SHOPIFY = new WireMockServer(0);

    static { SHOPIFY.start(); }

    @DynamicPropertySource
    static void shopifyProperties(DynamicPropertyRegistry registry) {
        registry.add("connectors.shopify.base-url", SHOPIFY::baseUrl);
    }

    @AfterAll
    static void stopShopify() {
        SHOPIFY.stop();
    }

    @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;
    @Autowired SyncAttemptWorker worker;
    @Autowired CircuitBreakerRegistry breakers;
    private UUID attemptId;

    @BeforeEach
    void seed() {
        SHOPIFY.resetAll();
        breakers.circuitBreaker("sync-shopify").reset();
        jdbc.sql("""
                TRUNCATE sync_attempt, outbox, reconciliation_exception, drift_sample, external_position,
                         inventory_position, inventory_event, raw_webhook, identity_quarantine,
                         sku_mapping, location_mapping, canonical_sku, location RESTART IDENTITY CASCADE
                """).update();
        jdbc.sql("""
                INSERT INTO canonical_sku (id, sku, style, color, size) VALUES (1, 'R-1', 'R', 'RED', 'M');
                INSERT INTO location (id, code, name, location_type) VALUES (10, 'R-LOC', 'Rate limit', 'STORE');
                """).update();
        attemptId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO sync_attempt (id, canonical_sku_id, location_id, target_system,
                    external_sku_id, external_location_id, target_qty)
                VALUES (:id, 1, 10, 'shopify', 'sku-1', 'loc-1', 37)
                """).param("id", attemptId).update();
        SHOPIFY.stubFor(get(urlPathEqualTo("/inventory_levels.json"))
                .willReturn(okJson("""
                        {"inventory_levels":[{"inventoryItemId":1,"locationId":1,"available":0}]}
                        """)));
        SHOPIFY.stubFor(post(urlPathEqualTo("/inventory_levels/set.json"))
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "60")));
    }

    @Test
    void breakerQueuesWritesForSixtySecondsAndDrainsAfterShopifyRecovers() throws Exception {
        long limitedAt = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            makeReady();
            worker.work();
        }

        CircuitBreaker breaker = breakers.circuitBreaker("sync-shopify");
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(state()).isEqualTo("QUEUED");

        Duration remaining = Duration.ofSeconds(60).minusNanos(System.nanoTime() - limitedAt);
        if (!remaining.isNegative()) {
            Thread.sleep(remaining);
        }
        assertThat(state()).isEqualTo("QUEUED");

        SHOPIFY.stubFor(post(urlPathEqualTo("/inventory_levels/set.json"))
                .willReturn(aResponse().withStatus(200)));
        breaker.transitionToHalfOpenState();
        makeReady();
        worker.work();

        assertThat(state()).isEqualTo("SUCCEEDED");
        SHOPIFY.verify(6, postRequestedFor(urlPathEqualTo("/inventory_levels/set.json"))
                .withHeader("X-Idempotency-Key", equalTo(attemptId.toString())));
        SHOPIFY.verify(5, getRequestedFor(urlPathEqualTo("/inventory_levels.json")));
    }

    private void makeReady() {
        jdbc.sql("UPDATE sync_attempt SET next_attempt_at = now() WHERE id = :id")
                .param("id", attemptId).update();
    }

    private String state() {
        return jdbc.sql("SELECT state FROM sync_attempt WHERE id = :id")
                .param("id", attemptId).query(String.class).single();
    }
}
