import { useState } from 'react';
import { api, ApiError } from '../api/rest';

export default function DuplicateDialog({
  sessionId,
  defaultBranch,
  onDuplicated,
  onCancel,
}: {
  sessionId: string;
  defaultBranch: string;
  onDuplicated: (id: string) => void;
  onCancel: () => void;
}) {
  const [branch, setBranch] = useState(`${defaultBranch}-copy`);
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

  return (
    <div className="modal-backdrop" onClick={onCancel}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h2>Duplicate session</h2>
        <p style={{ color: 'var(--muted)' }}>
          Starts a new session with the same model, permission mode, MCP servers, skills, and agents,
          on a new branch off the same base branch.
        </p>
        <div className="form-grid">
          <label>New branch</label>
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
