-- Phase 12 track B (see docs/plan/phase-12-linear-cache-serena-context.md decision 2): opt-in
-- Serena (symbolic code tools) MCP server, per session — default off, since each enabled
-- session runs its own Python process plus a language server.
ALTER TABLE session ADD COLUMN serena_enabled BOOLEAN NOT NULL DEFAULT FALSE;
