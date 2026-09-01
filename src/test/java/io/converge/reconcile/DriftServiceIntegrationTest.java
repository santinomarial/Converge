package io.converge.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.converge.IntegrationTestSupport;

@SpringBootTest
class DriftServiceIntegrationTest extends IntegrationTestSupport {
    @Autowired DriftService drift;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void seedPosition() {
        jdbc.sql("""
                TRUNCATE reconciliation_exception, drift_sample, external_position,
                         inventory_position, inventory_event, raw_webhook, identity_quarantine,
                         sku_mapping, location_mapping, canonical_sku, location RESTART IDENTITY CASCADE
                """).update();
        jdbc.sql("""
                INSERT INTO canonical_sku (id, sku, style, color, size) VALUES (1, 'D-1', 'D', 'RED', 'M');
                INSERT INTO location (id, code, name, location_type) VALUES (10, 'D-LOC', 'Drift', 'STORE');
                INSERT INTO inventory_position (canonical_sku_id, location_id, qty, anchor_seq, last_applied_seq, updated_at)
                VALUES (1, 10, 100, 0, 0, now());
                """).update();
    }

    @Test
    void opensOneExceptionOnlyAfterTwoConsecutiveDriftCycles() {
        var first = drift.observe(1, 10, "shopify", 96, Instant.now());
        assertThat(first.drift()).isEqualTo(-4);
        assertThat(first.exceptionOpened()).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM reconciliation_exception").query(Long.class).single()).isZero();

        var second = drift.observe(1, 10, "shopify", 96, Instant.now());
        var third = drift.observe(1, 10, "shopify", 95, Instant.now());
        assertThat(second.exceptionOpened()).isTrue();
        assertThat(third.consecutiveCycles()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT count(*) FROM reconciliation_exception").query(Long.class).single()).isOne();
        assertThat(drift.samples("shopify", java.time.Duration.ofHours(1))).hasSize(3);
        assertThat(drift.samples(null, java.time.Duration.ofHours(1))).hasSize(3);
    }

    @Test
    void zeroDriftResetsPersistenceCounter() {
        drift.observe(1, 10, "square", 90, Instant.now());
        var zero = drift.observe(1, 10, "square", 100, Instant.now());
        var next = drift.observe(1, 10, "square", 90, Instant.now());
        assertThat(zero.consecutiveCycles()).isZero();
        assertThat(next.consecutiveCycles()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM reconciliation_exception").query(Long.class).single()).isZero();
    }
}
