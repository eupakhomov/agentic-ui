import { useState } from 'react';
import { api, ApiError } from '../api/rest';

async function gitApi<T>(id: string, path: string, method = 'GET', body?: unknown): Promise<T> {
  return api.raw<T>(method, `/api/sessions/${id}/git/${path}`, body);
}

export default function PrDialog({
  sessionId,
  defaultTitle,
  onClose,
  onCreated,
}: {
  sessionId: string;
  defaultTitle?: string;
  onClose: () => void;
  onCreated: (url: string) => void;
}) {
  const [title, setTitle] = useState(defaultTitle ?? '');
  const [body, setBody] = useState('');
  const [busy, setBusy] = useState<'' | 'suggest' | 'create'>('');
  const [error, setError] = useState('');

  const suggest = async () => {
    setError('');
    setBusy('suggest');
    try {
      const r = await gitApi<{ title: string; body: string }>(sessionId, 'pr/suggest', 'POST');
      setTitle(r.title);
      setBody(r.body);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    } finally {
      setBusy('');
    }
  };

  const create = async () => {
    setError('');
    setBusy('create');
    try {
      const r = await gitApi<{ url: string }>(sessionId, 'pr', 'POST', { title: title.trim(), body });
      onCreated(r.url);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
      setBusy('');
    }
  };

  return (
    <div className="modal-backdrop" onClick={() => { if (busy === '') onClose(); }}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h2>Open PR</h2>
        <div className="form-grid">
          <label>Title</label>
          <input
            className="full"
            style={{ gridColumn: '2 / -1' }}
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            placeholder="PR title"
            autoFocus
          />
          <label>Description</label>
          <textarea
            className="full"
            style={{ gridColumn: '2 / -1' }}
            rows={8}
            value={body}
            onChange={(e) => setBody(e.target.value)}
            placeholder="optional — markdown supported"
          />
        </div>
        {error && <div className="error-text" style={{ marginTop: 10 }}>{error}</div>}
        <div className="actions">
          <button disabled={busy !== ''} onClick={() => void suggest()}>
            {busy === 'suggest' ? '…' : '✨ Suggest'}
          </button>
          <span className="spacer" />
          <button disabled={busy !== ''} onClick={onClose}>Cancel</button>
          <button className="primary" disabled={busy !== '' || !title.trim()} onClick={() => void create()}>
            {busy === 'create' ? '…' : 'Create PR'}
          </button>
        </div>
      </div>
    </div>
  );
}
