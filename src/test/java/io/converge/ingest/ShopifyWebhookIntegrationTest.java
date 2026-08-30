package io.converge.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import io.converge.IntegrationTestSupport;

@SpringBootTest
@AutoConfigureMockMvc
class ShopifyWebhookIntegrationTest extends IntegrationTestSupport {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void seedMappings() {
        jdbc.sql("""
                TRUNCATE inventory_position, inventory_event, raw_webhook, identity_quarantine,
                         sku_mapping, location_mapping, canonical_sku, location
                         RESTART IDENTITY CASCADE
                """).update();
        jdbc.sql("""
                INSERT INTO canonical_sku (id, sku, style, color, size)
                VALUES (1, 'SHOP-1', 'SHOP', 'RED', 'M');
                INSERT INTO location (id, code, name, location_type)
                VALUES (10, 'ONLINE', 'Online', 'ONLINE');
                INSERT INTO sku_mapping (canonical_sku_id, system, external_id)
                VALUES (1, 'shopify', '42');
                INSERT INTO location_mapping (location_id, system, external_id)
                VALUES (10, 'shopify', '7');
                """).update();
    }

    @Test
    void rejectsBeforeCaptureWhenSignatureIsInvalid() throws Exception {
        byte[] body = "not-json".getBytes(StandardCharsets.UTF_8);
        mvc.perform(post("/webhooks/shopify")
                        .header("X-Shopify-Hmac-Sha256", "invalid")
                        .header("X-Shopify-Webhook-Id", "bad-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());

        assertThat(jdbc.sql("SELECT count(*) FROM raw_webhook").query(Long.class).single()).isZero();
    }

    @Test
    void capturesAcknowledgesAndNormalizesIdempotently() throws Exception {
        byte[] body = """
                {"inventory_item_id":42,"location_id":7,"available":27,
                 "updated_at":"2026-08-30T12:00:00Z"}
                """.getBytes(StandardCharsets.UTF_8);
        String signature = sign(body);

        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/webhooks/shopify")
                            .header("X-Shopify-Hmac-Sha256", signature)
                            .header("X-Shopify-Webhook-Id", "webhook-42")
                            .header("X-Shopify-Topic", "inventory_levels/update")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());
        }

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(jdbc.sql("SELECT state FROM raw_webhook")
                    .query(String.class).single()).isEqualTo("PROCESSED");
            assertThat(jdbc.sql("SELECT qty FROM inventory_position WHERE canonical_sku_id = 1 AND location_id = 10")
                    .query(Integer.class).single()).isEqualTo(27);
            assertThat(jdbc.sql("SELECT count(*) FROM inventory_event")
                    .query(Long.class).single()).isOne();
        });
    }

    private String sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("development-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(body));
    }
}

