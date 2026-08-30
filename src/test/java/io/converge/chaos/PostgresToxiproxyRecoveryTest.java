package io.converge.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.containers.ToxiproxyContainer.ContainerProxy;
import org.testcontainers.utility.DockerImageName;

import io.converge.IntegrationTestSupport;

@Tag("chaos")
class PostgresToxiproxyRecoveryTest extends IntegrationTestSupport {
    static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer(
            DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.12.0")
                    .asCompatibleSubstituteFor("shopify/toxiproxy"))
            .withNetwork(NETWORK);

    static {
        TOXIPROXY.start();
    }

    @Test
    void durableDatabaseStateSurvivesAConnectionPartitionAndIsReadableAfterRecovery() throws Exception {
        try (var direct = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = direct.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS chaos_probe (id integer PRIMARY KEY)");
            statement.execute("INSERT INTO chaos_probe (id) VALUES (1) ON CONFLICT DO NOTHING");
        }
        ContainerProxy proxy = TOXIPROXY.getProxy(POSTGRES, 5432);
        String url = "jdbc:postgresql://" + TOXIPROXY.getHost() + ":" + proxy.getProxyPort()
                + "/" + POSTGRES.getDatabaseName() + "?connectTimeout=2&socketTimeout=2";

        try (var connection = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT count(*) FROM chaos_probe")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getLong(1)).isPositive();
        }

        proxy.setConnectionCut(true);
        try {
            assertThatThrownBy(() -> {
                try (var connection = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
                        var statement = connection.createStatement()) {
                    statement.executeQuery("SELECT 1");
                }
            }).isInstanceOf(java.sql.SQLException.class);
        } finally {
            proxy.setConnectionCut(false);
        }

        try (var connection = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT count(*) FROM chaos_probe")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getLong(1)).isPositive();
        }
    }
}
