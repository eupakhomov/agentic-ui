-- Canonical ticket identifier (e.g. "ENG-123") captured at ticket-import time, so commit
-- message / PR generation can reliably prefix it without parsing the branch name.
ALTER TABLE session ADD COLUMN ticket_ref TEXT;
