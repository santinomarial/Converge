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
public class SquareHmacVerifier {

    private final byte[] signatureKey;
    private final String notificationUrl;

    public SquareHmacVerifier(
            @Value("${connectors.square.webhook-signature-key}") String signatureKey,
            @Value("${connectors.square.notification-url}") String notificationUrl) {
        this.signatureKey = signatureKey.getBytes(StandardCharsets.UTF_8);
        this.notificationUrl = notificationUrl;
    }

    public boolean isValid(byte[] rawBody, String suppliedSignature) {
        if (suppliedSignature == null || suppliedSignature.isBlank()) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signatureKey, "HmacSHA256"));
            mac.update(notificationUrl.getBytes(StandardCharsets.UTF_8));
            byte[] expected = mac.doFinal(rawBody);
            return MessageDigest.isEqual(expected, Base64.getDecoder().decode(suppliedSignature));
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            return false;
        }
    }
}

