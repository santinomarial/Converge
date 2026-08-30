package io.converge.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.converge.IntegrationTestSupport;
import io.converge.ingest.RawWebhookService;

@Tag("chaos")
@SpringBootTest
class ConcurrentDuplicateWebhookChaosTest extends IntegrationTestSupport {
    @Autowired RawWebhookService webhooks;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void seed() {
        jdbc.sql("""
                TRUNCATE sync_attempt, outbox, reconciliation_exception, drift_sample, external_position,
                         inventory_position, inventory_event, raw_webhook, identity_quarantine,
                         sku_mapping, location_mapping, canonical_sku, location RESTART IDENTITY CASCADE
                """).update();
        jdbc.sql("""
                INSERT INTO canonical_sku (id, sku, style, color, size) VALUES (1, 'C-1', 'C', 'RED', 'M');
                INSERT INTO location (id, code, name, location_type) VALUES (10, 'C-LOC', 'Chaos', 'STORE');
                INSERT INTO sku_mapping (canonical_sku_id, system, external_id) VALUES (1, 'shopify', '42');
                INSERT INTO location_mapping (location_id, system, external_id) VALUES (10, 'shopify', '7');
                """).update();
    }

    @Test
    void fiftyConcurrentDuplicatesProduceExactlyOneEvent() throws Exception {
        byte[] payload = """
                {"inventory_item_id":42,"location_id":7,"available":23,
                 "updated_at":"2026-08-30T12:00:00Z"}
                """.getBytes(StandardCharsets.UTF_8);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 50; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    webhooks.captureShopify("chaos-duplicate", "inventory_levels/update", payload);
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) future.get();
        }

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(jdbc.sql("SELECT count(*) FROM raw_webhook").query(Long.class).single()).isOne();
            assertThat(jdbc.sql("SELECT count(*) FROM inventory_event").query(Long.class).single()).isOne();
            assertThat(jdbc.sql("SELECT qty FROM inventory_position").query(Integer.class).single()).isEqualTo(23);
        });
    }
}

