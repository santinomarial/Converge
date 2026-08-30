CREATE TABLE external_position (
    canonical_sku_id BIGINT NOT NULL REFERENCES canonical_sku(id),
    location_id BIGINT NOT NULL REFERENCES location(id),
    system TEXT NOT NULL,
    qty INTEGER NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    consecutive_drift_cycles INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (canonical_sku_id, location_id, system)
);

CREATE TABLE drift_sample (
    id BIGSERIAL PRIMARY KEY,
    canonical_sku_id BIGINT NOT NULL REFERENCES canonical_sku(id),
    location_id BIGINT NOT NULL REFERENCES location(id),
    system TEXT NOT NULL,
    drift INTEGER NOT NULL,
    sampled_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX drift_sample_lookup_idx
    ON drift_sample (system, sampled_at DESC, canonical_sku_id, location_id);

CREATE TABLE reconciliation_exception (
    id UUID PRIMARY KEY,
    canonical_sku_id BIGINT NOT NULL REFERENCES canonical_sku(id),
    location_id BIGINT NOT NULL REFERENCES location(id),
    target_system TEXT,
    type TEXT NOT NULL,
    severity TEXT NOT NULL CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
    ledger_qty INTEGER NOT NULL,
    observed INTEGER,
    detected_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    state TEXT NOT NULL DEFAULT 'OPEN' CHECK (state IN ('OPEN', 'CLAIMED', 'RESOLVED', 'DISMISSED')),
    claimed_by TEXT,
    claimed_at TIMESTAMPTZ,
    resolution TEXT,
    resolved_at TIMESTAMPTZ,
    resolution_event_id UUID
);

CREATE UNIQUE INDEX reconciliation_exception_active_idx
    ON reconciliation_exception (canonical_sku_id, location_id, target_system, type)
    WHERE state IN ('OPEN', 'CLAIMED');

