package io.converge.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import io.converge.IntegrationTestSupport;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("prod")
@TestPropertySource(properties = {
        "DATABASE_URL=jdbc:postgresql://db.internal:5432/converge",
        "DATABASE_USERNAME=converge", "DATABASE_PASSWORD=secret",
        "KAFKA_BOOTSTRAP_SERVERS=broker.internal:9092", "REDIS_HOST=redis.internal",
        "SHOPIFY_BASE_URL=https://shop.myshopify.com", "SHOPIFY_ACCESS_TOKEN=token",
        "SHOPIFY_WEBHOOK_SECRET=secret", "SQUARE_BASE_URL=https://connect.squareup.com",
        "SQUARE_ACCESS_TOKEN=token", "SQUARE_WEBHOOK_SIGNATURE_KEY=secret",
        "SQUARE_NOTIFICATION_URL=https://app.fly.dev/webhooks/square",
        "CONSOLE_USERNAME=operator", "CONSOLE_PASSWORD=a-unique-twenty-character-password"
})
class ProductionSecurityIntegrationTest extends IntegrationTestSupport {
    @Autowired MockMvc mvc;

    @Test
    void protectsOperationsApiAndAllowsHealthProbe() throws Exception {
        mvc.perform(get("/api/operations/summary")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/replay")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/operations/summary").header("Authorization", basic("operator", "wrong")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/operations/summary").header("Authorization", basic("operator", "a-unique-twenty-character-password")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/admin/replay").header("Authorization", basic("operator", "a-unique-twenty-character-password")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/replay").header("Authorization", basic("operator", "a-unique-twenty-character-password"))
                .header("X-Converge-Request", "console")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
