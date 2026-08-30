package io.converge;

import java.time.Duration;

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

    static {
        POSTGRES.start();
        REDPANDA.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", REDPANDA::getBootstrapServers);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
