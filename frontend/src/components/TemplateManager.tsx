import { useEffect, useState } from 'react';
import { api, ApiError } from '../api/rest';
import type { Template } from '../protocol';

export default function TemplateManager({ onClose }: { onClose: () => void }) {
  const [templates, setTemplates] = useState<Template[]>([]);
  const [editing, setEditing] = useState<Template | null>(null);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [config, setConfig] = useState('{\n  "model": "sonnet",\n  "permissionMode": "default"\n}');
  const [error, setError] = useState('');

  const load = () => api.listTemplates().then(setTemplates).catch(() => setTemplates([]));
  useEffect(() => { void load(); }, []);

  const startEdit = (t: Template | null) => {
    setEditing(t);
    setName(t?.name ?? '');
    setDescription(t?.description ?? '');
    setConfig(t ? JSON.stringify(t.config, null, 2) : '{\n  "model": "sonnet",\n  "permissionMode": "default"\n}');
    setError('');
  };

  const save = async () => {
    setError('');
    try {
      const body = { name, description, config: JSON.parse(config) as Record<string, unknown> };
      if (editing) await api.updateTemplate(editing.id, body);
      else await api.createTemplate(body);
      setEditing(null);
      setName('');
      await load();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  };

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h2>Session templates</h2>
        <table style={{ width: '100%', borderCollapse: 'collapse', marginBottom: 14 }}>
          <tbody>
            {templates.map((t) => (
              <tr key={t.id} style={{ borderBottom: '1px solid var(--border)' }}>
                <td style={{ padding: '6px 4px', fontWeight: 600 }}>{t.name}</td>
                <td style={{ padding: '6px 4px', color: 'var(--muted)' }}>{t.description}</td>
                <td style={{ padding: '6px 4px', textAlign: 'right', whiteSpace: 'nowrap' }}>
                  <button onClick={() => startEdit(t)}>Edit</button>{' '}
                  <button className="danger" onClick={() => void api.deleteTemplate(t.id).then(load)}>Delete</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="form-grid">
          <label>Name</label>
          <input value={name} onChange={(e) => setName(e.target.value)} />
          <label>Description</label>
          <input value={description} onChange={(e) => setDescription(e.target.value)} />
          <label>Config JSON</label>
          <textarea
            style={{ gridColumn: '2 / -1', fontFamily: 'monospace' }}
            rows={8}
            value={config}
            onChange={(e) => setConfig(e.target.value)}
          />
        </div>
        {error && <div className="error-text" style={{ marginTop: 8 }}>{error}</div>}
        <div className="actions">
          {editing && <button onClick={() => startEdit(null)}>New instead</button>}
          <button onClick={onClose}>Close</button>
          <button className="primary" disabled={!name.trim()} onClick={() => void save()}>
            {editing ? 'Update' : 'Create'} template
          </button>
        </div>
      </div>
    </div>
  );
}
