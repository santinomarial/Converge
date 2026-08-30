package io.converge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
class InfrastructureIntegrationTest extends IntegrationTestSupport {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    RedisConnectionFactory redis;

    @Test
    void bootsAgainstRealInfrastructureAndRunsFlyway() throws Exception {
        assertThat(jdbc.sql("SELECT value FROM app_metadata WHERE name = 'schema'")
                .query(String.class)
                .single()).isEqualTo("1");

        try (var connection = redis.getConnection()) {
            assertThat(connection.ping()).isEqualTo("PONG");
        }

        try (var admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, REDPANDA.getBootstrapServers()))) {
            assertThat(admin.listTopics().names().get(10, TimeUnit.SECONDS)).isEmpty();
        }
    }
}
