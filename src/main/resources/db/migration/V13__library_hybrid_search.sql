-- Phase 9 O3: promote library search to the same dense+sparse+trigram hybrid RRF search
-- memory already has (V9), so the two search boxes behave consistently — sparse/trigram now
-- work even when Voyage isn't configured or an asset was never (re-)embedded, instead of
-- library search being dense-only-or-nothing.

ALTER TABLE library_asset ADD COLUMN tsv tsvector GENERATED ALWAYS AS (
    setweight(to_tsvector('english', coalesce(name, '')), 'A')
    || setweight(to_tsvector('english', coalesce(description, '')), 'B')) STORED;

CREATE INDEX idx_library_asset_tsv ON library_asset USING gin (tsv);
CREATE INDEX idx_library_asset_name_trgm ON library_asset USING gin (name gin_trgm_ops);
