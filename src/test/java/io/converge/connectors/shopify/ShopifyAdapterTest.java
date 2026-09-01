package io.converge.connectors.shopify;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.github.tomakehurst.wiremock.WireMockServer;

class ShopifyAdapterTest {

    private final WireMockServer shopify = new WireMockServer(0);

    @BeforeEach
    void start() {
        shopify.start();
    }

    @AfterEach
    void stop() {
        shopify.stop();
    }

    @Test
    void readsInventoryThroughTheConnectorPort() {
        shopify.stubFor(get(urlPathEqualTo("/inventory_levels.json"))
                .withQueryParam("inventory_item_ids", equalTo("42"))
                .withQueryParam("location_ids", equalTo("7"))
                .willReturn(okJson("""
                        {"inventory_levels":[{"inventoryItemId":42,"locationId":7,"available":19}]}
                        """)));
        ShopifyProperties properties = new ShopifyProperties();
        properties.setBaseUrl(shopify.baseUrl());
        ShopifyAdapter adapter = new ShopifyAdapter(RestClient.builder(), properties);

        assertThat(adapter.fetchPosition("42", "7").qty()).isEqualTo(19);
    }

    @Test
    void writesInventoryWithTheStableAttemptIdAsIdempotencyKey() {
        shopify.stubFor(post(urlPathEqualTo("/inventory_levels/set.json"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                        .withStatus(200).withHeader("Content-Length", "0")));
        ShopifyProperties properties = new ShopifyProperties();
        properties.setBaseUrl(shopify.baseUrl());
        ShopifyAdapter adapter = new ShopifyAdapter(RestClient.builder(), properties);

        adapter.pushPosition("42", "7", 19, "attempt-123");

        shopify.verify(1, postRequestedFor(urlPathEqualTo("/inventory_levels/set.json"))
                .withHeader("X-Idempotency-Key", equalTo("attempt-123")));
    }
}
