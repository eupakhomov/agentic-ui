-- Phase 5.3 follow-up: reflection can require explicit human approval before writing to
-- memory (default on — see docs/plan/phase-5.3-memory-reflection.md decision 14). A
-- proposal holds the computed-but-not-yet-applied episode + semantic ops; approval may
-- edit either before ReflectionService actually writes them.

CREATE TABLE memory_proposal (
    id            UUID PRIMARY KEY,
    session_id    UUID NOT NULL,
    session_name  TEXT NOT NULL,
    service_path  TEXT NOT NULL,
    reflected_seq BIGINT NOT NULL,   -- journal seq this reflection covered; applied to the
                                     -- session on approval, same as the auto-apply path
    episode       TEXT NOT NULL,
    ops           JSONB NOT NULL,    -- the model's raw "semantic" array — edited at approval time
    status        TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'DISCARDED')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at    TIMESTAMPTZ
);

CREATE INDEX idx_memory_proposal_pending ON memory_proposal (created_at) WHERE status = 'PENDING';
-- at most one pending proposal per session (decision 14): a second "Reflect now" while one
-- is pending is refused, not queued
CREATE UNIQUE INDEX idx_memory_proposal_one_pending_per_session ON memory_proposal (session_id)
    WHERE status = 'PENDING';
