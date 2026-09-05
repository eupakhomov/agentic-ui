-- Phase 5.3: layered long-term memory. Semantic memory (Markdown files under a managed
-- root) is indexed here for hybrid search; the files themselves are the source of truth
-- (see docs/plan/phase-5.3-memory-reflection.md). Episodic memory (what a session did)
-- is DB-native and append-only.

CREATE EXTENSION IF NOT EXISTS pg_trgm;   -- vector already enabled by V7

CREATE TABLE memory_doc (
    id            UUID PRIMARY KEY,
    scope         TEXT NOT NULL CHECK (scope IN ('ecosystem', 'service')),
    service_path  TEXT,                   -- canonical repo path; null iff scope = 'ecosystem'
    rel_path      TEXT NOT NULL UNIQUE,   -- relative to memory root; file is truth
    name          TEXT NOT NULL,          -- slug; unique within its scope directory (== filename stem)
    description   TEXT NOT NULL,
    tags          TEXT[] NOT NULL DEFAULT '{}',
    content       TEXT NOT NULL,          -- indexed copy of the file body (frontmatter stripped)
    content_hash  TEXT NOT NULL,          -- SHA-256 of the full file, drives human-edit detection
    status        TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    embedding     vector(1024),           -- null when Voyage unconfigured
    embedding_model TEXT,
    tsv           tsvector GENERATED ALWAYS AS (
                    setweight(to_tsvector('english', coalesce(name, '') || ' ' || coalesce(description, '')), 'A')
                    || setweight(to_tsvector('english', coalesce(content, '')), 'B')) STORED
);

CREATE INDEX idx_memory_doc_hnsw ON memory_doc USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_memory_doc_tsv ON memory_doc USING gin (tsv);
CREATE INDEX idx_memory_doc_trgm ON memory_doc USING gin (content gin_trgm_ops);
CREATE INDEX idx_memory_doc_scope ON memory_doc (scope, service_path) WHERE status = 'ACTIVE';

-- Wikilinks ([[slug]] / [[slug|alias]]) extracted from doc bodies at index time.
-- to_doc_id null = dangling (target slug not written yet, or archived); resolved
-- automatically once a doc with that slug (re)appears in an in-scope location.
CREATE TABLE memory_link (
    from_doc_id   UUID NOT NULL REFERENCES memory_doc (id) ON DELETE CASCADE,
    to_slug       TEXT NOT NULL,
    to_doc_id     UUID REFERENCES memory_doc (id) ON DELETE SET NULL,
    PRIMARY KEY (from_doc_id, to_slug)
);

CREATE INDEX idx_memory_link_to_doc ON memory_link (to_doc_id);
CREATE INDEX idx_memory_link_dangling ON memory_link (to_slug) WHERE to_doc_id IS NULL;

CREATE TABLE memory_episode (
    id            UUID PRIMARY KEY,
    session_id    UUID NOT NULL,          -- no FK cascade: episodes outlive a session's row lifecycle
    session_name  TEXT NOT NULL,
    service_path  TEXT NOT NULL,
    ts            TIMESTAMPTZ NOT NULL DEFAULT now(),
    summary       TEXT NOT NULL,
    embedding     vector(1024),
    embedding_model TEXT,
    tsv           tsvector GENERATED ALWAYS AS (to_tsvector('english', coalesce(summary, ''))) STORED
);

CREATE INDEX idx_memory_episode_hnsw ON memory_episode USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_memory_episode_tsv ON memory_episode USING gin (tsv);
CREATE INDEX idx_memory_episode_service ON memory_episode (service_path, ts DESC);

ALTER TABLE session ADD COLUMN reflection_enabled BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE session ADD COLUMN reflected_seq BIGINT;
