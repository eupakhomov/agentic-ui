import { useEffect, useRef, useState } from 'react';
import { api, ApiError } from '../api/rest';
import { pickDefaultBranch, type ProviderView, type ServicesResponse } from '../protocol';
import { useTicketImport, type TicketImportOutcome } from '../hooks/useTicketImport';
import TicketPickerDialog from './TicketPickerDialog';

/**
 * Minimal create flow: service + ticket only. Everything else (model, permission mode,
 * tools, MCP servers, skills, agents, instructions, ecosystem, ...) is copied from
 * whatever the most recently created session had configured (SessionService.lastSessionConfig,
 * the same "snapshot a session's effective config" logic the per-session Duplicate button uses).
 */
export default function QuickSessionDialog({
  onCreated,
  onCancel,
}: {
  onCreated: (id: string, draftInput?: string) => void;
  onCancel: () => void;
}) {
  const [servicesInfo, setServicesInfo] = useState<ServicesResponse | null>(null);
  const [servicePath, setServicePath] = useState('');
  const [ticketImportEnabled, setTicketImportEnabled] = useState(false);
  // recommendedModel is generated against the *default* provider's catalog (TicketImportService
  // uses SettingsService.defaultProvider()), which is what a new session created here also gets
  // absent an explicit override in lastSessionConfig — see ModelCatalog (P3).
  const [defaultProviderModelIds, setDefaultProviderModelIds] = useState<string[]>([]);

  const [branchName, setBranchName] = useState('');
  const [prompt, setPrompt] = useState('');
  const [resolvedTicketRef, setResolvedTicketRef] = useState<string | null>(null);
  const [recommendedModel, setRecommendedModel] = useState<string | null>(null);

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const ticketInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    const raf = requestAnimationFrame(() => ticketInputRef.current?.focus());
    return () => cancelAnimationFrame(raf);
  }, []);

  useEffect(() => {
    api.services().then((info) => {
      setServicesInfo(info);
      setServicePath(info.defaultRepoPath);
    }).catch(() => setServicesInfo(null));
    api.ticketImportEnabled().then((r) => setTicketImportEnabled(r.enabled)).catch(() => setTicketImportEnabled(false));
    Promise.all([api.getSettings(), api.listProviders()]).then(([settings, providers]) => {
      const models = providers.find((p: ProviderView) => p.id === settings.defaultProvider)?.capabilities.models ?? [];
      setDefaultProviderModelIds(models.map((m) => m.id));
    }).catch(() => setDefaultProviderModelIds([]));
  }, []);

  const ticketImport = useTicketImport(defaultProviderModelIds);
  const { ticketRef, importBusy, importError, showTicketPicker, setShowTicketPicker,
    recentTickets, pickerBusy, pickerError } = ticketImport;

  const applyImportOutcome = (outcome: TicketImportOutcome) => {
    setBranchName(outcome.branchName);
    setPrompt(outcome.prompt);
    setResolvedTicketRef(outcome.ticketRef);
    setRecommendedModel(outcome.recommendedModel);
  };
  const importTicket = (refOverride?: string) => ticketImport.importTicket(refOverride, applyImportOutcome);
  const pickTicket = (ref: string) => ticketImport.pickTicket(ref, applyImportOutcome);

  // the picker's value is the service's own path; its repoPath (the git root) is what branches()
  // and the actual worktree source need (docs/plan/phase-11-monorepo.md Step 6)
  const selectedService = (servicesInfo?.services ?? []).find((s) => s.path === servicePath) ?? null;
  const selectedRepoPath = selectedService?.repoPath || servicePath;

  const create = async () => {
    setError('');
    setBusy(true);
    try {
      const [lastConfig, branches] = await Promise.all([api.lastSessionConfig(), api.branches(selectedRepoPath)]);
      const overrides: Record<string, unknown> = { ...lastConfig };
      if (resolvedTicketRef) overrides['ticketRef'] = resolvedTicketRef;
      if (recommendedModel) overrides['model'] = recommendedModel;
      const baseBranch = pickDefaultBranch(branches);
      const created = await api.createSession({
        name: branchName,
        branch: branchName,
        baseBranch,
        repoPath: selectedRepoPath,
        servicePath,
        templateId: null,
        overrides,
        syncBaseBranch: true,
      });
      onCreated(created.id, prompt.trim() || undefined);
    } catch (e) {
      setError(e instanceof ApiError ? `${e.status}: ${e.message}` : String(e));
    } finally {
      setBusy(false);
    }
  };

  const ready = !!servicePath && !!branchName.trim() && !!prompt.trim();

  return (
    <>
    <div className="modal-backdrop" onClick={() => { if (!busy && !importBusy) onCancel(); }}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h2>Quick session</h2>
        <p style={{ color: 'var(--muted)' }}>
          Model, permissions, MCP servers, skills, and agents are copied from your most recently
          created session.
        </p>
        {!ticketImportEnabled ? (
          <div className="error-text">
            Linear ticket import isn't configured (Settings → Linear integration) — use the full New
            Session dialog instead.
          </div>
        ) : (
          <div className="form-grid">
            <label>Service</label>
            <select value={servicePath} onChange={(e) => setServicePath(e.target.value)}>
              {(servicesInfo?.services ?? []).map((s) => (
                <option key={s.path} value={s.path}>{s.name}{s.monorepo ? ' · monorepo' : ''}</option>
              ))}
            </select>

            <label>Ticket</label>
            <div className="row" style={{ display: 'flex', gap: 6 }}>
              <input
                ref={ticketInputRef}
                style={{ flex: 1 }}
                value={ticketRef}
                onChange={(e) => ticketImport.setTicketRef(e.target.value)}
                placeholder="Linear ticket, e.g. ENG-123 or a URL — leave blank to browse tickets assigned to you"
                disabled={importBusy || pickerBusy}
              />
              {importBusy || pickerBusy ? (
                <button onClick={() => ticketImport.cancel()}>
                  Cancel
                </button>
              ) : (
                <button onClick={() => void (ticketRef.trim() ? importTicket() : ticketImport.browseRecentTickets())}>
                  {ticketRef.trim() ? 'Fetch' : 'Browse'}
                </button>
              )}
            </div>
            {importBusy && (
              <div className="full" style={{ gridColumn: '2 / -1', color: 'var(--muted)', fontSize: 12.5 }}>
                fetching from Linear — can take up to 45s on the first call (spinning up the system session)…
              </div>
            )}
            {importError && <div className="error-text full" style={{ gridColumn: '2 / -1' }}>{importError}</div>}

            {branchName && (
              <>
                <label>Branch</label>
                <div className="full" style={{ gridColumn: '2 / -1', color: 'var(--muted)' }}>{branchName}</div>

                <label>Prompt</label>
                <textarea
                  style={{ gridColumn: '2 / -1' }}
                  rows={3}
                  value={prompt}
                  onChange={(e) => setPrompt(e.target.value)}
                />
              </>
            )}
          </div>
        )}
        {error && <div className="error-text" style={{ marginTop: 10 }}>{error}</div>}
        <div className="actions">
          <button onClick={onCancel}>Cancel</button>
          <button className="primary" disabled={busy || !ready} onClick={() => void create()}>
            {busy ? 'Creating…' : 'Create'}
          </button>
        </div>
      </div>
    </div>
    {showTicketPicker && (
      <TicketPickerDialog
        tickets={recentTickets}
        busy={pickerBusy}
        error={pickerError}
        onPick={pickTicket}
        onClose={() => { ticketImport.cancel(); setShowTicketPicker(false); }}
      />
    )}
    </>
  );
}
