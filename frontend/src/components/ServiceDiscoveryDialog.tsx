import { useEffect, useState } from 'react';
import { api } from '../api/rest';
import type { ServiceProfileView } from '../protocol';

function splitTags(text: string): string[] {
  return text.split(',').map((t) => t.trim()).filter(Boolean);
}

export default function ServiceDiscoveryDialog({ onClose }: { onClose: () => void }) {
  const [services, setServices] = useState<ServiceProfileView[] | null>(null);
  const [error, setError] = useState('');
  const [busyPath, setBusyPath] = useState<string | null>(null);
  const [scanning, setScanning] = useState(false);
  const [scanProgress, setScanProgress] = useState<{ done: number; total: number } | null>(null);
  const [editingPath, setEditingPath] = useState<string | null>(null);
  const [draft, setDraft] = useState({ description: '', tags: '' });

  const refresh = () => {
    api.serviceDiscoveryServices().then(setServices).catch((e) => setError(String((e as Error).message ?? e)));
  };

  useEffect(refresh, []);

  const rediscover = (path: string) => {
    setBusyPath(path);
    setError('');
    api.serviceDiscoveryRediscover(path)
      .then((updated) => {
        setServices((prev) => (prev ?? []).map((s) => (s.path === path ? updated : s)));
        setBusyPath(null);
      })
      .catch((e) => { setError(String((e as Error).message ?? e)); setBusyPath(null); });
  };

  const scanEcosystem = async () => {
    const stale = (services ?? []).filter((s) => s.stale);
    if (stale.length === 0) return;
    setScanning(true);
    setError('');
    setScanProgress({ done: 0, total: stale.length });
    for (let i = 0; i < stale.length; i++) {
      try {
        const updated = await api.serviceDiscoveryRediscover(stale[i]!.path);
        setServices((prev) => (prev ?? []).map((s) => (s.path === updated.path ? updated : s)));
      } catch (e) {
        setError(String((e as Error).message ?? e));
      }
      setScanProgress({ done: i + 1, total: stale.length });
    }
    setScanning(false);
    setScanProgress(null);
  };

  const startEdit = (s: ServiceProfileView) => {
    setEditingPath(s.path);
    setDraft({ description: s.description ?? '', tags: s.tags.join(', ') });
    setError('');
  };

  const saveEdit = (path: string) => {
    setBusyPath(path);
    api.serviceDiscoveryUpdate(path, { description: draft.description, tags: splitTags(draft.tags) })
      .then((updated) => {
        setServices((prev) => (prev ?? []).map((s) => (s.path === path ? updated : s)));
        setEditingPath(null);
        setBusyPath(null);
      })
      .catch((e) => { setError(String((e as Error).message ?? e)); setBusyPath(null); });
  };

  const staleCount = (services ?? []).filter((s) => s.stale).length;

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal wide" onClick={(e) => e.stopPropagation()}>
        <h2>Service discovery</h2>

        <div className="usage-filters">
          <div className="note" style={{ flex: 1 }}>
            {services === null ? 'loading…' : `${services.length} service(s) under the ecosystem root`}
            {staleCount > 0 && ` — ${staleCount} missing or stale`}
          </div>
          <button onClick={refresh} disabled={scanning}>Refresh</button>
          <button
            className="primary"
            disabled={scanning || staleCount === 0}
            onClick={() => void scanEcosystem()}
          >
            {scanning ? `Scanning ${scanProgress?.done ?? 0}/${scanProgress?.total ?? 0}…` : 'Scan ecosystem now'}
          </button>
        </div>

        {error && <div className="error-text">{error}</div>}

        <div className="stale-list">
          {services !== null && services.length === 0 && (
            <div style={{ color: 'var(--muted)' }}>no ecosystem root configured, or no git repos under it</div>
          )}
          {(services ?? []).map((s) => (
            <div key={s.path} className="stale-row" style={{ flexDirection: 'column', alignItems: 'stretch', gap: 6 }}>
              <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                <span className="name">{s.name}</span>
                {s.discoveredAt
                  ? (s.stale
                    ? <span className="chip" style={{ color: 'var(--red)' }}>stale</span>
                    : <span className="chip">fresh</span>)
                  : <span className="chip" style={{ color: 'var(--red)' }}>not discovered</span>}
                {s.discoveredAt && <span className="idle">{new Date(s.discoveredAt).toLocaleString()}</span>}
                <span style={{ flex: 1 }} />
                {editingPath !== s.path && (
                  <>
                    <button disabled={busyPath === s.path || scanning} onClick={() => startEdit(s)}>Edit</button>
                    <button disabled={busyPath === s.path || scanning} onClick={() => rediscover(s.path)}>
                      {busyPath === s.path ? 'Rediscovering…' : 'Rediscover'}
                    </button>
                  </>
                )}
              </div>
              <div className="note">{s.path}</div>

              {editingPath === s.path ? (
                <div className="form-grid">
                  <label>Description</label>
                  <textarea
                    className="full"
                    rows={4}
                    value={draft.description}
                    onChange={(e) => setDraft((d) => ({ ...d, description: e.target.value }))}
                  />
                  <label>Tags</label>
                  <input
                    placeholder="comma, separated"
                    value={draft.tags}
                    onChange={(e) => setDraft((d) => ({ ...d, tags: e.target.value }))}
                  />
                  <span className="full" style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
                    <button disabled={busyPath === s.path} onClick={() => setEditingPath(null)}>Cancel</button>
                    <button
                      className="primary"
                      disabled={busyPath === s.path || !draft.description.trim()}
                      onClick={() => saveEdit(s.path)}
                    >
                      {busyPath === s.path ? 'Saving…' : 'Save'}
                    </button>
                  </span>
                </div>
              ) : (
                <>
                  <div className="note">{s.description ?? '(no description yet)'}</div>
                  {s.tags.length > 0 && (
                    <div className="chip-row">
                      {s.tags.map((t) => <span key={t} className="chip">{t}</span>)}
                    </div>
                  )}
                </>
              )}
            </div>
          ))}
        </div>

        <div className="actions">
          <button onClick={onClose}>Close</button>
        </div>
      </div>
    </div>
  );
}
