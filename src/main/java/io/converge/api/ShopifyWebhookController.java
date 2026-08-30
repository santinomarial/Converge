package io.converge.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.converge.ingest.RawWebhookService;
import io.converge.ingest.ShopifyHmacVerifier;

@RestController
@RequestMapping("/webhooks")
public class ShopifyWebhookController {

    private final ShopifyHmacVerifier hmac;
    private final RawWebhookService rawWebhooks;

    public ShopifyWebhookController(ShopifyHmacVerifier hmac, RawWebhookService rawWebhooks) {
        this.hmac = hmac;
        this.rawWebhooks = rawWebhooks;
    }

    @PostMapping("/shopify")
    public ResponseEntity<Void> ingest(
            @RequestHeader(value = "X-Shopify-Hmac-Sha256", required = false) String signature,
            @RequestHeader(value = "X-Shopify-Webhook-Id", required = false) String webhookId,
            @RequestHeader(value = "X-Shopify-Topic", defaultValue = "inventory_levels/update") String topic,
            @RequestBody byte[] rawBody) {
        if (!hmac.isValid(rawBody, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (webhookId == null || webhookId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        rawWebhooks.captureShopify(webhookId, topic, rawBody);
        return ResponseEntity.ok().build();
    }
}

