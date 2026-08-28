-- Tracks the single GitHub PR (if any) opened from a session's branch, so CI status can be
-- polled in the background and shown/notified without depending on ephemeral UI state.
ALTER TABLE session ADD COLUMN pr_url TEXT;
ALTER TABLE session ADD COLUMN pr_head_sha TEXT;
ALTER TABLE session ADD COLUMN pr_check_status TEXT;
ALTER TABLE session ADD COLUMN pr_checked_at TIMESTAMPTZ;
