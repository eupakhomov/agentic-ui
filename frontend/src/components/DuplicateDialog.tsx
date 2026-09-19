import { useState } from 'react';
import { api, ApiError } from '../api/rest';
import type { SessionType } from '../protocol';
import { useBackdropDismiss } from '../hooks/useBackdropDismiss';

export default function DuplicateDialog({
  sessionId,
  sessionType,
  defaultBranch,
  onDuplicated,
  onCancel,
}: {
  sessionId: string;
  sessionType: SessionType;
  defaultBranch: string;
  onDuplicated: (id: string) => void;
  onCancel: () => void;
}) {
  const isReview = sessionType === 'review';
  // a review duplicate reviews the SAME branch/PR again (proposal 7) — no "-copy" suffix, since
  // that would name a branch that doesn't exist to review
  const [branch, setBranch] = useState(isReview ? defaultBranch : `${defaultBranch}-copy`);
  const [name, setName] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const duplicate = async () => {
    setError('');
    setBusy(true);
    try {
      const created = await api.duplicateSession(sessionId, {
        branch: branch.trim(),
        name: name.trim() || undefined,
        syncBaseBranch: true,
      });
      onDuplicated(created.id);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const backdropDismiss = useBackdropDismiss(onCancel);

  return (
    <div className="modal-backdrop" {...backdropDismiss}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h2>Duplicate session</h2>
        <p style={{ color: 'var(--muted)' }}>
          {isReview
            ? 'Starts another review session with the same model, permission mode, MCP servers, skills, and agents, reviewing the same branch/PR (editable below).'
            : 'Starts a new session with the same model, permission mode, MCP servers, skills, and agents, on a new branch off the same base branch.'}
        </p>
        <div className="form-grid">
          <label>{isReview ? 'Branch to review' : 'New branch'}</label>
          <input value={branch} onChange={(e) => setBranch(e.target.value)} autoFocus />
          <label>Name</label>
          <input value={name} onChange={(e) => setName(e.target.value)} placeholder="defaults to branch name" />
        </div>
        {error && <div className="error-text">{error}</div>}
        <div className="actions">
          <button onClick={onCancel}>Cancel</button>
          <button className="primary" disabled={busy || !branch.trim()} onClick={() => void duplicate()}>
            {busy ? 'Duplicating…' : 'Duplicate'}
          </button>
        </div>
      </div>
    </div>
  );
}
