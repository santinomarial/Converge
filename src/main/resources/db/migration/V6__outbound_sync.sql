CREATE TABLE outbox (
    id UUID PRIMARY KEY,
    aggregate_id TEXT NOT NULL,
    topic TEXT NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

CREATE INDEX outbox_unpublished_idx ON outbox (created_at) WHERE published_at IS NULL;

CREATE TABLE sync_attempt (
    id UUID PRIMARY KEY,
    outbox_id UUID,
    canonical_sku_id BIGINT NOT NULL REFERENCES canonical_sku(id),
    location_id BIGINT NOT NULL REFERENCES location(id),
    target_system TEXT NOT NULL,
    external_sku_id TEXT NOT NULL,
    external_location_id TEXT NOT NULL,
    target_qty INTEGER NOT NULL,
    state TEXT NOT NULL DEFAULT 'QUEUED'
        CHECK (state IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    attempt INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error TEXT,
    compensation BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (outbox_id, target_system)
);

CREATE INDEX sync_attempt_ready_idx ON sync_attempt (next_attempt_at, created_at)
    WHERE state = 'QUEUED';

