-- Phase 7.3/7.4 (see docs/plan/phase-7-ux-and-orchestration.md): a session's lineage —
-- continuation (7.3) and parent/child fan-out (7.4). No FK cascades — lineage is
-- informational and should outlive the source/parent row's eventual close/cleanup.

ALTER TABLE session ADD COLUMN continued_from_id UUID;
ALTER TABLE session ADD COLUMN parent_session_id UUID;
