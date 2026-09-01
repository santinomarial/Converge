package io.converge;

import java.time.Duration;
import java.util.function.Supplier;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;

public abstract class IntegrationTestSupport {

    protected static final Network NETWORK = Network.newNetwork();
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.10-alpine")
            .withNetwork(NETWORK).withNetworkAliases("postgres");
    protected static final RedpandaContainer REDPANDA = new RedpandaContainer(
            "docker.redpanda.com/redpandadata/redpanda:v24.3.18")
            .withNetwork(NETWORK).withNetworkAliases("redpanda");
    protected static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.5-alpine")
            .withNetwork(NETWORK).withNetworkAliases("redis")
            .withExposedPorts(6379)
            .withStartupTimeout(Duration.ofMinutes(2));
    private static volatile Supplier<String> databaseUrl = POSTGRES::getJdbcUrl;

    static {
        POSTGRES.start();
        REDPANDA.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> databaseUrl.get());
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", REDPANDA::getBootstrapServers);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        // Tests invoke workers explicitly; cached Spring contexts must not wake and mutate the shared database.
        registry.add("sync.worker-initial-delay", () -> "1h");
        registry.add("sync.relay-initial-delay", () -> "1h");
        registry.add("reconciliation.initial-delay", () -> "1h");
        registry.add("ledger.shadow-verification-initial-delay", () -> "1h");
    }

    protected static void useDatabaseUrl(Supplier<String> url) {
        databaseUrl = url;
    }

    protected static void resetDatabaseUrl() {
        databaseUrl = POSTGRES::getJdbcUrl;
    }
}
