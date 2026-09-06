import { useEffect, useRef, useState } from 'react';
import { api, ApiError } from '../api/rest';
import type { ServicesResponse, TicketSummary } from '../protocol';
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
  const [repoPath, setRepoPath] = useState('');
  const [ticketImportEnabled, setTicketImportEnabled] = useState(false);

  const [ticketRef, setTicketRef] = useState('');
  const [branchName, setBranchName] = useState('');
  const [prompt, setPrompt] = useState('');
  const [resolvedTicketRef, setResolvedTicketRef] = useState<string | null>(null);
  const [recommendedModel, setRecommendedModel] = useState<string | null>(null);
  const [importBusy, setImportBusy] = useState(false);
  const [importError, setImportError] = useState('');
  const importAbortRef = useRef<AbortController | null>(null);
  const [showTicketPicker, setShowTicketPicker] = useState(false);
  const [recentTickets, setRecentTickets] = useState<TicketSummary[] | null>(null);
  const [pickerBusy, setPickerBusy] = useState(false);
  const [pickerError, setPickerError] = useState('');
  const pickerAbortRef = useRef<AbortController | null>(null);

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
      setRepoPath(info.defaultRepoPath);
    }).catch(() => setServicesInfo(null));
    api.ticketImportEnabled().then((r) => setTicketImportEnabled(r.enabled)).catch(() => setTicketImportEnabled(false));
  }, []);

  const importTicket = async (refOverride?: string) => {
    const ref = (refOverride ?? ticketRef).trim();
    setImportError('');
    setImportBusy(true);
    const controller = new AbortController();
    importAbortRef.current = controller;
    const safetyNet = setTimeout(() => controller.abort('timeout'), 50_000);
    try {
      const result = await api.importTicket(ref, controller.signal);
      setBranchName(result.branchName);
      setPrompt(result.prompt);
      setResolvedTicketRef(result.ticketRef);
      setRecommendedModel(result.recommendedModel);
    } catch (e) {
      if (controller.signal.aborted) {
        setImportError(controller.signal.reason === 'user' ? 'cancelled' : 'timed out waiting for a response (50s)');
      } else {
        setImportError(e instanceof ApiError ? e.message : String(e));
      }
    } finally {
      clearTimeout(safetyNet);
      importAbortRef.current = null;
      setImportBusy(false);
    }
  };

  const browseRecentTickets = async () => {
    setPickerError('');
    setPickerBusy(true);
    setShowTicketPicker(true);
    const controller = new AbortController();
    pickerAbortRef.current = controller;
    const safetyNet = setTimeout(() => controller.abort('timeout'), 50_000);
    try {
      setRecentTickets(await api.listRecentTickets(controller.signal));
    } catch (e) {
      if (controller.signal.aborted) {
        setPickerError(controller.signal.reason === 'user' ? 'cancelled' : 'timed out waiting for a response (50s)');
      } else {
        setPickerError(e instanceof ApiError ? e.message : String(e));
      }
    } finally {
      clearTimeout(safetyNet);
      pickerAbortRef.current = null;
      setPickerBusy(false);
    }
  };

  const pickTicket = (ref: string) => {
    setShowTicketPicker(false);
    setTicketRef(ref);
    void importTicket(ref);
  };

  const create = async () => {
    setError('');
    setBusy(true);
    try {
      const [lastConfig, branches] = await Promise.all([api.lastSessionConfig(), api.branches(repoPath)]);
      const overrides: Record<string, unknown> = { ...lastConfig };
      if (resolvedTicketRef) overrides['ticketRef'] = resolvedTicketRef;
      if (recommendedModel && ['sonnet', 'opus', 'haiku'].includes(recommendedModel)) {
        overrides['model'] = recommendedModel;
      }
      const baseBranch = branches.includes('main') ? 'main' : (branches[0] ?? 'main');
      const created = await api.createSession({
        name: branchName,
        branch: branchName,
        baseBranch,
        repoPath,
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

  const ready = !!repoPath && !!branchName.trim() && !!prompt.trim();

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
            <select value={repoPath} onChange={(e) => setRepoPath(e.target.value)}>
              {(servicesInfo?.services ?? []).map((s) => (
                <option key={s.path} value={s.path}>{s.name}</option>
              ))}
            </select>

            <label>Ticket</label>
            <div className="row" style={{ display: 'flex', gap: 6 }}>
              <input
                ref={ticketInputRef}
                style={{ flex: 1 }}
                value={ticketRef}
                onChange={(e) => setTicketRef(e.target.value)}
                placeholder="Linear ticket, e.g. ENG-123 or a URL — leave blank to browse tickets assigned to you"
                disabled={importBusy || pickerBusy}
              />
              {importBusy || pickerBusy ? (
                <button onClick={() => { importAbortRef.current?.abort('user'); pickerAbortRef.current?.abort('user'); }}>
                  Cancel
                </button>
              ) : (
                <button onClick={() => void (ticketRef.trim() ? importTicket() : browseRecentTickets())}>
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
        onClose={() => { pickerAbortRef.current?.abort('user'); setShowTicketPicker(false); }}
      />
    )}
    </>
  );
}
