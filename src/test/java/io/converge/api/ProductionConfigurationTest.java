package io.converge.api;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionConfigurationTest {
    private MockEnvironment validEnvironment() {
        return new MockEnvironment()
                .withProperty("DATABASE_URL", "jdbc:postgresql://db.internal:5432/converge")
                .withProperty("DATABASE_USERNAME", "converge")
                .withProperty("DATABASE_PASSWORD", "secret")
                .withProperty("KAFKA_BOOTSTRAP_SERVERS", "broker.internal:9092")
                .withProperty("REDIS_HOST", "redis.internal")
                .withProperty("SHOPIFY_BASE_URL", "https://shop.myshopify.com")
                .withProperty("SHOPIFY_ACCESS_TOKEN", "token")
                .withProperty("SHOPIFY_WEBHOOK_SECRET", "secret")
                .withProperty("SQUARE_BASE_URL", "https://connect.squareup.com")
                .withProperty("SQUARE_ACCESS_TOKEN", "token")
                .withProperty("SQUARE_WEBHOOK_SIGNATURE_KEY", "secret")
                .withProperty("SQUARE_NOTIFICATION_URL", "https://app.fly.dev/webhooks/square")
                .withProperty("CONSOLE_USERNAME", "operator")
                .withProperty("CONSOLE_PASSWORD", "a-unique-twenty-character-password");
    }

    @Test
    void acceptsCompleteRemoteConfiguration() {
        new ProductionConfiguration(validEnvironment());
    }

    @Test
    void rejectsMissingSecretAndLocalInfrastructure() {
        assertThatThrownBy(() -> new ProductionConfiguration(validEnvironment().withProperty("CONSOLE_PASSWORD", "short")))
                .hasMessageContaining("CONSOLE_PASSWORD");
        assertThatThrownBy(() -> new ProductionConfiguration(validEnvironment().withProperty("REDIS_HOST", "localhost")))
                .hasMessageContaining("REDIS_HOST");
    }

    @Test
    void rejectsInsecureConnectorUrl() {
        assertThatThrownBy(() -> new ProductionConfiguration(validEnvironment().withProperty("SQUARE_NOTIFICATION_URL", "http://app.fly.dev/webhooks/square")))
                .hasMessageContaining("SQUARE_NOTIFICATION_URL");
    }

    @Test
    void requiresSaslCredentialsWhenKafkaUsesSasl() {
        assertThatThrownBy(() -> new ProductionConfiguration(validEnvironment()
                .withProperty("KAFKA_SECURITY_PROTOCOL", "SASL_SSL")))
                .hasMessageContaining("KAFKA_SASL_MECHANISM");
    }
}
