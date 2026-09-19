-- Phase 15 (docs/plan/phase-15-review-sessions.md decision 6): sessions gain a first-class role.
-- Every existing row becomes a development session — no data migration needed beyond the default.
ALTER TABLE session ADD COLUMN session_type TEXT NOT NULL DEFAULT 'development';
