-- Core schema: sessions, event journal, message queue, templates.
-- List/map-valued fields use JSONB throughout (incl. tool lists) for uniform handling.

CREATE TABLE session (
    id                  UUID PRIMARY KEY,
    name                TEXT NOT NULL,
    provider            TEXT NOT NULL DEFAULT 'claude',
    provider_config     JSONB,
    repo_path           TEXT NOT NULL,
    ecosystem_path      TEXT,
    context_dirs        JSONB NOT NULL DEFAULT '[]',
    branch              TEXT NOT NULL,
    base_branch         TEXT NOT NULL,
    worktree_path       TEXT NOT NULL,
    provider_session_id TEXT,
    capabilities        JSONB,
    model               TEXT,
    permission_mode     TEXT NOT NULL DEFAULT 'default',
    allowed_tools       JSONB NOT NULL DEFAULT '[]',
    disallowed_tools    JSONB NOT NULL DEFAULT '[]',
    mcp_config          JSONB,
    env_vars            JSONB,
    skill_sources       JSONB NOT NULL DEFAULT '[]',
    agent_sources       JSONB NOT NULL DEFAULT '[]',
    instructions        TEXT,
    thinking            TEXT,             -- 'off' | 'adaptive' | '<budgetTokens>'
    effort              TEXT,             -- low|medium|high|xhigh|max
    max_turns           INT,
    fallback_model      TEXT,
    cost_budget_usd     NUMERIC,
    kickoff_prompt      TEXT,
    state               TEXT NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE session_event (
    session_id UUID   NOT NULL REFERENCES session(id) ON DELETE CASCADE,
    seq        BIGINT NOT NULL,
    ts         TIMESTAMPTZ NOT NULL DEFAULT now(),
    type       TEXT   NOT NULL,
    payload    JSONB  NOT NULL,
    PRIMARY KEY (session_id, seq)
);

CREATE TABLE session_queue (
    session_id UUID NOT NULL REFERENCES session(id) ON DELETE CASCADE,
    pos        BIGINT GENERATED ALWAYS AS IDENTITY,
    text       TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (session_id, pos)
);

CREATE TABLE session_template (
    id          UUID PRIMARY KEY,
    name        TEXT NOT NULL UNIQUE,
    description TEXT,
    config      JSONB NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
