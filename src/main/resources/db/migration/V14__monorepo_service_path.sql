-- Phase 11: monorepo support (see docs/plan/phase-11-monorepo.md). servicePath becomes the
-- identity memory/discovery/orchestration key on (a folder inside a git repo); repoPath is
-- demoted to "git root for worktree ops". Polyrepo is the servicePath == repoPath special
-- case, so every existing row stays correct under the new meaning without a data migration:
-- both new columns are nullable, and application code (SessionEntity.servicePath()/cwdPath(),
-- ServiceProfileRepository's repoPath fallback) resolves the NULL so callers never branch.

ALTER TABLE session ADD COLUMN service_path TEXT;                     -- NULL = same as repo_path

ALTER TABLE service_profile RENAME COLUMN repo_path TO service_path;  -- UNIQUE constraint follows the column
ALTER TABLE service_profile ADD COLUMN repo_path TEXT;                -- git root; NULL = same as service_path
