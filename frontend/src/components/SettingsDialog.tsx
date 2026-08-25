import { useEffect, useState } from 'react';
import { api } from '../api/rest';
import { getFontSize, getTheme, setFontSize, setTheme, type FontSize, type Theme } from '../prefs';
import type { Settings } from '../protocol';

export default function SettingsDialog({ onClose }: { onClose: () => void }) {
  const [theme, setThemeState] = useState<Theme>(getTheme());
  const [fontSize, setFontSizeState] = useState<FontSize>(getFontSize());
  const [settings, setSettings] = useState<Settings | null>(null);
  const [specDraft, setSpecDraft] = useState('');
  const [ecosystemRootDraft, setEcosystemRootDraft] = useState('');

  useEffect(() => {
    api.getSettings().then((s) => {
      setSettings(s);
      setSpecDraft(s.ticketImportSpec);
      setEcosystemRootDraft(s.ecosystemRoot);
    }).catch(() => setSettings(null));
  }, []);

  const toggleOAuth = () => {
    if (!settings) return;
    const next = { ...settings, linearOAuthEnabled: !settings.linearOAuthEnabled };
    setSettings(next);
    void api.updateSettings({ linearOAuthEnabled: next.linearOAuthEnabled }).catch(() => setSettings(settings));
  };

  const saveSpec = () => {
    if (!settings || specDraft === settings.ticketImportSpec) return;
    void api.updateSettings({ ticketImportSpec: specDraft })
      .then(setSettings)
      .catch(() => setSpecDraft(settings.ticketImportSpec));
  };

  const saveEcosystemRoot = () => {
    if (!settings || ecosystemRootDraft === settings.ecosystemRoot) return;
    void api.updateSettings({ ecosystemRoot: ecosystemRootDraft })
      .then(setSettings)
      .catch(() => setEcosystemRootDraft(settings.ecosystemRoot));
  };

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h2>Settings</h2>
        <h3 style={{ margin: '0 0 10px' }}>Appearance</h3>
        <div className="form-grid">
          <label>Theme</label>
          <select
            value={theme}
            onChange={(e) => {
              const next = e.target.value as Theme;
              setThemeState(next);
              setTheme(next);
            }}
          >
            <option value="system">System</option>
            <option value="light">Light</option>
            <option value="dark">Dark</option>
          </select>
          <label>Font size</label>
          <select
            value={fontSize}
            onChange={(e) => {
              const next = e.target.value as FontSize;
              setFontSizeState(next);
              setFontSize(next);
            }}
          >
            <option value="small">Small</option>
            <option value="medium">Medium</option>
            <option value="large">Large</option>
          </select>
        </div>

        {settings && (
          <>
            <h3 style={{ margin: '18px 0 10px' }}>Sessions</h3>
            <div className="form-grid">
              <label>Ecosystem root</label>
              <input
                className="full"
                style={{ gridColumn: '2 / -1' }}
                value={ecosystemRootDraft}
                onChange={(e) => setEcosystemRootDraft(e.target.value)}
                onBlur={saveEcosystemRoot}
                placeholder="parent folder of your services; empty = no default wider context"
                title="default read-only context folder + service discovery root, overridable per session"
              />
            </div>

            <h3 style={{ margin: '18px 0 10px' }}>Linear integration</h3>
            <div className="form-grid">
              <label>API key</label>
              <span style={{ color: 'var(--muted)', fontSize: 12.5 }}>
                {settings.linearApiKeyConfigured
                  ? 'configured via CLAUDE_UI_LINEAR_API_KEY'
                  : 'not set (env var only — see docs/DEPLOY.md)'}
              </span>

              <label>OAuth</label>
              <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontWeight: 'normal' }}>
                <input type="checkbox" checked={settings.linearOAuthEnabled} onChange={toggleOAuth} />
                use the ambient <code>claude</code> CLI's cached OAuth credential
              </label>

              <label>Branch naming</label>
              <textarea
                className="full"
                style={{ gridColumn: '2 / -1' }}
                rows={3}
                value={specDraft}
                onChange={(e) => setSpecDraft(e.target.value)}
                onBlur={saveSpec}
                placeholder={'optional guidance appended to the ticket-import prompt, e.g. '
                  + '"keep the ticket number uppercase" or "format as feat(TICKET)-description / fix(TICKET)-description"'}
              />
            </div>
          </>
        )}

        <div className="actions">
          <button onClick={onClose}>Close</button>
        </div>
      </div>
    </div>
  );
}
