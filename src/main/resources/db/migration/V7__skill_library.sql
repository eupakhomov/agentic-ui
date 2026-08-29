-- Phase 6: curated skill & agent library — imported assets with metadata, tags,
-- optional pgvector embeddings for semantic search, and synced sources whose upstream
-- changes are tracked by content hash (auto-update / archive / suggest-new).

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE asset_source (
    id               UUID PRIMARY KEY,
    type             TEXT NOT NULL CHECK (type IN ('dir', 'repo')),
    ref              TEXT NOT NULL UNIQUE,
    sync_enabled     BOOLEAN NOT NULL DEFAULT false,
    last_synced_at   TIMESTAMPTZ,
    last_sync_status TEXT,
    last_sync_error  TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE library_asset (
    id           UUID PRIMARY KEY,
    -- source survives asset deletion and vice versa; SET NULL keeps manually-kept assets
    source_id    UUID REFERENCES asset_source (id) ON DELETE SET NULL,
    kind         TEXT NOT NULL CHECK (kind IN ('skill', 'agent')),
    name         TEXT NOT NULL,
    description  TEXT NOT NULL DEFAULT '',
    -- path of the managed copy under the skills/agents root
    location     TEXT NOT NULL,
    -- path relative to the source root, for hash comparison on sync
    source_path  TEXT,
    content_hash TEXT NOT NULL,
    status       TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_library_asset_source ON library_asset (source_id);

CREATE TABLE asset_tag (
    asset_id UUID NOT NULL REFERENCES library_asset (id) ON DELETE CASCADE,
    tag      TEXT NOT NULL,
    PRIMARY KEY (asset_id, tag)
);

CREATE TABLE asset_embedding (
    asset_id    UUID PRIMARY KEY REFERENCES library_asset (id) ON DELETE CASCADE,
    embedding   vector(1024) NOT NULL,
    model       TEXT NOT NULL,
    embedded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_asset_embedding_hnsw ON asset_embedding
    USING hnsw (embedding vector_cosine_ops);

-- files that appeared upstream in a synced source but are not imported yet
CREATE TABLE source_discovery (
    source_id     UUID NOT NULL REFERENCES asset_source (id) ON DELETE CASCADE,
    source_path   TEXT NOT NULL,
    kind          TEXT NOT NULL CHECK (kind IN ('skill', 'agent')),
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    dismissed     BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (source_id, source_path)
);
