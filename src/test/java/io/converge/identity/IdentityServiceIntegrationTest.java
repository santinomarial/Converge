package io.converge.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.converge.IntegrationTestSupport;

@SpringBootTest
class IdentityServiceIntegrationTest extends IntegrationTestSupport {

    @Autowired
    IdentityService identity;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clearIdentity() {
        jdbc.sql("""
                TRUNCATE inventory_position, inventory_event, identity_quarantine,
                         sku_mapping, location_mapping, canonical_sku, location
                         RESTART IDENTITY CASCADE
                """).update();
    }

    @Test
    void mapsBothExternalIdentifiersToOneCanonicalIdentity() {
        long sku = identity.createSku("TSH-CRM-M", "TSH", "CRM", "M", "APPAREL");
        long location = identity.createLocation("SQ-01", "Main square", "STORE");
        identity.mapSku(sku, "Shopify", "gid://sku/42");
        identity.mapLocation(location, "Shopify", "gid://location/7");

        IdentityResolution resolution = identity.resolve(
                "SHOPIFY", "gid://sku/42", "gid://location/7", Map.of("inventory", 3));

        assertThat(resolution).isEqualTo(
                new IdentityResolution.Mapped(new CanonicalIdentity(sku, location)));
    }

    @Test
    void quarantinesUnknownIdentityAndCountsDuplicateSightings() {
        IdentityResolution first = identity.resolve("square", "unknown", "missing", Map.of("qty", -1));
        IdentityResolution second = identity.resolve("square", "unknown", "missing", Map.of("qty", -1));

        assertThat(first).isInstanceOf(IdentityResolution.Quarantined.class);
        assertThat(second).isInstanceOf(IdentityResolution.Quarantined.class);
        assertThat(((IdentityResolution.Quarantined) second).quarantineId())
                .isEqualTo(((IdentityResolution.Quarantined) first).quarantineId());
        assertThat(jdbc.sql("SELECT occurrences FROM identity_quarantine")
                .query(Integer.class).single()).isEqualTo(2);
    }
}

