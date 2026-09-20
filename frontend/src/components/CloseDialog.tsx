import { useState } from 'react';
import { api, ApiError } from '../api/rest';
import type { SessionType } from '../protocol';
import { useBackdropDismiss } from '../hooks/useBackdropDismiss';

export default function CloseDialog({
  sessionId,
  sessionType,
  onClosed,
  onCancel,
}: {
  sessionId: string;
  sessionType: SessionType;
  onClosed: () => void;
  onCancel: () => void;
}) {
  const [dirtyFiles, setDirtyFiles] = useState<string[] | null>(null);
  const [commitMessage, setCommitMessage] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const close = async (dirty: string) => {
    setError('');
    setBusy(true);
    try {
      await api.closeSession(sessionId, dirty, dirty === 'commit' ? commitMessage : undefined);
      onClosed();
    } catch (e) {
      if (e instanceof ApiError && e.status === 409 && Array.isArray(e.body?.['dirtyFiles'])) {
        setDirtyFiles(e.body['dirtyFiles'] as string[]);
      } else {
        setError(e instanceof Error ? e.message : String(e));
      }
    } finally {
      setBusy(false);
    }
  };

  const backdropDismiss = useBackdropDismiss(onCancel);

  return (
    <div className="modal-backdrop" {...backdropDismiss}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h2>Close session</h2>
        {dirtyFiles === null ? (
          <>
            <p style={{ color: 'var(--muted)' }}>
              Ends the agent session and removes the worktree. Uncommitted changes will be detected first.
            </p>
            {error && <div className="error-text">{error}</div>}
            <div className="actions">
              <button onClick={onCancel}>Cancel</button>
              <button className="danger" disabled={busy} onClick={() => void close('fail')}>Close session</button>
            </div>
          </>
        ) : (
          <>
            <p>The worktree has uncommitted changes:</p>
            <pre style={{ background: 'var(--panel2)', padding: 10, borderRadius: 6, maxHeight: 180, overflow: 'auto' }}>
              {dirtyFiles.join('\n')}
            </pre>
            {sessionType !== 'review' && (
              <div className="form-grid">
                <label>Commit message</label>
                <input
                  value={commitMessage}
                  onChange={(e) => setCommitMessage(e.target.value)}
                  placeholder="used by Commit & close"
                />
              </div>
            )}
            {error && <div className="error-text">{error}</div>}
            <div className="actions">
              <button onClick={onCancel}>Cancel</button>
              {sessionType !== 'review' && (
                <button className="primary" disabled={busy} onClick={() => void close('commit')}>Commit &amp; close</button>
              )}
              <button
                className={sessionType === 'review' ? 'primary' : undefined}
                disabled={busy}
                onClick={() => void close('stash')}
              >
                Stash &amp; close
              </button>
              <button
                className="danger"
                disabled={busy}
                onClick={() => {
                  if (confirm('Discard ALL uncommitted changes in this worktree? This cannot be undone.')) {
                    void close('discard');
                  }
                }}
              >
                Discard &amp; close
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
