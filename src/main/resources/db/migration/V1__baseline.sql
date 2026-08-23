-- Phase 0 baseline: proves the Flyway pipeline end-to-end.
-- Real schema arrives in Phase 2 (V2__core_schema.sql).
CREATE TABLE app_meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

INSERT INTO app_meta (key, value) VALUES ('schema.baseline', '2026-08-23');
