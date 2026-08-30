CREATE TABLE inventory_event (
    seq BIGSERIAL PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    canonical_sku_id BIGINT NOT NULL,
    location_id BIGINT NOT NULL,
    source_system TEXT NOT NULL,
    external_event_id TEXT,
    event_type TEXT NOT NULL CHECK (event_type IN ('SALE', 'RETURN', 'RESTOCK', 'ADJUSTMENT', 'TRANSFER', 'COUNT')),
    kind TEXT NOT NULL CHECK (kind IN ('DELTA', 'SNAPSHOT')),
    qty_delta INTEGER,
    qty_absolute INTEGER,
    occurred_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    absorbed BOOLEAN NOT NULL DEFAULT false,
    causation_id UUID,
    payload JSONB NOT NULL,
    CONSTRAINT inventory_event_quantity_matches_kind CHECK (
        (kind = 'DELTA' AND qty_delta IS NOT NULL AND qty_absolute IS NULL)
        OR (kind = 'SNAPSHOT' AND qty_absolute IS NOT NULL AND qty_delta IS NULL)
    ),
    UNIQUE (source_system, external_event_id)
);

CREATE INDEX inventory_event_aggregate_time_idx
    ON inventory_event (canonical_sku_id, location_id, occurred_at, seq);

CREATE TABLE inventory_position (
    canonical_sku_id BIGINT NOT NULL,
    location_id BIGINT NOT NULL,
    qty INTEGER NOT NULL,
    anchor_seq BIGINT NOT NULL,
    last_applied_seq BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (canonical_sku_id, location_id)
);

CREATE FUNCTION reject_inventory_event_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'inventory_event is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER inventory_event_append_only
    BEFORE UPDATE OR DELETE ON inventory_event
    FOR EACH ROW EXECUTE FUNCTION reject_inventory_event_mutation();
