import { useCallback, useEffect, useState } from 'react';
import { api, ApiError } from '../api/rest';

interface GitStatus {
  branch: string;
  dirty: string[];
  upstream: string | null;
  ahead: number;
  behind: number;
}

interface LogEntry {
  hash: string;
  subject: string;
  author: string;
  date: string;
}

async function gitApi<T>(id: string, path: string, method = 'GET', body?: unknown): Promise<T> {
  return api.raw<T>(method, `/api/sessions/${id}/git/${path}`, body);
}

export default function GitPanel({ sessionId, onClose }: { sessionId: string; onClose: () => void }) {
  const [status, setStatus] = useState<GitStatus | null>(null);
  const [log, setLog] = useState<LogEntry[]>([]);
  const [diff, setDiff] = useState<string | null>(null);
  const [commitMessage, setCommitMessage] = useState('');
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [prUrl, setPrUrl] = useState('');

  const refresh = useCallback(async () => {
    setError('');
    try {
      setStatus(await gitApi<GitStatus>(sessionId, 'status'));
      setLog(await gitApi<LogEntry[]>(sessionId, 'log'));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }, [sessionId]);

  useEffect(() => { void refresh(); }, [refresh]);

  const run = async (label: string, fn: () => Promise<void>) => {
    setBusy(label);
    setError('');
    try {
      await fn();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    } finally {
      setBusy('');
    }
  };

  return (
    <div className="git-panel">
      <div className="git-head">
        <strong>⎇ {status?.branch ?? '…'}</strong>
        {status?.upstream && (
          <span className="chip">{status.upstream} {status.ahead > 0 ? `↑${status.ahead}` : ''}{status.behind > 0 ? ` ↓${status.behind}` : ''}</span>
        )}
        <span className="spacer" />
        <button onClick={() => void refresh()}>↻</button>
        <button onClick={onClose}>✕</button>
      </div>
      {error && <div className="error-text">{error}</div>}
      {prUrl && <div className="t-note info">PR created: <a href={prUrl} target="_blank" rel="noreferrer">{prUrl}</a></div>}

      <div className="git-section">
        <div className="git-label">changes ({status?.dirty.length ?? 0})</div>
        {status && status.dirty.length > 0 ? (
          <>
            <pre className="git-files">{status.dirty.join('\n')}</pre>
            <button onClick={() => void run('diff', async () => setDiff(diff === null ? (await gitApi<{ diff: string }>(sessionId, 'diff')).diff : null))}>
              {diff === null ? 'Show diff' : 'Hide diff'}
            </button>
            {diff !== null && <pre className="git-diff">{diff || '(no tracked changes)'}</pre>}
            <div className="row" style={{ display: 'flex', gap: 6, marginTop: 6 }}>
              <input
                style={{ flex: 1 }}
                placeholder="commit message"
                value={commitMessage}
                onChange={(e) => setCommitMessage(e.target.value)}
              />
              <button
                className="primary"
                disabled={!commitMessage.trim() || busy !== ''}
                onClick={() => void run('commit', async () => {
                  setStatus(await gitApi<GitStatus>(sessionId, 'commit', 'POST', { message: commitMessage.trim() }));
                  setCommitMessage('');
                  setDiff(null);
                  setLog(await gitApi<LogEntry[]>(sessionId, 'log'));
                })}
              >
                {busy === 'commit' ? '…' : 'Commit all'}
              </button>
            </div>
          </>
        ) : (
          <div style={{ color: 'var(--muted)', fontSize: 12.5 }}>worktree clean</div>
        )}
      </div>

      <div className="git-section">
        <div className="row" style={{ display: 'flex', gap: 6 }}>
          <button disabled={busy !== ''} onClick={() => void run('push', async () => { await gitApi(sessionId, 'push', 'POST', {}); await refresh(); })}>
            {busy === 'push' ? '…' : 'Push'}
          </button>
          <button
            disabled={busy !== ''}
            onClick={() => {
              const title = prompt('PR title:');
              if (!title) return;
              const body = prompt('PR description (optional):') ?? '';
              void run('pr', async () => {
                const r = await gitApi<{ url: string }>(sessionId, 'pr', 'POST', { title, body });
                setPrUrl(r.url);
                await refresh();
              });
            }}
          >
            {busy === 'pr' ? '…' : 'Open PR'}
          </button>
        </div>
      </div>

      <div className="git-section">
        <div className="git-label">recent commits</div>
        {log.map((entry) => (
          <div key={entry.hash} className="git-log-line">
            <code>{entry.hash}</code> {entry.subject} <span style={{ color: 'var(--muted)' }}>· {entry.date}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
