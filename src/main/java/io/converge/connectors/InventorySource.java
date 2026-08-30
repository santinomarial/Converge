package io.converge.connectors;

public interface InventorySource {

    String system();

    ExternalInventoryPosition fetchPosition(String externalSkuId, String externalLocationId);
}

