-- Phase 5.4: live link from a session template to library skills/agents (Phase 6).
-- A row here is a reference by library_asset.id, not a frozen copy — editing or
-- archiving the asset is reflected next time the template is used to create a session.

CREATE TABLE template_asset (
    template_id UUID NOT NULL REFERENCES session_template (id) ON DELETE CASCADE,
    asset_id    UUID NOT NULL REFERENCES library_asset (id) ON DELETE CASCADE,
    kind        TEXT NOT NULL CHECK (kind IN ('skill', 'agent')),
    PRIMARY KEY (template_id, asset_id)
);

CREATE INDEX idx_template_asset_asset ON template_asset (asset_id);
