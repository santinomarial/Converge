package io.converge.connectors.square;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.github.tomakehurst.wiremock.WireMockServer;

class SquareAdapterTest {
    private final WireMockServer square = new WireMockServer(0);
    @BeforeEach void start() { square.start(); }
    @AfterEach void stop() { square.stop(); }

    @Test
    void readsInventoryThroughTheSameConnectorPort() {
        square.stubFor(post(urlEqualTo("/v2/inventory/batch-retrieve-counts"))
                .willReturn(okJson("""
                        {"counts":[{"catalogObjectId":"sku-1","locationId":"loc-1","quantity":"13"}]}
                        """)));
        SquareProperties properties = new SquareProperties();
        properties.setBaseUrl(square.baseUrl());
        SquareAdapter adapter = new SquareAdapter(RestClient.builder(), properties);
        assertThat(adapter.fetchPosition("sku-1", "loc-1").qty()).isEqualTo(13);
    }

    @Test
    void writesInventoryWithTheStableAttemptIdInTheRequestBody() {
        square.stubFor(post(urlEqualTo("/v2/inventory/changes/batch-create"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                        .withStatus(200).withHeader("Content-Length", "0")));
        SquareProperties properties = new SquareProperties();
        properties.setBaseUrl(square.baseUrl());
        SquareAdapter adapter = new SquareAdapter(RestClient.builder(), properties);

        adapter.pushPosition("sku-1", "loc-1", 13, "attempt-123");

        square.verify(1, postRequestedFor(urlPathEqualTo("/v2/inventory/changes/batch-create"))
                .withRequestBody(matchingJsonPath("$.idempotency_key", equalTo("attempt-123"))));
    }
}
