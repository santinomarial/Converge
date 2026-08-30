package io.converge.ingest;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ShopifyHmacVerifier {

    private final byte[] secret;

    public ShopifyHmacVerifier(@Value("${connectors.shopify.webhook-secret}") String webhookSecret) {
        this.secret = webhookSecret.getBytes(StandardCharsets.UTF_8);
    }

    public boolean isValid(byte[] rawBody, String suppliedBase64Hmac) {
        if (suppliedBase64Hmac == null || suppliedBase64Hmac.isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] expected = mac.doFinal(rawBody);
            byte[] supplied = Base64.getDecoder().decode(suppliedBase64Hmac);
            return MessageDigest.isEqual(expected, supplied);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            return false;
        }
    }
}
