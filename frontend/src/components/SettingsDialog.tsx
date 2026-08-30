import { useEffect, useState } from 'react';
import { api } from '../api/rest';
import { getFontSize, getTheme, setFontSize, setTheme, type FontSize, type Theme } from '../prefs';
import type { ProviderView, Settings } from '../protocol';

export default function SettingsDialog({ onClose }: { onClose: () => void }) {
  const [theme, setThemeState] = useState<Theme>(getTheme());
  const [fontSize, setFontSizeState] = useState<FontSize>(getFontSize());
  const [settings, setSettings] = useState<Settings | null>(null);
  const [providers, setProviders] = useState<ProviderView[]>([]);
  const [specDraft, setSpecDraft] = useState('');
  const [ecosystemRootDraft, setEcosystemRootDraft] = useState('');
  const [pollIntervalDraft, setPollIntervalDraft] = useState('');
  const [skillsRootDraft, setSkillsRootDraft] = useState('');
  const [agentsRootDraft, setAgentsRootDraft] = useState('');
  const [syncIntervalDraft, setSyncIntervalDraft] = useState('');
  const [codexPricingDraft, setCodexPricingDraft] = useState('');
  const [codexPricingError, setCodexPricingError] = useState('');

  useEffect(() => {
    api.getSettings().then((s) => {
      setSettings(s);
      setSpecDraft(s.ticketImportSpec);
      setEcosystemRootDraft(s.ecosystemRoot);
      setPollIntervalDraft(String(s.prCheckPollIntervalSeconds));
      setSkillsRootDraft(s.librarySkillsRoot);
      setAgentsRootDraft(s.libraryAgentsRoot);
      setSyncIntervalDraft(String(s.librarySyncIntervalMinutes));
      setCodexPricingDraft(s.codexPricing);
    }).catch(() => setSettings(null));
    api.listProviders().then(setProviders).catch(() => setProviders([]));
  }, []);

  const toggleOAuth = () => {
    if (!settings) return;
    const next = { ...settings, linearOAuthEnabled: !settings.linearOAuthEnabled };
    setSettings(next);
    void api.updateSettings({ linearOAuthEnabled: next.linearOAuthEnabled }).catch(() => setSettings(settings));
  };

  const togglePrChecks = () => {
    if (!settings) return;
    const next = { ...settings, prChecksEnabled: !settings.prChecksEnabled };
    setSettings(next);
    void api.updateSettings({ prChecksEnabled: next.prChecksEnabled }).catch(() => setSettings(settings));
  };

  const savePollInterval = () => {
    if (!settings) return;
    const seconds = Number(pollIntervalDraft);
    if (!Number.isFinite(seconds) || seconds === settings.prCheckPollIntervalSeconds) return;
    void api.updateSettings({ prCheckPollIntervalSeconds: seconds })
      .then((s) => { setSettings(s); setPollIntervalDraft(String(s.prCheckPollIntervalSeconds)); })
      .catch(() => setPollIntervalDraft(String(settings.prCheckPollIntervalSeconds)));
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

  const saveSkillsRoot = () => {
    if (!settings || skillsRootDraft === settings.librarySkillsRoot) return;
    void api.updateSettings({ librarySkillsRoot: skillsRootDraft })
      .then((s) => { setSettings(s); setSkillsRootDraft(s.librarySkillsRoot); })
      .catch(() => setSkillsRootDraft(settings.librarySkillsRoot));
  };

  const saveAgentsRoot = () => {
    if (!settings || agentsRootDraft === settings.libraryAgentsRoot) return;
    void api.updateSettings({ libraryAgentsRoot: agentsRootDraft })
      .then((s) => { setSettings(s); setAgentsRootDraft(s.libraryAgentsRoot); })
      .catch(() => setAgentsRootDraft(settings.libraryAgentsRoot));
  };

  const saveSyncInterval = () => {
    if (!settings) return;
    const minutes = Number(syncIntervalDraft);
    if (!Number.isFinite(minutes) || minutes === settings.librarySyncIntervalMinutes) return;
    void api.updateSettings({ librarySyncIntervalMinutes: minutes })
      .then((s) => { setSettings(s); setSyncIntervalDraft(String(s.librarySyncIntervalMinutes)); })
      .catch(() => setSyncIntervalDraft(String(settings.librarySyncIntervalMinutes)));
  };

  const saveDefaultProvider = (id: string) => {
    if (!settings) return;
    const previous = settings.defaultProvider;
    setSettings({ ...settings, defaultProvider: id });
    void api.updateSettings({ defaultProvider: id }).catch(() => setSettings({ ...settings, defaultProvider: previous }));
  };

  const saveCodexPricing = () => {
    if (!settings || codexPricingDraft === settings.codexPricing) return;
    setCodexPricingError('');
    void api.updateSettings({ codexPricing: codexPricingDraft })
      .then((s) => { setSettings(s); setCodexPricingDraft(s.codexPricing); })
      .catch((e: unknown) => setCodexPricingError(e instanceof Error ? e.message : String(e)));
  };

  const toggleVectorize = () => {
    if (!settings) return;
    const next = { ...settings, libraryVectorize: !settings.libraryVectorize };
    setSettings(next);
    void api.updateSettings({ libraryVectorize: next.libraryVectorize }).catch(() => setSettings(settings));
  };

  const toggleLibrarySync = () => {
    if (!settings) return;
    const next = { ...settings, librarySyncEnabled: !settings.librarySyncEnabled };
    setSettings(next);
    void api.updateSettings({ librarySyncEnabled: next.librarySyncEnabled }).catch(() => setSettings(settings));
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

              <label>Default provider</label>
              <select value={settings.defaultProvider} onChange={(e) => saveDefaultProvider(e.target.value)}>
                {(providers.length ? providers.map((p) => p.id) : [settings.defaultProvider]).map((id) => (
                  <option key={id} value={id}>{id}</option>
                ))}
              </select>
            </div>

            <h3 style={{ margin: '18px 0 10px' }}>Codex</h3>
            <div className="form-grid">
              <label>Pricing</label>
              <textarea
                className="full"
                style={{ gridColumn: '2 / -1', fontFamily: 'monospace' }}
                rows={4}
                value={codexPricingDraft}
                onChange={(e) => setCodexPricingDraft(e.target.value)}
                onBlur={saveCodexPricing}
                title='per-model $-per-million-tokens estimate; Codex reports no per-turn USD itself. "default" is the fallback for a model with no specific row.'
              />
              {codexPricingError && <div className="error-text full" style={{ gridColumn: '2 / -1' }}>{codexPricingError}</div>}
            </div>

            <h3 style={{ margin: '18px 0 10px' }}>Skill library</h3>
            <div className="form-grid">
              <label>Skills folder</label>
              <input
                className="full"
                style={{ gridColumn: '2 / -1' }}
                value={skillsRootDraft}
                onChange={(e) => setSkillsRootDraft(e.target.value)}
                onBlur={saveSkillsRoot}
                title="managed skill folder — import destination and the root the create-dialog picker scans"
              />

              <label>Agents folder</label>
              <input
                className="full"
                style={{ gridColumn: '2 / -1' }}
                value={agentsRootDraft}
                onChange={(e) => setAgentsRootDraft(e.target.value)}
                onBlur={saveAgentsRoot}
                title="managed agent folder — import destination for agent assets"
              />

              <label>Vectorize</label>
              <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontWeight: 'normal' }}>
                <input
                  type="checkbox"
                  checked={settings.libraryVectorize}
                  disabled={!settings.voyageConfigured}
                  onChange={toggleVectorize}
                />
                {settings.voyageConfigured
                  ? 'embed content on import & sync for semantic search (Voyage AI)'
                  : 'requires CLAUDE_UI_VOYAGE_API_KEY (env var only)'}
              </label>

              <label>Source sync</label>
              <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontWeight: 'normal' }}>
                <input type="checkbox" checked={settings.librarySyncEnabled} onChange={toggleLibrarySync} />
                periodically re-check synced sources; update changed assets, archive removed ones
              </label>

              <label>Sync interval</label>
              <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                <input
                  type="number"
                  min={5}
                  style={{ width: 90 }}
                  value={syncIntervalDraft}
                  onChange={(e) => setSyncIntervalDraft(e.target.value)}
                  onBlur={saveSyncInterval}
                />
                minutes (minimum 5)
              </span>
            </div>

            <h3 style={{ margin: '18px 0 10px' }}>PR checks</h3>
            <div className="form-grid">
              <label>Enabled</label>
              <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontWeight: 'normal' }}>
                <input type="checkbox" checked={settings.prChecksEnabled} onChange={togglePrChecks} />
                poll GitHub for CI status on sessions with an open PR, and notify when it resolves
              </label>

              <label>Poll interval</label>
              <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                <input
                  type="number"
                  min={30}
                  style={{ width: 90 }}
                  value={pollIntervalDraft}
                  onChange={(e) => setPollIntervalDraft(e.target.value)}
                  onBlur={savePollInterval}
                />
                seconds (minimum 30)
              </span>
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
