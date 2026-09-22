package io.converge.api;

import java.net.URI;
import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

@Configuration
@Profile("prod")
public class ProductionConfiguration {
    public ProductionConfiguration(Environment environment) {
        for (String key : List.of("DATABASE_URL", "DATABASE_USERNAME", "DATABASE_PASSWORD",
                "KAFKA_BOOTSTRAP_SERVERS", "REDIS_HOST", "SHOPIFY_BASE_URL", "SHOPIFY_ACCESS_TOKEN",
                "SHOPIFY_WEBHOOK_SECRET", "SQUARE_BASE_URL", "SQUARE_ACCESS_TOKEN",
                "SQUARE_WEBHOOK_SIGNATURE_KEY", "SQUARE_NOTIFICATION_URL", "CONSOLE_USERNAME",
                "CONSOLE_PASSWORD")) {
            String value = environment.getProperty(key);
            if (value == null || value.isBlank() || "...".equals(value) || value.startsWith("development-")) {
                throw new IllegalStateException("Production configuration requires " + key);
            }
        }
        for (String key : List.of("SHOPIFY_BASE_URL", "SQUARE_BASE_URL", "SQUARE_NOTIFICATION_URL")) {
            URI uri = URI.create(environment.getRequiredProperty(key));
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw new IllegalStateException(key + " must be an HTTPS URL in production");
            }
        }
        for (String key : List.of("DATABASE_URL", "KAFKA_BOOTSTRAP_SERVERS", "REDIS_HOST")) {
            String value = environment.getRequiredProperty(key).toLowerCase();
            if (value.contains("localhost") || value.contains("127.0.0.1")) {
                throw new IllegalStateException(key + " must not point to localhost in production");
            }
        }
        if (!environment.getRequiredProperty("DATABASE_URL").startsWith("jdbc:postgresql:")) {
            throw new IllegalStateException("DATABASE_URL must use PostgreSQL");
        }
        String protocol = environment.getProperty("KAFKA_SECURITY_PROTOCOL", "PLAINTEXT");
        if (protocol.startsWith("SASL_")) {
            for (String key : List.of("KAFKA_SASL_MECHANISM", "KAFKA_SASL_JAAS_CONFIG")) {
                String value = environment.getProperty(key);
                if (value == null || value.isBlank()) {
                    throw new IllegalStateException(protocol + " requires " + key);
                }
            }
        }
        if (environment.getRequiredProperty("CONSOLE_PASSWORD").length() < 20) {
            throw new IllegalStateException("CONSOLE_PASSWORD must be at least 20 characters");
        }
    }
}
