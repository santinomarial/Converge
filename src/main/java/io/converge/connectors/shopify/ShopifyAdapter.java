package io.converge.connectors.shopify;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import io.converge.connectors.ExternalInventoryPosition;
import io.converge.connectors.InventorySink;
import io.converge.connectors.InventorySource;

@Component
public class ShopifyAdapter implements InventorySource, InventorySink {

    private final RestClient client;

    public ShopifyAdapter(RestClient.Builder builder, ShopifyProperties properties) {
        this.client = builder.baseUrl(properties.getBaseUrl())
                .defaultHeader("X-Shopify-Access-Token", properties.getAccessToken())
                .build();
    }

    @Override
    public String system() {
        return "shopify";
    }

    @Override
    public ExternalInventoryPosition fetchPosition(String externalSkuId, String externalLocationId) {
        InventoryLevelsResponse response = client.get()
                .uri(uri -> uri.path("/inventory_levels.json")
                        .queryParam("inventory_item_ids", externalSkuId)
                        .queryParam("location_ids", externalLocationId)
                        .build())
                .retrieve()
                .body(InventoryLevelsResponse.class);
        if (response == null || response.inventoryLevels() == null || response.inventoryLevels().isEmpty()) {
            throw new IllegalStateException("Shopify returned no inventory level");
        }
        ShopifyInventoryLevel level = response.inventoryLevels().getFirst();
        return new ExternalInventoryPosition(system(), externalSkuId, externalLocationId,
                level.available(), Instant.now());
    }

    @Override
    public void pushPosition(String externalSkuId, String externalLocationId, int targetQty) {
        client.post()
                .uri("/inventory_levels/set.json")
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body(Map.of(
                        "inventory_item_id", externalSkuId,
                        "location_id", externalLocationId,
                        "available", targetQty))
                .retrieve()
                .toBodilessEntity();
    }

    record InventoryLevelsResponse(
            @com.fasterxml.jackson.annotation.JsonProperty("inventory_levels")
            List<ShopifyInventoryLevel> inventoryLevels) {
    }

    record ShopifyInventoryLevel(long inventoryItemId, long locationId, int available) {
    }
}

