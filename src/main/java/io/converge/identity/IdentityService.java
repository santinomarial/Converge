package io.converge.identity;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class IdentityService {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public IdentityService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public long createSku(String sku, String style, String color, String size, String skuClass) {
        return jdbc.sql("""
                        INSERT INTO canonical_sku (sku, style, color, size, sku_class)
                        VALUES (:sku, :style, :color, :size, :skuClass)
                        RETURNING id
                        """)
                .param("sku", sku)
                .param("style", style)
                .param("color", color)
                .param("size", size)
                .param("skuClass", skuClass)
                .query(Long.class)
                .single();
    }

    public long createLocation(String code, String name, String type) {
        return jdbc.sql("""
                        INSERT INTO location (code, name, location_type)
                        VALUES (:code, :name, :type)
                        RETURNING id
                        """)
                .param("code", code)
                .param("name", name)
                .param("type", type)
                .query(Long.class)
                .single();
    }

    public void mapSku(long canonicalSkuId, String system, String externalId) {
        jdbc.sql("""
                        INSERT INTO sku_mapping (canonical_sku_id, system, external_id)
                        VALUES (:sku, :system, :externalId)
                        """)
                .param("sku", canonicalSkuId)
                .param("system", normalizedSystem(system))
                .param("externalId", externalId)
                .update();
    }

    public void mapLocation(long locationId, String system, String externalId) {
        jdbc.sql("""
                        INSERT INTO location_mapping (location_id, system, external_id)
                        VALUES (:location, :system, :externalId)
                        """)
                .param("location", locationId)
                .param("system", normalizedSystem(system))
                .param("externalId", externalId)
                .update();
    }

    @Transactional
    public IdentityResolution resolve(
            String sourceSystem,
            String externalSkuId,
            String externalLocationId,
            Map<String, Object> payload) {
        String system = normalizedSystem(sourceSystem);
        Optional<Long> sku = mappedSku(system, externalSkuId);
        Optional<Long> location = mappedLocation(system, externalLocationId);
        if (sku.isPresent() && location.isPresent()) {
            return new IdentityResolution.Mapped(new CanonicalIdentity(sku.get(), location.get()));
        }

        String reason = missingReason(sku, location);
        UUID id = UUID.randomUUID();
        UUID quarantineId = jdbc.sql("""
                        INSERT INTO identity_quarantine (
                            id, source_system, external_sku_id, external_location_id, reason, payload
                        ) VALUES (
                            :id, :system, :externalSku, :externalLocation, :reason, CAST(:payload AS jsonb)
                        )
                        ON CONFLICT (source_system, external_sku_id, external_location_id, state)
                        DO UPDATE SET
                            occurrences = identity_quarantine.occurrences + 1,
                            last_seen_at = now(),
                            reason = EXCLUDED.reason,
                            payload = EXCLUDED.payload
                        RETURNING id
                        """)
                .param("id", id)
                .param("system", system)
                .param("externalSku", externalSkuId)
                .param("externalLocation", externalLocationId)
                .param("reason", reason)
                .param("payload", json(payload))
                .query(UUID.class)
                .single();
        return new IdentityResolution.Quarantined(quarantineId, reason);
    }

    private Optional<Long> mappedSku(String system, String externalId) {
        return jdbc.sql("SELECT canonical_sku_id FROM sku_mapping WHERE system = :system AND external_id = :id")
                .param("system", system)
                .param("id", externalId)
                .query(Long.class)
                .optional();
    }

    private Optional<Long> mappedLocation(String system, String externalId) {
        return jdbc.sql("SELECT location_id FROM location_mapping WHERE system = :system AND external_id = :id")
                .param("system", system)
                .param("id", externalId)
                .query(Long.class)
                .optional();
    }

    private String missingReason(Optional<Long> sku, Optional<Long> location) {
        if (sku.isEmpty() && location.isEmpty()) {
            return "UNMAPPED_SKU_AND_LOCATION";
        }
        return sku.isEmpty() ? "UNMAPPED_SKU" : "UNMAPPED_LOCATION";
    }

    private String normalizedSystem(String system) {
        if (system == null || system.isBlank()) {
            throw new IllegalArgumentException("source system is required");
        }
        return system.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private String json(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Payload cannot be serialized", exception);
        }
    }
}

