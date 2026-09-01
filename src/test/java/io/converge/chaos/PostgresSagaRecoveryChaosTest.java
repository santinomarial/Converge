package io.converge.chaos;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.DriverManager;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.containers.ToxiproxyContainer.ContainerProxy;
import org.testcontainers.utility.DockerImageName;

import com.github.tomakehurst.wiremock.WireMockServer;

import eu.rekawek.toxiproxy.model.ToxicDirection;

import io.converge.IntegrationTestSupport;
import io.converge.sync.SyncAttemptWorker;
import io.converge.sync.SyncWriteObserver;

@Tag("chaos")
@SpringBootTest(properties = {
        "sync.max-attempts=20",
        "sync.running-lease=1ms",
        "sync.worker-initial-delay=1h",
        "sync.relay-initial-delay=1h",
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.minimum-idle=1",
        "spring.datasource.hikari.connection-timeout=2000",
        "spring.datasource.hikari.validation-timeout=1000"
})
@Import(PostgresSagaRecoveryChaosTest.FaultInjectionConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PostgresSagaRecoveryChaosTest extends IntegrationTestSupport {
    private static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer(
            DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.12.0")
                    .asCompatibleSubstituteFor("shopify/toxiproxy"))
            .withNetwork(NETWORK);
    private static final WireMockServer SHOPIFY = new WireMockServer(0);
    private static final AtomicBoolean CUT_AFTER_WRITE = new AtomicBoolean();
    private static final AtomicBoolean DATABASE_RESET_ACTIVE = new AtomicBoolean();
    private static final ContainerProxy POSTGRES_PROXY;

    static {
        TOXIPROXY.start();
        POSTGRES_PROXY = TOXIPROXY.getProxy(POSTGRES, 5432);
        useDatabaseUrl(() -> "jdbc:postgresql://" + TOXIPROXY.getHost() + ":"
                + POSTGRES_PROXY.getProxyPort() + "/" + POSTGRES.getDatabaseName()
                + "?connectTimeout=2&socketTimeout=2");
        SHOPIFY.start();
    }

    @DynamicPropertySource
    static void faultedDependencies(DynamicPropertyRegistry registry) {
        registry.add("connectors.shopify.base-url", SHOPIFY::baseUrl);
    }

    @AfterAll
    static void stopDependencies() {
        restoreProxy();
        resetDatabaseUrl();
        SHOPIFY.stop();
        TOXIPROXY.stop();
    }

    @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;
    @Autowired SyncAttemptWorker worker;
    private UUID attemptId;

    @BeforeEach
    void seed() throws Exception {
        restoreProxy();
        CUT_AFTER_WRITE.set(false);
        SHOPIFY.resetAll();
        jdbc.sql("""
                TRUNCATE sync_attempt, outbox, reconciliation_exception, drift_sample, external_position,
                         inventory_position, inventory_event, raw_webhook, identity_quarantine,
                         sku_mapping, location_mapping, canonical_sku, location RESTART IDENTITY CASCADE
                """).update();
        jdbc.sql("""
                INSERT INTO canonical_sku (id, sku, style, color, size) VALUES (1, 'P-1', 'P', 'RED', 'M');
                INSERT INTO location (id, code, name, location_type) VALUES (10, 'P-LOC', 'Postgres chaos', 'STORE');
                """).update();
        attemptId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO sync_attempt (id, canonical_sku_id, location_id, target_system,
                    external_sku_id, external_location_id, target_qty)
                VALUES (:id, 1, 10, 'shopify', 'sku-1', 'loc-1', 37)
                """).param("id", attemptId).update();
        SHOPIFY.stubFor(post(urlPathEqualTo("/inventory_levels/set.json"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse().withStatus(200)));
        SHOPIFY.stubFor(get(urlPathEqualTo("/inventory_levels.json"))
                .willReturn(okJson("""
                        {"inventory_levels":[{"inventoryItemId":1,"locationId":1,"available":37}]}
                        """)));
    }

    @Test
    void expiredSagaLeaseRecoversAfterDatabasePartitionWithoutDuplicateExternalWrite() throws Exception {
        CUT_AFTER_WRITE.set(true);
        try {
            try {
                worker.work();
            } catch (RuntimeException expectedPartitionFailure) {
                // The local acknowledgement is expected to fail after the external write commits.
            }
        } finally {
            restoreProxy();
        }

        AttemptSnapshot afterPartition = directSnapshot();
        assertThat(afterPartition.state())
                .as("worker state after partition; attempt=%s, error=%s",
                        afterPartition.attempt(), afterPartition.lastError())
                .isEqualTo("RUNNING");
        SHOPIFY.verify(1, postRequestedFor(urlPathEqualTo("/inventory_levels/set.json")));
        assertThat(CUT_AFTER_WRITE).isFalse();
        makeLeaseExpiredDirectly();

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            worker.work();
            assertThat(directState()).isEqualTo("SUCCEEDED");
        });

        SHOPIFY.verify(1, postRequestedFor(urlPathEqualTo("/inventory_levels/set.json"))
                .withHeader("X-Idempotency-Key", equalTo(attemptId.toString())));
    }

    private String directState() throws Exception {
        return directSnapshot().state();
    }

    private AttemptSnapshot directSnapshot() throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.prepareStatement(
                        "SELECT state, attempt, last_error FROM sync_attempt WHERE id = ?")) {
            statement.setObject(1, attemptId);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return new AttemptSnapshot(result.getString(1), result.getInt(2), result.getString(3));
            }
        }
    }

    private record AttemptSnapshot(String state, int attempt, String lastError) { }

    private void makeLeaseExpiredDirectly() throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.prepareStatement(
                        "UPDATE sync_attempt SET updated_at = now() - interval '1 minute' WHERE id = ?")) {
            statement.setObject(1, attemptId);
            statement.executeUpdate();
        }
    }

    @TestConfiguration
    static class FaultInjectionConfiguration {
        @Bean
        @Primary
        SyncWriteObserver partitionAfterExternalWrite() {
            return attempt -> {
                if (CUT_AFTER_WRITE.compareAndSet(true, false)) {
                    try {
                        POSTGRES_PROXY.toxics().resetPeer("CUT_DATABASE_UPSTREAM", ToxicDirection.UPSTREAM, 0);
                        DATABASE_RESET_ACTIVE.set(true);
                        Thread.sleep(250);
                    } catch (Exception exception) {
                        throw new IllegalStateException("Could not cut PostgreSQL proxy", exception);
                    }
                }
            };
        }
    }

    private static void restoreProxy() {
        if (DATABASE_RESET_ACTIVE.compareAndSet(true, false)) {
            try {
                POSTGRES_PROXY.toxics().get("CUT_DATABASE_UPSTREAM").remove();
            } catch (java.io.IOException exception) {
                throw new IllegalStateException("Could not restore PostgreSQL proxy", exception);
            }
        }
    }
}
