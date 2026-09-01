package io.converge.connectors;

public interface InventorySink {

    String system();

    void pushPosition(String externalSkuId, String externalLocationId, int targetQty, String idempotencyKey);
}
