import { useEffect, useMemo, useRef, useState } from 'react';
import { api, ApiError } from '../api/rest';
import { placeholdersOf, type AssetKind, type LibraryAsset, type ServicesResponse, type Settings, type Template, type TicketSummary } from '../protocol';
import AssetPickerDialog from './AssetPickerDialog';
import TicketPickerDialog from './TicketPickerDialog';

export default function CreateSessionDialog({
  onCreated,
  onCancel,
}: {
  onCreated: (id: string, draftInput?: string) => void;
  onCancel: () => void;
}) {
  const [servicesInfo, setServicesInfo] = useState<ServicesResponse | null>(null);
  const [branches, setBranches] = useState<string[]>([]);
  const [templates, setTemplates] = useState<Template[]>([]);
  const [settings, setSettings] = useState<Settings | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [advancedOpen, setAdvancedOpen] = useState(false);

  const [name, setName] = useState('');
  const [repoPath, setRepoPath] = useState('');
  const [branch, setBranch] = useState('');
  const [baseBranch, setBaseBranch] = useState('main');
  const [syncBaseBranch, setSyncBaseBranch] = useState(true);
  const [templateId, setTemplateId] = useState('');
  const [model, setModel] = useState('sonnet');
  const [recommendedModel, setRecommendedModel] = useState<string | null>(null);
  const [permissionMode, setPermissionMode] = useState('acceptEdits');
  const [thinking, setThinking] = useState('');
  const [effort, setEffort] = useState('');
  const [instructions, setInstructions] = useState('');
  const [ecosystemPath, setEcosystemPath] = useState('');
  const [selectedSkillAssets, setSelectedSkillAssets] = useState<Map<string, LibraryAsset>>(new Map());
  const [selectedAgentAssets, setSelectedAgentAssets] = useState<Map<string, LibraryAsset>>(new Map());
  const [extraSkill, setExtraSkill] = useState('');
  const [extraAgent, setExtraAgent] = useState('');
  const [assetPickerKind, setAssetPickerKind] = useState<AssetKind | null>(null);
  const [kickoffValues, setKickoffValues] = useState<Record<string, string>>({});
  const [advanced, setAdvanced] = useState('');
  const [initialPrompt, setInitialPrompt] = useState('');
  const [ticketRef, setTicketRef] = useState('');
  // the canonical ref returned by import (e.g. "ENG-123"), distinct from the raw text the user
  // typed above (which may be a pasted URL) — this is what gets persisted on the session
  const [resolvedTicketRef, setResolvedTicketRef] = useState<string | null>(null);
  const [ticketImportEnabled, setTicketImportEnabled] = useState(false);
  // ticket-derived prompts land unsent in the new session's compose box (reviewed & sent by
  // hand there) instead of auto-firing as a kickoff turn the moment the sidecar is ready
  const [promptFromTicket, setPromptFromTicket] = useState(false);
  const [importBusy, setImportBusy] = useState(false);
  const [importError, setImportError] = useState('');
  const importAbortRef = useRef<AbortController | null>(null);
  const [showTicketPicker, setShowTicketPicker] = useState(false);
  const [recentTickets, setRecentTickets] = useState<TicketSummary[] | null>(null);
  const [pickerBusy, setPickerBusy] = useState(false);
  const [pickerError, setPickerError] = useState('');
  const pickerAbortRef = useRef<AbortController | null>(null);

  useEffect(() => {
    api.services().then((info) => {
      setServicesInfo(info);
      setRepoPath(info.defaultRepoPath);
      setEcosystemPath(info.ecosystemRoot);
    }).catch(() => setServicesInfo(null));
    api.listTemplates().then(setTemplates).catch(() => setTemplates([]));
    api.getSettings().then(setSettings).catch(() => setSettings(null));
    api.ticketImportEnabled().then((r) => setTicketImportEnabled(r.enabled)).catch(() => setTicketImportEnabled(false));
  }, []);

  const importTicket = async (refOverride?: string) => {
    const ref = (refOverride ?? ticketRef).trim();
    setImportError('');
    setImportBusy(true);
    const controller = new AbortController();
    importAbortRef.current = controller;
    // backend already bounds the underlying system-session turn to 45s (see
    // SessionService.runSystemTurn / TicketImportService) and always resolves with a
    // real error by then; this is a client-side safety net so the button can never
    // get stuck forever even if that assumption turns out wrong in some environment
    const safetyNet = setTimeout(() => controller.abort('timeout'), 50_000);
    console.log('[claude-ui] ticket import: fetching', ref);
    const started = performance.now();
    try {
      const result = await api.importTicket(ref, controller.signal);
      console.log('[claude-ui] ticket import: succeeded in', Math.round(performance.now() - started), 'ms', result);
      setBranch(result.branchName);
      setInitialPrompt(result.prompt);
      setPromptFromTicket(true);
      setResolvedTicketRef(result.ticketRef);
      if (result.recommendedModel && ['sonnet', 'opus', 'haiku'].includes(result.recommendedModel)) {
        setModel(result.recommendedModel);
        setRecommendedModel(result.recommendedModel);
      }
    } catch (e) {
      const elapsed = Math.round(performance.now() - started);
      if (controller.signal.aborted) {
        console.error('[claude-ui] ticket import: aborted after', elapsed, 'ms, reason:', controller.signal.reason);
        setImportError(controller.signal.reason === 'user' ? 'cancelled' : 'timed out waiting for a response (50s)');
      } else {
        console.error('[claude-ui] ticket import: failed after', elapsed, 'ms', e);
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
    console.log('[claude-ui] ticket browse: fetching recent tickets');
    const started = performance.now();
    try {
      const list = await api.listRecentTickets(controller.signal);
      console.log('[claude-ui] ticket browse: succeeded in', Math.round(performance.now() - started), 'ms', list);
      setRecentTickets(list);
    } catch (e) {
      const elapsed = Math.round(performance.now() - started);
      if (controller.signal.aborted) {
        console.error('[claude-ui] ticket browse: aborted after', elapsed, 'ms, reason:', controller.signal.reason);
        setPickerError(controller.signal.reason === 'user' ? 'cancelled' : 'timed out waiting for a response (50s)');
      } else {
        console.error('[claude-ui] ticket browse: failed after', elapsed, 'ms', e);
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

  useEffect(() => {
    if (!repoPath) return;
    api.branches(repoPath).then((list) => {
      setBranches(list);
      if (list.length > 0 && !list.includes(baseBranch)) {
        setBaseBranch(list.includes('main') ? 'main' : list[0]!);
      }
    }).catch(() => setBranches([]));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [repoPath]);

  const template = templates.find((t) => t.id === templateId);
  const kickoffPrompt = typeof template?.config['kickoffPrompt'] === 'string'
    ? (template.config['kickoffPrompt'] as string)
    : null;
  const placeholders = useMemo(() => (kickoffPrompt ? placeholdersOf(kickoffPrompt) : []), [kickoffPrompt]);

  const create = async () => {
    setError('');
    setBusy(true);
    try {
      const overrides: Record<string, unknown> = { model, permissionMode };
      if (thinking) overrides['thinking'] = thinking;
      if (effort) overrides['effort'] = effort;
      if (instructions.trim()) overrides['instructions'] = instructions.trim();
      overrides['ecosystemPath'] = ecosystemPath.trim() || null;
      if (promptFromTicket && resolvedTicketRef) overrides['ticketRef'] = resolvedTicketRef;
      const sniffSource = (raw: string) =>
        raw.startsWith('http') || raw.endsWith('.git') ? { type: 'repo', ref: raw } : { type: 'dir', ref: raw };
      const skillSources = [
        ...[...selectedSkillAssets.values()].map((a) => ({ type: 'dir', ref: a.location })),
        ...(extraSkill.trim() ? [sniffSource(extraSkill.trim())] : []),
      ];
      if (skillSources.length > 0) overrides['skillSources'] = skillSources;
      const agentSources = [
        ...[...selectedAgentAssets.values()].map((a) => ({ type: 'file', ref: a.location })),
        ...(extraAgent.trim() ? [sniffSource(extraAgent.trim())] : []),
      ];
      if (agentSources.length > 0) overrides['agentSources'] = agentSources;
      const draftInput = promptFromTicket ? initialPrompt.trim() : '';
      if (promptFromTicket) {
        // explicit '' (not omitted) so a template's own kickoffPrompt can't leak through the
        // config merge and auto-fire in place of the ticket text we're intentionally not sending
        overrides['kickoffPrompt'] = '';
      } else if (initialPrompt.trim()) {
        overrides['kickoffPrompt'] = initialPrompt.trim();
      }
      if (advanced.trim()) Object.assign(overrides, JSON.parse(advanced) as Record<string, unknown>);

      const created = await api.createSession({
        name: name.trim() || branch.trim(),
        branch: branch.trim(),
        baseBranch,
        repoPath,
        templateId: templateId || null,
        overrides,
        kickoffValues,
        syncBaseBranch,
      });
      onCreated(created.id, draftInput || undefined);
    } catch (e) {
      setError(e instanceof ApiError ? `${e.status}: ${e.message}` : String(e));
      setAdvancedOpen(true);
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
    <div className="modal-backdrop" onClick={() => { if (!busy && !importBusy) onCancel(); }}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h2>New Session</h2>
        <div className="form-grid">
          <label>Service</label>
          <select value={repoPath} onChange={(e) => setRepoPath(e.target.value)}>
            {(servicesInfo?.services ?? []).map((s) => (
              <option key={s.path} value={s.path}>{s.name}</option>
            ))}
          </select>

          <label className="full" style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <input type="checkbox" checked={syncBaseBranch} onChange={(e) => setSyncBaseBranch(e.target.checked)} />
            Update {baseBranch || 'base branch'} from origin before branching
          </label>

          <label>Branch</label>
          <input value={branch} onChange={(e) => setBranch(e.target.value)} list="branches" placeholder="feat/my-feature" autoFocus />
          <datalist id="branches">{branches.map((b) => <option key={b} value={b} />)}</datalist>

          {ticketImportEnabled && (
            <>
              <label>Import ticket</label>
              <div className="row" style={{ display: 'flex', gap: 6 }}>
                <input
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
            </>
          )}

          <label>Initial prompt</label>
          <textarea
            style={{ gridColumn: '2 / -1' }}
            rows={2}
            value={initialPrompt}
            onChange={(e) => setInitialPrompt(e.target.value)}
            placeholder="optional — overrides the template's kickoff prompt; filled in automatically by ticket import"
          />

          <label>Model</label>
          <select value={model} onChange={(e) => { setRecommendedModel(null); setModel(e.target.value); }}>
            <option value="sonnet">sonnet</option>
            <option value="opus">opus</option>
            <option value="haiku">haiku</option>
          </select>
          {recommendedModel && recommendedModel === model && (
            <div className="full" style={{ gridColumn: '2 / -1', color: 'var(--muted)', fontSize: 12.5 }}>
              recommended by ticket import
            </div>
          )}

          <label>Permissions</label>
          <select
            value={permissionMode}
            onChange={(e) => setPermissionMode(e.target.value)}
            title={permissionMode === 'bypassPermissions'
              ? 'ALL tool calls auto-approve, including Bash — no approval prompts at all'
              : undefined}
          >
            <option value="default">ask for edits & commands</option>
            <option value="acceptEdits">auto-accept edits</option>
            <option value="plan">plan first</option>
            <option value="bypassPermissions">bypass all approval (including Bash) — no prompts at all</option>
          </select>
        </div>

        <details
          className="advanced-toggle"
          open={advancedOpen}
          onToggle={(e) => setAdvancedOpen(e.currentTarget.open)}
        >
          <summary>Advanced options</summary>
          <div className="form-grid">
            <label>Name</label>
            <input value={name} onChange={(e) => setName(e.target.value)} placeholder="defaults to branch name" />

            <label>Base branch</label>
            <select value={baseBranch} onChange={(e) => setBaseBranch(e.target.value)}>
              {(branches.length ? branches : ['main']).map((b) => <option key={b} value={b}>{b}</option>)}
            </select>

            <label>Template</label>
            <select value={templateId} onChange={(e) => setTemplateId(e.target.value)}>
              <option value="">— none —</option>
              {templates.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
            </select>

            <label>Thinking</label>
            <select value={thinking} onChange={(e) => setThinking(e.target.value)}>
              <option value="">provider default</option>
              <option value="off">off</option>
              <option value="adaptive">adaptive</option>
              <option value="16000">budget 16k</option>
            </select>

            <label>Effort</label>
            <select value={effort} onChange={(e) => setEffort(e.target.value)}>
              <option value="">provider default</option>
              {['low', 'medium', 'high', 'xhigh', 'max'].map((l) => <option key={l} value={l}>{l}</option>)}
            </select>

            <label>Ecosystem</label>
            <input
              value={ecosystemPath}
              onChange={(e) => setEcosystemPath(e.target.value)}
              placeholder="read-only context folder; empty = no wider context"
              title="parent folder attached read-only so Claude can read sibling services"
            />

            <label>Skills</label>
            <AttachedAssetsRow
              assets={[...selectedSkillAssets.values()]}
              onBrowse={() => setAssetPickerKind('skill')}
              buttonLabel="Add skills…"
            />

            <label>Extra skill</label>
            <input
              value={extraSkill}
              onChange={(e) => setExtraSkill(e.target.value)}
              placeholder="path or git URL of a skill source"
            />

            <label>Agents</label>
            <AttachedAssetsRow
              assets={[...selectedAgentAssets.values()]}
              onBrowse={() => setAssetPickerKind('agent')}
              buttonLabel="Add agents…"
            />

            <label>Extra agent</label>
            <input
              value={extraAgent}
              onChange={(e) => setExtraAgent(e.target.value)}
              placeholder="path or git URL of an agent source"
            />

            <label>Instructions</label>
            <textarea
              className="full"
              style={{ gridColumn: '2 / -1' }}
              rows={2}
              value={instructions}
              onChange={(e) => setInstructions(e.target.value)}
              placeholder="extra system instructions for this session"
            />

            {placeholders.map((ph) => (
              <div key={ph} style={{ display: 'contents' }}>
                <label>{ph}</label>
                <input
                  value={kickoffValues[ph] ?? ''}
                  onChange={(e) => setKickoffValues({ ...kickoffValues, [ph]: e.target.value })}
                  placeholder={`kickoff value for {{${ph}}}`}
                />
              </div>
            ))}

            <label>Raw overrides</label>
            <textarea
              style={{ gridColumn: '2 / -1', fontFamily: 'monospace' }}
              rows={2}
              value={advanced}
              onChange={(e) => setAdvanced(e.target.value)}
              placeholder='extra overrides JSON, e.g. {"maxTurns": 30, "contextDirs": ["/path"]}'
            />
          </div>
        </details>
        {error && <div className="error-text" style={{ marginTop: 10 }}>{error}</div>}
        <div className="actions">
          <button onClick={onCancel}>Cancel</button>
          <button className="primary" disabled={busy || !branch.trim()} onClick={() => void create()}>
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
    {assetPickerKind && (
      <AssetPickerDialog
        kind={assetPickerKind}
        semanticAvailable={!!settings?.voyageConfigured && !!settings?.libraryVectorize}
        selected={assetPickerKind === 'skill' ? selectedSkillAssets : selectedAgentAssets}
        onConfirm={(picked) => {
          if (assetPickerKind === 'skill') setSelectedSkillAssets(picked); else setSelectedAgentAssets(picked);
          setAssetPickerKind(null);
        }}
        onClose={() => setAssetPickerKind(null)}
      />
    )}
    </>
  );
}

/** First few attached asset names as chips, a "+N more" chip, and a browse button. */
function AttachedAssetsRow({ assets, onBrowse, buttonLabel }: {
  assets: LibraryAsset[];
  onBrowse: () => void;
  buttonLabel: string;
}) {
  const shown = assets.slice(0, 3);
  const rest = assets.length - shown.length;
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
      {shown.map((a) => <span key={a.id} className="chip" title={a.description}>{a.name}</span>)}
      {rest > 0 && <span className="chip">+{rest} more</span>}
      <button onClick={onBrowse}>{buttonLabel}</button>
    </div>
  );
}
