-- Phase 18 (docs/plan/phase-18-slack.md): per-session Slack mirroring. slack_enabled is the opt-in;
-- slack_channel + slack_thread_ts identify the thread (the parent message's ts) once the first post succeeds.
-- Existing rows stay off.
ALTER TABLE session ADD COLUMN slack_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE session ADD COLUMN slack_channel TEXT;
ALTER TABLE session ADD COLUMN slack_thread_ts TEXT;
-- A thread reply arrives as (channel, thread_ts); look the session up by it.
CREATE INDEX session_slack_thread_idx ON session (slack_channel, slack_thread_ts) WHERE slack_thread_ts IS NOT NULL;
