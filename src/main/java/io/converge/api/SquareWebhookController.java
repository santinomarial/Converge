package io.converge.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.converge.ingest.RawWebhookService;
import io.converge.ingest.SquareHmacVerifier;

@RestController
@RequestMapping("/webhooks")
public class SquareWebhookController {

    private final SquareHmacVerifier hmac;
    private final RawWebhookService rawWebhooks;

    public SquareWebhookController(SquareHmacVerifier hmac, RawWebhookService rawWebhooks) {
        this.hmac = hmac;
        this.rawWebhooks = rawWebhooks;
    }

    @PostMapping("/square")
    public ResponseEntity<Void> ingest(
            @RequestHeader(value = "X-Square-HmacSha256-Signature", required = false) String signature,
            @RequestHeader(value = "X-Square-Event-Id", required = false) String eventId,
            @RequestBody byte[] rawBody) {
        if (!hmac.isValid(rawBody, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (eventId == null || eventId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        rawWebhooks.capture("square", eventId, "inventory.count.updated", rawBody);
        return ResponseEntity.ok().build();
    }
}

