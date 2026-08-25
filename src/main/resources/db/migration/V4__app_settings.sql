-- Persisted, UI-editable app settings (non-secret only — secrets like the Linear API key
-- stay in env vars). Separate from app_meta, which holds internal schema bookkeeping.
CREATE TABLE app_setting (
    key        TEXT PRIMARY KEY,
    value      TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
