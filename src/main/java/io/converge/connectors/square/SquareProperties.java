package io.converge.connectors.square;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("connectors.square")
public class SquareProperties {

    private String baseUrl = "http://localhost:9998";
    private String accessToken = "development-token";
    private String webhookSignatureKey = "development-square-secret";
    private String notificationUrl = "http://localhost:8080/webhooks/square";

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
    public String getWebhookSignatureKey() { return webhookSignatureKey; }
    public void setWebhookSignatureKey(String webhookSignatureKey) { this.webhookSignatureKey = webhookSignatureKey; }
    public String getNotificationUrl() { return notificationUrl; }
    public void setNotificationUrl(String notificationUrl) { this.notificationUrl = notificationUrl; }
}

