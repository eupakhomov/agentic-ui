-- Phase 8: ecosystem service discovery (see docs/plan/phase-8-service-discovery.md).
-- DB-only cache (no Markdown vault, unlike memory) of an LLM-generated description per
-- service (git repo) under the ecosystem root, regenerated at session close when missing
-- or stale. Change detection is the repo's own git commit SHA, not a content hash of the
-- digest inputs — cheap (one `git rev-parse HEAD`) and correct regardless of repo size.

CREATE TABLE service_profile (
    id              UUID PRIMARY KEY,
    repo_path       TEXT NOT NULL UNIQUE,
    name            TEXT NOT NULL,
    description     TEXT NOT NULL,
    tags            TEXT[] NOT NULL DEFAULT '{}',
    last_commit_sha TEXT,                  -- null for a repo with no commits yet (always regenerates)
    embedding       vector(1024),           -- null when Voyage unconfigured
    embedding_model TEXT,
    discovered_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    session_id      UUID,                   -- triggering session; null for a manual rediscover; no FK (sessions may be pruned)
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    tsv             tsvector GENERATED ALWAYS AS (
                        setweight(to_tsvector('english', coalesce(name, '') || ' ' || coalesce(description, '')), 'A')
                    ) STORED
);

CREATE INDEX idx_service_profile_hnsw ON service_profile USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_service_profile_tsv ON service_profile USING gin (tsv);
CREATE INDEX idx_service_profile_trgm ON service_profile USING gin (description gin_trgm_ops);
