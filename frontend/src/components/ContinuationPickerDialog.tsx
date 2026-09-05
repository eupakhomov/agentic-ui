import { useMemo, useState } from 'react';
import type { SessionSummary } from '../protocol';

function formatDate(iso: string): string {
  try {
    return new Date(iso).toLocaleString();
  } catch {
    return iso;
  }
}

export default function ContinuationPickerDialog({
  sessions,
  busy,
  error,
  fullTranscript,
  onToggleFullTranscript,
  onPick,
  onClose,
}: {
  sessions: SessionSummary[] | null;
  busy: boolean;
  error: string;
  fullTranscript: boolean;
  onToggleFullTranscript: (v: boolean) => void;
  onPick: (session: SessionSummary) => void;
  onClose: () => void;
}) {
  const [query, setQuery] = useState('');

  const filtered = useMemo(() => {
    const list = sessions ?? [];
    const q = query.trim().toLowerCase();
    const matched = q
      ? list.filter((s) => s.name.toLowerCase().includes(q) || s.branch.toLowerCase().includes(q))
      : list;
    return [...matched]
      .sort((a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime())
      .slice(0, 50);
  }, [sessions, query]);

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal" style={{ width: 'min(640px, 92vw)' }} onClick={(e) => e.stopPropagation()}>
        <h2>Continue from…</h2>
        <input
          style={{ width: '100%', marginBottom: 10 }}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="filter by name or branch…"
          autoFocus
        />
        <label style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 10, fontSize: 12.5, color: 'var(--muted)' }}>
          <input type="checkbox" checked={fullTranscript} onChange={(e) => onToggleFullTranscript(e.target.checked)} />
          inject the full transcript instead of an AI-summarized handoff brief
        </label>
        {busy && <div style={{ color: 'var(--muted)', fontSize: 12.5 }}>loading recent sessions…</div>}
        {error && <div className="error-text">{error}</div>}
        {!busy && !error && filtered.length === 0 && (
          <div style={{ color: 'var(--muted)', fontSize: 12.5 }}>no sessions found</div>
        )}
        {!busy && !error && filtered.length > 0 && (
          <div className="continuation-list">
            {filtered.map((s) => {
              const pruned = s.lastSeq === 0;
              return (
                <button
                  key={s.id}
                  className="continuation-row"
                  disabled={pruned}
                  title={pruned ? 'journal pruned — nothing to hand off' : undefined}
                  onClick={() => onPick(s)}
                >
                  <span className={`dot ${s.state}`} />
                  <span className="continuation-name">{s.name}</span>
                  <span className="chip">{s.provider}</span>
                  <span className="chip" title={s.repoPath}>{s.repoPath.split('/').pop()}</span>
                  <span className="chip">{s.state}</span>
                  <span className="chip">${s.costToDate.toFixed(3)}</span>
                  <span className="continuation-date">{formatDate(s.updatedAt)}</span>
                  {pruned && <span className="continuation-pruned">journal pruned</span>}
                </button>
              );
            })}
          </div>
        )}
        <div className="actions">
          <button onClick={onClose}>Close</button>
        </div>
      </div>
    </div>
  );
}
