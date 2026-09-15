-- Phase 13 (docs/plan/phase-13-graphify.md decision 5): the per-session Serena flag generalizes
-- to "which code-intelligence tool was attached at creation" — 'serena' / 'graphify' / NULL.
-- Recorded per session (not just the global selector) because the MCP entry is baked into
-- mcp_config at creation: flipping the selector later must not change what a live session says.
ALTER TABLE session ADD COLUMN code_intel TEXT;
UPDATE session SET code_intel = 'serena' WHERE serena_enabled;
ALTER TABLE session DROP COLUMN serena_enabled;
