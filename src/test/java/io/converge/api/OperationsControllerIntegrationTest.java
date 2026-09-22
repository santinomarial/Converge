package io.converge.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.converge.IntegrationTestSupport;

@SpringBootTest
class OperationsControllerIntegrationTest extends IntegrationTestSupport {
    @Autowired OperationsController operations;
    @Autowired ConnectorHealthController connectors;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void seed() {
        jdbc.sql("""
                TRUNCATE sync_attempt, reconciliation_exception, drift_sample, external_position,
                         inventory_position, inventory_event, raw_webhook, identity_quarantine,
                         sku_mapping, location_mapping, canonical_sku, location RESTART IDENTITY CASCADE
                """).update();
        jdbc.sql("""
                INSERT INTO canonical_sku (id, sku, style, color, size)
                VALUES (1, 'SKU-REAL', 'SHIRT', 'BLUE', 'M');
                INSERT INTO location (id, code, name, location_type)
                VALUES (10, 'STORE-1', 'Main store', 'STORE');
                INSERT INTO inventory_position (canonical_sku_id, location_id, qty, anchor_seq, last_applied_seq, updated_at)
                VALUES (1, 10, 42, 0, 0, now());
                INSERT INTO external_position (canonical_sku_id, location_id, system, qty, observed_at)
                VALUES (1, 10, 'shopify', 40, now()), (1, 10, 'square', 42, now());
                INSERT INTO sync_attempt (id, canonical_sku_id, location_id, target_system,
                                          external_sku_id, external_location_id, target_qty, state)
                VALUES (gen_random_uuid(), 1, 10, 'shopify', 'external-sku', 'external-location', 42, 'QUEUED');
                """).update();
    }

    @Test
    void exposesRealNamesObservedQuantitiesAndPendingWork() {
        assertThat(operations.positions()).singleElement().satisfies(position -> {
            assertThat(position.sku()).isEqualTo("SKU-REAL");
            assertThat(position.location()).isEqualTo("Main store");
            assertThat(position.ledger()).isEqualTo(42);
            assertThat(position.shopify()).isEqualTo(40);
            assertThat(position.square()).isEqualTo(42);
            assertThat(position.warehouse()).isNull();
        });
        assertThat(operations.summary().locations()).isEqualTo(1);
        assertThat(connectors.health()).filteredOn(health -> health.system().equals("shopify"))
                .singleElement().satisfies(health -> {
                    assertThat(health.lag()).isEqualTo(1);
                    assertThat(health.status()).isEqualTo("UNVERIFIED");
                    assertThat(health.lastSync()).isNull();
                });
    }
}
