package io.converge.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
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
class KafkaPartitionRecoveryChaosTest extends IntegrationTestSupport {
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
                INSERT INTO canonical_sku (id, sku, style, color, size) VALUES (1, 'K-1', 'K', 'RED', 'M');
                INSERT INTO location (id, code, name, location_type) VALUES (10, 'K-LOC', 'Kafka chaos', 'STORE');
                INSERT INTO sku_mapping (canonical_sku_id, system, external_id) VALUES (1, 'shopify', '42');
                INSERT INTO location_mapping (location_id, system, external_id) VALUES (10, 'shopify', '7');
                """).update();
    }

    @Test
    void consumerResumesFromCommittedOffsetsWithoutLossAfterBrokerPartition() throws Exception {
        capture("warm-up", 49);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(eventCount()).isOne());

        String containerId = REDPANDA.getContainerId();
        REDPANDA.getDockerClient().pauseContainerCmd(containerId).exec();
        try {
            for (int i = 0; i < 100; i++) {
                capture("partitioned-" + i, 50);
            }
            assertThat(jdbc.sql("SELECT count(*) FROM raw_webhook").query(Long.class).single()).isEqualTo(101);
        } finally {
            REDPANDA.getDockerClient().unpauseContainerCmd(containerId).exec();
        }

        await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> {
            assertThat(eventCount()).isEqualTo(101);
            assertThat(jdbc.sql("SELECT count(*) FROM raw_webhook WHERE state = 'FAILED'")
                    .query(Long.class).single()).isZero();
            assertThat(jdbc.sql("SELECT qty FROM inventory_position WHERE canonical_sku_id = 1 AND location_id = 10")
                    .query(Integer.class).single()).isEqualTo(50);
        });

        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, REDPANDA.getBootstrapServers()))) {
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                long committed = admin.listConsumerGroupOffsets("converge-normalizer")
                        .partitionsToOffsetAndMetadata().get().values().stream()
                        .mapToLong(offset -> offset.offset()).sum();
                assertThat(committed).isGreaterThanOrEqualTo(101);
            });
        }
    }

    private void capture(String id, int available) {
        webhooks.captureShopify(id, "inventory_levels/update", ("""
                {"inventory_item_id":42,"location_id":7,"available":%d,
                 "updated_at":"2026-08-31T12:00:00Z"}
                """).formatted(available).getBytes(StandardCharsets.UTF_8));
    }

    private long eventCount() {
        return jdbc.sql("SELECT count(*) FROM inventory_event").query(Long.class).single();
    }
}
