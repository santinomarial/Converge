package io.converge.connectors.shopify;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("connectors.shopify")
public class ShopifyProperties {

    private String baseUrl = "http://localhost:9999";
    private String accessToken = "development-token";
    private String webhookSecret = "development-secret";

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public void setWebhookSecret(String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }
}

