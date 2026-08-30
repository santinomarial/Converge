package io.converge.connectors.square;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
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
}

