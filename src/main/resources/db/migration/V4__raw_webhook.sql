CREATE TABLE raw_webhook (
    id UUID PRIMARY KEY,
    source_system TEXT NOT NULL,
    external_event_id TEXT NOT NULL,
    topic TEXT NOT NULL,
    payload BYTEA NOT NULL,
    state TEXT NOT NULL DEFAULT 'CAPTURED'
        CHECK (state IN ('CAPTURED', 'PUBLISHED', 'PROCESSED', 'QUARANTINED', 'FAILED')),
    error TEXT,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ,
    UNIQUE (source_system, external_event_id)
);

CREATE INDEX raw_webhook_state_received_idx ON raw_webhook (state, received_at);

