-- Phase 12 track C (see docs/plan/phase-12-linear-cache-serena-context.md decision 9): latest
-- known context-window usage per session, so the widget's chip is right immediately after a
-- page reload without waiting for a turn. NULL until the sidecar's first context_usage event
-- (both columns update together — see SessionRepository.updateContextUsage).
ALTER TABLE session ADD COLUMN context_tokens INTEGER;
ALTER TABLE session ADD COLUMN context_window INTEGER;
