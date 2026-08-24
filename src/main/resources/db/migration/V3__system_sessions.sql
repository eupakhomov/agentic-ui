-- System sessions: backend-initiated tasks (ticket import, future ones) reuse the normal
-- session/sidecar machinery for observability, tagged 'system' so the dashboard hides them
-- by default. Exactly one live system session exists at a time (enforced in SessionService).
ALTER TABLE session ADD COLUMN kind TEXT NOT NULL DEFAULT 'user' CHECK (kind IN ('user', 'system'));
