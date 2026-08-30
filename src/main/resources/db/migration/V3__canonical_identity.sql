CREATE TABLE canonical_sku (
    id BIGSERIAL PRIMARY KEY,
    sku TEXT NOT NULL UNIQUE,
    style TEXT NOT NULL,
    color TEXT NOT NULL,
    size TEXT NOT NULL,
    sku_class TEXT NOT NULL DEFAULT 'DEFAULT',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE location (
    id BIGSERIAL PRIMARY KEY,
    code TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    location_type TEXT NOT NULL CHECK (location_type IN ('STORE', 'WAREHOUSE', 'POP_UP', 'ONLINE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE sku_mapping (
    id BIGSERIAL PRIMARY KEY,
    canonical_sku_id BIGINT NOT NULL REFERENCES canonical_sku(id),
    system TEXT NOT NULL,
    external_id TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (system, external_id),
    UNIQUE (canonical_sku_id, system)
);

CREATE TABLE location_mapping (
    id BIGSERIAL PRIMARY KEY,
    location_id BIGINT NOT NULL REFERENCES location(id),
    system TEXT NOT NULL,
    external_id TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (system, external_id),
    UNIQUE (location_id, system)
);

CREATE TABLE identity_quarantine (
    id UUID PRIMARY KEY,
    source_system TEXT NOT NULL,
    external_sku_id TEXT,
    external_location_id TEXT,
    reason TEXT NOT NULL,
    state TEXT NOT NULL DEFAULT 'OPEN' CHECK (state IN ('OPEN', 'RESOLVED', 'DISMISSED')),
    payload JSONB NOT NULL,
    occurrences INTEGER NOT NULL DEFAULT 1,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE NULLS NOT DISTINCT (source_system, external_sku_id, external_location_id, state)
);

ALTER TABLE inventory_event
    ADD CONSTRAINT inventory_event_sku_fk FOREIGN KEY (canonical_sku_id) REFERENCES canonical_sku(id),
    ADD CONSTRAINT inventory_event_location_fk FOREIGN KEY (location_id) REFERENCES location(id);

ALTER TABLE inventory_position
    ADD CONSTRAINT inventory_position_sku_fk FOREIGN KEY (canonical_sku_id) REFERENCES canonical_sku(id),
    ADD CONSTRAINT inventory_position_location_fk FOREIGN KEY (location_id) REFERENCES location(id);

