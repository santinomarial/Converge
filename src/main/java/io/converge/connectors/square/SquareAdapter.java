package io.converge.connectors.square;

import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import io.converge.connectors.ExternalInventoryPosition;
import io.converge.connectors.InventorySink;
import io.converge.connectors.InventorySource;

@Component
public class SquareAdapter implements InventorySource, InventorySink {

    private final RestClient client;

    public SquareAdapter(RestClient.Builder builder, SquareProperties properties) {
        this.client = builder.baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory())
                .defaultHeader("Authorization", "Bearer " + properties.getAccessToken())
                .defaultHeader("Square-Version", "2026-08-20")
                .build();
    }

    private static JdkClientHttpRequestFactory requestFactory() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(java.time.Duration.ofSeconds(20));
        return factory;
    }

    @Override
    public String system() { return "square"; }

    @Override
    public ExternalInventoryPosition fetchPosition(String externalSkuId, String externalLocationId) {
        BatchResponse response = client.post().uri("/v2/inventory/batch-retrieve-counts")
                .body(Map.of("catalog_object_ids", List.of(externalSkuId),
                        "location_ids", List.of(externalLocationId)))
                .retrieve().body(BatchResponse.class);
        if (response == null || response.counts() == null || response.counts().isEmpty()) {
            throw new IllegalStateException("Square returned no inventory count");
        }
        return new ExternalInventoryPosition(system(), externalSkuId, externalLocationId,
                Integer.parseInt(response.counts().getFirst().quantity()), Instant.now());
    }

    @Override
    public void pushPosition(String externalSkuId, String externalLocationId, int targetQty, String idempotencyKey) {
        client.post().uri("/v2/inventory/changes/batch-create")
                .body(Map.of("idempotency_key", idempotencyKey,
                        "changes", List.of(Map.of("type", "PHYSICAL_COUNT", "physical_count", Map.of(
                                "catalog_object_id", externalSkuId,
                                "location_id", externalLocationId,
                                "quantity", Integer.toString(targetQty),
                                "occurred_at", Instant.now().toString())))))
                .retrieve().toBodilessEntity();
    }

    record BatchResponse(List<SquareCount> counts) { }
    record SquareCount(String catalogObjectId, String locationId, String quantity) { }
}
