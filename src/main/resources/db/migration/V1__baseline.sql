CREATE TABLE app_metadata (
    name TEXT PRIMARY KEY,
    value TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO app_metadata (name, value) VALUES ('schema', '1');

