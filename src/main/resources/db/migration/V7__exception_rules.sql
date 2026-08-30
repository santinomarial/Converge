ALTER TABLE reconciliation_exception
    ADD COLUMN resolution_note TEXT;

ALTER TABLE reconciliation_exception
    ADD CONSTRAINT reconciliation_exception_resolution_event_fk
    FOREIGN KEY (resolution_event_id) REFERENCES inventory_event(event_id);

CREATE TABLE reconciliation_rule (
    sku_class TEXT PRIMARY KEY,
    snapshot_exception_threshold INTEGER NOT NULL CHECK (snapshot_exception_threshold >= 0),
    negative_position_policy TEXT NOT NULL CHECK (negative_position_policy IN ('ALLOW', 'CLAMP', 'EXCEPTION')),
    precedence JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO reconciliation_rule (
    sku_class, snapshot_exception_threshold, negative_position_policy, precedence
) VALUES (
    'DEFAULT', 10, 'EXCEPTION', '["physical_count", "square", "shopify"]'::jsonb
);

