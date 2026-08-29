import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../api/rest';
import type { AssetKind, LibraryAsset, LibraryAssetContent, LibrarySource, ScanCandidate, ScanResult, Settings } from '../protocol';

const PAGE_SIZE = 20;

type View = 'library' | 'import' | 'sources';

interface RowDraft {
  selected: boolean;
  kind: AssetKind;
  name: string;
  description: string;
  tags: string;
}

function splitTags(text: string): string[] {
  return text.split(',').map((t) => t.trim()).filter(Boolean);
}

function sniffType(ref: string): 'dir' | 'repo' {
  const r = ref.trim();
  if (r.startsWith('http://') || r.startsWith('https://') || r.endsWith('.git')) return 'repo';
  if (/^[\w.-]+\/[\w.-]+$/.test(r)) return 'repo'; // owner/repo shorthand
  return 'dir';
}

function Pagination({ page, total, onPage }: { page: number; total: number; onPage: (p: number) => void }) {
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  if (pages <= 1) return null;
  return (
    <div className="pagination">
      <button disabled={page <= 0} onClick={() => onPage(page - 1)}>‹ Prev</button>
      <span>{page + 1} / {pages}</span>
      <button disabled={page >= pages - 1} onClick={() => onPage(page + 1)}>Next ›</button>
    </div>
  );
}

export default function LibraryDialog({ onClose }: { onClose: () => void }) {
  const [view, setView] = useState<View>('library');
  const [settings, setSettings] = useState<Settings | null>(null);

  useEffect(() => {
    api.getSettings().then(setSettings).catch(() => setSettings(null));
  }, []);

  // --- library view state ---
  const [assets, setAssets] = useState<LibraryAsset[] | null>(null);
  const [kindFilter, setKindFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('ACTIVE');
  const [textFilter, setTextFilter] = useState('');
  const [semantic, setSemantic] = useState(false);
  const [semanticHits, setSemanticHits] = useState<Map<string, number> | null>(null);
  const [libraryPage, setLibraryPage] = useState(0);
  const [libraryError, setLibraryError] = useState('');
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editDraft, setEditDraft] = useState({ name: '', description: '', tags: '' });

  const refreshAssets = useCallback(() => {
    api.libraryAssets({ kind: kindFilter, status: statusFilter, q: semantic ? '' : textFilter })
      .then((list) => { setAssets(list); setLibraryError(''); })
      .catch((e) => setLibraryError(String((e as Error).message ?? e)));
  }, [kindFilter, statusFilter, textFilter, semantic]);

  useEffect(() => {
    refreshAssets();
    setLibraryPage(0);
  }, [refreshAssets]);

  const runSemanticSearch = () => {
    if (!textFilter.trim()) { setSemanticHits(null); return; }
    api.librarySearch(textFilter.trim(), 20)
      .then((hits) => {
        setSemanticHits(new Map(hits.map((h) => [h.asset.id, h.distance])));
        setLibraryError('');
      })
      .catch((e) => setLibraryError(String((e as Error).message ?? e)));
  };

  const startEdit = (a: LibraryAsset) => {
    setEditingId(a.id);
    setEditDraft({ name: a.name, description: a.description, tags: a.tags.join(', ') });
  };

  const saveEdit = (id: string) => {
    void api.libraryUpdateAsset(id, {
      name: editDraft.name, description: editDraft.description, tags: splitTags(editDraft.tags),
    }).then(() => { setEditingId(null); refreshAssets(); })
      .catch((e) => setLibraryError(String((e as Error).message ?? e)));
  };

  const setStatus = (id: string, status: string) => {
    void api.libraryUpdateAsset(id, { status }).then(refreshAssets)
      .catch((e) => setLibraryError(String((e as Error).message ?? e)));
  };

  const deleteAsset = (a: LibraryAsset) => {
    if (!window.confirm(`Delete "${a.name}" and its copy at ${a.location}?`)) return;
    void api.libraryDeleteAsset(a.id).then(refreshAssets)
      .catch((e) => setLibraryError(String((e as Error).message ?? e)));
  };

  // --- details drawer state ---
  const [detailAsset, setDetailAsset] = useState<LibraryAsset | null>(null);
  const [detailContent, setDetailContent] = useState<LibraryAssetContent | null>(null);
  const [detailError, setDetailError] = useState('');
  const [detailLoading, setDetailLoading] = useState(false);

  const openDetails = (a: LibraryAsset) => {
    setDetailAsset(a);
    setDetailContent(null);
    setDetailError('');
    setDetailLoading(true);
    api.libraryAssetContent(a.id)
      .then(setDetailContent)
      .catch((e) => setDetailError(String((e as Error).message ?? e)))
      .finally(() => setDetailLoading(false));
  };

  const closeDetails = () => setDetailAsset(null);

  // --- import view state ---
  const [sourceRef, setSourceRef] = useState('');
  const [scan, setScan] = useState<ScanResult | null>(null);
  const [drafts, setDrafts] = useState<Map<string, RowDraft>>(new Map());
  const [scanBusy, setScanBusy] = useState(false);
  const [importBusy, setImportBusy] = useState(false);
  const [aiProgress, setAiProgress] = useState('');
  const [importPage, setImportPage] = useState(0);
  const [importError, setImportError] = useState('');
  const [importNotes, setImportNotes] = useState<string[]>([]);
  const [syncSource, setSyncSource] = useState(true);
  const aiAbortRef = useRef<AbortController | null>(null);
  const preselectRef = useRef<string[] | null>(null);

  // note: deliberately does NOT clear importNotes — the post-import rescan must not
  // wipe the "imported N of M" feedback; manual scans clear notes at the call site
  const doScan = useCallback((ref: string, preselect?: string[]) => {
    const trimmed = ref.trim();
    if (!trimmed) return;
    setScanBusy(true);
    setImportError('');
    setScan(null);
    const started = performance.now();
    api.libraryScan(sniffType(trimmed), trimmed)
      .then((result) => {
        console.log('[claude-ui] library scan done', result.candidates.length, 'candidates in',
          Math.round(performance.now() - started), 'ms');
        setScan(result);
        setImportPage(0);
        const wanted = new Set(preselect ?? preselectRef.current ?? []);
        preselectRef.current = null;
        setDrafts(new Map(result.candidates.map((c) => [c.path, {
          selected: wanted.has(c.path),
          kind: c.kind,
          name: c.name,
          description: c.description,
          tags: '',
        }])));
      })
      .catch((e) => setImportError(String((e as Error).message ?? e)))
      .finally(() => setScanBusy(false));
  }, []);

  const patchDraft = (path: string, patch: Partial<RowDraft>) => {
    setDrafts((prev) => {
      const next = new Map(prev);
      const current = next.get(path);
      if (current) next.set(path, { ...current, ...patch });
      return next;
    });
  };

  const selectedPaths = scan
    ? scan.candidates.filter((c) => drafts.get(c.path)?.selected && !c.alreadyImported).map((c) => c.path)
    : [];
  const selectablePaths = scan ? scan.candidates.filter((c) => !c.alreadyImported).map((c) => c.path) : [];
  const allSelected = selectablePaths.length > 0 && selectedPaths.length === selectablePaths.length;

  const toggleAll = () => {
    setDrafts((prev) => {
      const next = new Map(prev);
      for (const path of selectablePaths) {
        const current = next.get(path);
        if (current) next.set(path, { ...current, selected: !allSelected });
      }
      return next;
    });
  };

  const aiFill = (paths: string[]) => {
    if (!scan || paths.length === 0) return;
    const controller = new AbortController();
    aiAbortRef.current = controller;
    const started = performance.now();
    void (async () => {
      try {
        const CHUNK = 5;
        for (let i = 0; i < paths.length; i += CHUNK) {
          setAiProgress(`AI-fill ${Math.min(i + CHUNK, paths.length)}/${paths.length}…`);
          const chunk = paths.slice(i, i + CHUNK);
          const filled = await api.libraryAiFill(scan.type, scan.ref, chunk, controller.signal);
          for (const meta of filled) {
            patchDraft(meta.path, { name: meta.name, description: meta.description, tags: meta.tags.join(', ') });
          }
        }
        console.log('[claude-ui] ai-fill done for', paths.length, 'in', Math.round(performance.now() - started), 'ms');
      } catch (e) {
        if (!controller.signal.aborted) setImportError(String((e as Error).message ?? e));
      } finally {
        setAiProgress('');
        aiAbortRef.current = null;
      }
    })();
  };

  const doImport = () => {
    if (!scan || selectedPaths.length === 0) return;
    setImportBusy(true);
    setImportError('');
    const items = selectedPaths.map((path) => {
      const draft = drafts.get(path)!;
      return { path, kind: draft.kind, name: draft.name, description: draft.description, tags: splitTags(draft.tags) };
    });
    void api.libraryImport({ type: scan.type, ref: scan.ref, syncEnabled: syncSource }, items)
      .then((results) => {
        const notes = results.filter((r) => r.warning).map((r) => `${r.path}: ${r.warning}`);
        const ok = results.filter((r) => r.assetId).length;
        setImportNotes([`imported ${ok} of ${results.length}`, ...notes]);
        refreshAssets();
        refreshSources();
        doScan(scan.ref); // re-scan so imported rows show as such
      })
      .catch((e) => setImportError(String((e as Error).message ?? e)))
      .finally(() => setImportBusy(false));
  };

  // --- sources view state ---
  const [sources, setSources] = useState<LibrarySource[] | null>(null);
  const [sourcesError, setSourcesError] = useState('');
  const [syncingId, setSyncingId] = useState<string | null>(null);

  const refreshSources = useCallback(() => {
    api.librarySources().then((list) => { setSources(list); setSourcesError(''); })
      .catch((e) => setSourcesError(String((e as Error).message ?? e)));
  }, []);
  useEffect(() => refreshSources(), [refreshSources]);

  const toggleSync = (s: LibrarySource) => {
    void api.libraryUpdateSource(s.id, { syncEnabled: !s.syncEnabled }).then(refreshSources)
      .catch((e) => setSourcesError(String((e as Error).message ?? e)));
  };

  const syncNow = (s: LibrarySource) => {
    setSyncingId(s.id);
    void api.librarySyncNow(s.id)
      .then(() => { refreshSources(); refreshAssets(); })
      .catch((e) => setSourcesError(String((e as Error).message ?? e)))
      .finally(() => setSyncingId(null));
  };

  const deleteSource = (s: LibrarySource) => {
    if (!window.confirm(`Forget source ${s.ref}? Imported assets stay in the library.`)) return;
    void api.libraryDeleteSource(s.id).then(refreshSources)
      .catch((e) => setSourcesError(String((e as Error).message ?? e)));
  };

  const reviewAndAdd = (s: LibrarySource) => {
    preselectRef.current = s.discoveries.map((d) => d.path);
    setSourceRef(s.ref);
    setView('import');
    setImportNotes([]);
    doScan(s.ref);
  };

  const dismissAll = (s: LibrarySource) => {
    void api.libraryDismiss(s.id).then(refreshSources)
      .catch((e) => setSourcesError(String((e as Error).message ?? e)));
  };

  // --- render ---

  const detailSource = detailAsset ? (sources ?? []).find((s) => s.id === detailAsset.sourceId) ?? null : null;
  const semanticAvailable = !!settings?.voyageConfigured && !!settings?.libraryVectorize;
  const shownAssets = (assets ?? [])
    .filter((a) => !semantic || !semanticHits || semanticHits.has(a.id))
    .sort((a, b) => semantic && semanticHits
      ? (semanticHits.get(a.id) ?? 9) - (semanticHits.get(b.id) ?? 9)
      : a.name.localeCompare(b.name));
  const assetPage = shownAssets.slice(libraryPage * PAGE_SIZE, (libraryPage + 1) * PAGE_SIZE);
  const candidatePage = (scan?.candidates ?? []).slice(importPage * PAGE_SIZE, (importPage + 1) * PAGE_SIZE);
  const kindIcon = (kind: AssetKind) => (kind === 'skill' ? '📖' : '🤖');

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal wide" onClick={(e) => e.stopPropagation()}>
        <h2 style={{ display: 'flex', alignItems: 'center' }}>
          Skill &amp; agent library
          <div className="grouping-toggle">
            {(['library', 'import', 'sources'] as View[]).map((v) => (
              <button key={v} className={view === v ? 'active' : ''} onClick={() => setView(v)}>
                {v === 'library' ? 'Library' : v === 'import' ? 'Import' : 'Sources'}
              </button>
            ))}
          </div>
        </h2>

        {view === 'library' && (
          <>
            <div className="usage-filters">
              <select value={kindFilter} onChange={(e) => setKindFilter(e.target.value)}>
                <option value="">all kinds</option>
                <option value="skill">skills</option>
                <option value="agent">agents</option>
              </select>
              <select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)}>
                <option value="ACTIVE">active</option>
                <option value="ARCHIVED">archived</option>
                <option value="">all</option>
              </select>
              <input
                style={{ flex: 1 }}
                placeholder={semantic ? 'semantic search — press Enter' : 'filter by name, description, or tag'}
                value={textFilter}
                onChange={(e) => { setTextFilter(e.target.value); if (!semantic) setSemanticHits(null); }}
                onKeyDown={(e) => { if (e.key === 'Enter' && semantic) runSemanticSearch(); }}
              />
              {semanticAvailable && (
                <label style={{ display: 'flex', alignItems: 'center', gap: 5, color: 'var(--muted)', whiteSpace: 'nowrap' }}>
                  <input
                    type="checkbox"
                    checked={semantic}
                    onChange={(e) => { setSemantic(e.target.checked); setSemanticHits(null); }}
                  />
                  semantic
                </label>
              )}
            </div>
            {libraryError && <div className="error-text">{libraryError}</div>}
            {assets === null && !libraryError && <div style={{ color: 'var(--muted)' }}>loading…</div>}
            {assets !== null && shownAssets.length === 0 && (
              <div style={{ color: 'var(--muted)' }}>
                nothing here yet — use the Import tab to scan a folder or GitHub repo
              </div>
            )}
            <div className="stale-list">
              {assetPage.map((a) => (
                <div key={a.id} className="stale-row" style={a.status === 'ARCHIVED' ? { opacity: 0.6 } : undefined}>
                  {editingId === a.id ? (
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 6, flex: 1 }}>
                      <div style={{ display: 'flex', gap: 6 }}>
                        <span title={a.kind}>{kindIcon(a.kind)}</span>
                        <input
                          style={{ width: 220 }}
                          value={editDraft.name}
                          onChange={(e) => setEditDraft((d) => ({ ...d, name: e.target.value }))}
                        />
                        <input
                          style={{ flex: 1 }}
                          value={editDraft.description}
                          placeholder="description"
                          onChange={(e) => setEditDraft((d) => ({ ...d, description: e.target.value }))}
                        />
                      </div>
                      <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
                        <input
                          style={{ flex: 1 }}
                          value={editDraft.tags}
                          placeholder="tags, comma, separated"
                          onChange={(e) => setEditDraft((d) => ({ ...d, tags: e.target.value }))}
                        />
                        <button className="primary" onClick={() => saveEdit(a.id)}>Save</button>
                        <button onClick={() => setEditingId(null)}>Cancel</button>
                      </div>
                    </div>
                  ) : (
                    <>
                      <span title={a.kind}>{kindIcon(a.kind)}</span>
                      <span
                        className="name"
                        title={`${a.location} — click for details`}
                        style={{ cursor: 'pointer', textDecoration: 'underline dotted' }}
                        onClick={() => openDetails(a)}
                      >
                        {a.name}
                      </span>
                      <span className="branch" style={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={a.description}>
                        {a.description}
                      </span>
                      {a.tags.map((t) => <span key={t} className="chip">{t}</span>)}
                      {a.status === 'ARCHIVED' && <span className="chip" style={{ color: 'var(--amber)', borderColor: 'var(--amber)' }}>archived</span>}
                      {semantic && semanticHits?.has(a.id) && (
                        <span className="idle" title="cosine distance">{semanticHits.get(a.id)!.toFixed(3)}</span>
                      )}
                      <span style={{ marginLeft: 'auto', display: 'flex', gap: 5 }}>
                        <button onClick={() => openDetails(a)}>Details</button>
                        <button onClick={() => startEdit(a)}>Edit</button>
                        {a.status === 'ACTIVE'
                          ? <button onClick={() => setStatus(a.id, 'ARCHIVED')}>Archive</button>
                          : <button onClick={() => setStatus(a.id, 'ACTIVE')}>Restore</button>}
                        <button className="danger" onClick={() => deleteAsset(a)}>Delete</button>
                      </span>
                    </>
                  )}
                </div>
              ))}
            </div>
            <Pagination page={libraryPage} total={shownAssets.length} onPage={setLibraryPage} />
          </>
        )}

        {view === 'import' && (
          <>
            <div style={{ display: 'flex', gap: 8, marginBottom: 10 }}>
              <input
                style={{ flex: 1 }}
                placeholder="local folder path, GitHub URL, or owner/repo"
                value={sourceRef}
                onChange={(e) => setSourceRef(e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter') { setImportNotes([]); doScan(sourceRef); } }}
              />
              <button
                className="primary"
                disabled={scanBusy || !sourceRef.trim()}
                onClick={() => { setImportNotes([]); doScan(sourceRef); }}
              >
                {scanBusy ? 'Scanning…' : 'Scan'}
              </button>
            </div>
            {importError && <div className="error-text" style={{ marginBottom: 8 }}>{importError}</div>}
            {importNotes.map((n, i) => (
              <div key={i} style={{ color: i === 0 ? 'var(--green)' : 'var(--amber)', fontSize: 'calc(12.5px * var(--font-scale))' }}>{n}</div>
            ))}
            {scan && scan.candidates.length === 0 && (
              <div style={{ color: 'var(--muted)' }}>no skills or agents found in {scan.ref}</div>
            )}
            {scan && scan.candidates.length > 0 && (
              <>
                <div style={{ display: 'flex', alignItems: 'center', gap: 10, margin: '4px 0 8px' }}>
                  <label style={{ display: 'flex', alignItems: 'center', gap: 5, color: 'var(--muted)' }}>
                    <input type="checkbox" checked={allSelected} onChange={toggleAll} />
                    select all ({selectedPaths.length}/{selectablePaths.length})
                  </label>
                  {aiProgress
                    ? <button onClick={() => aiAbortRef.current?.abort('user')}>Cancel — {aiProgress}</button>
                    : <button disabled={selectedPaths.length === 0} onClick={() => aiFill(selectedPaths)}>✨ AI-fill selected</button>}
                  <label style={{ display: 'flex', alignItems: 'center', gap: 5, color: 'var(--muted)', marginLeft: 'auto' }}>
                    <input type="checkbox" checked={syncSource} onChange={(e) => setSyncSource(e.target.checked)} />
                    keep source synced
                  </label>
                  <button className="primary" disabled={importBusy || selectedPaths.length === 0} onClick={doImport}>
                    {importBusy ? 'Importing…' : `Import selected (${selectedPaths.length})`}
                  </button>
                </div>
                <div className="candidate-list">
                  {candidatePage.map((c: ScanCandidate) => {
                    const draft = drafts.get(c.path);
                    if (!draft) return null;
                    return (
                      <div key={c.path} className="candidate-row">
                        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                          <input
                            type="checkbox"
                            disabled={c.alreadyImported}
                            checked={draft.selected}
                            onChange={(e) => patchDraft(c.path, { selected: e.target.checked })}
                          />
                          <span title={draft.kind}>{kindIcon(draft.kind)}</span>
                          <code style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={c.path}>{c.path}</code>
                          {c.confidence === 'low' && <span className="chip" title="matched only by name heuristic">low confidence</span>}
                          {c.alreadyImported && !c.changedSinceImport && <span className="chip" style={{ color: 'var(--green)', borderColor: 'var(--green)' }}>imported</span>}
                          {c.changedSinceImport && <span className="chip" style={{ color: 'var(--amber)', borderColor: 'var(--amber)' }}>changed upstream</span>}
                          <span style={{ marginLeft: 'auto', display: 'flex', gap: 5, alignItems: 'center' }}>
                            <select
                              value={draft.kind}
                              disabled={c.alreadyImported}
                              onChange={(e) => patchDraft(c.path, { kind: e.target.value as AssetKind })}
                            >
                              <option value="skill">skill</option>
                              <option value="agent">agent</option>
                            </select>
                            <button
                              title="fill name/description/tags with AI"
                              disabled={!!aiProgress || c.alreadyImported}
                              onClick={() => aiFill([c.path])}
                            >✨</button>
                          </span>
                        </div>
                        {!c.alreadyImported && (
                          <div style={{ display: 'flex', gap: 6, marginTop: 5 }}>
                            <input
                              style={{ width: 200 }}
                              placeholder="name"
                              value={draft.name}
                              onChange={(e) => patchDraft(c.path, { name: e.target.value })}
                            />
                            <input
                              style={{ flex: 2 }}
                              placeholder="description"
                              value={draft.description}
                              onChange={(e) => patchDraft(c.path, { description: e.target.value })}
                            />
                            <input
                              style={{ flex: 1 }}
                              placeholder="tags, comma, separated"
                              value={draft.tags}
                              onChange={(e) => patchDraft(c.path, { tags: e.target.value })}
                            />
                          </div>
                        )}
                      </div>
                    );
                  })}
                </div>
                <Pagination page={importPage} total={scan.candidates.length} onPage={setImportPage} />
              </>
            )}
          </>
        )}

        {view === 'sources' && (
          <>
            {sourcesError && <div className="error-text" style={{ marginBottom: 8 }}>{sourcesError}</div>}
            {sources !== null && sources.length === 0 && (
              <div style={{ color: 'var(--muted)' }}>no sources yet — importing from the Import tab records one</div>
            )}
            <div className="stale-list">
              {(sources ?? []).map((s) => (
                <div key={s.id} style={{ background: 'var(--panel2)', borderRadius: 6, padding: '8px 10px' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <span title={s.type}>{s.type === 'repo' ? '🌐' : '📁'}</span>
                    <span className="name" style={{ fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={s.ref}>{s.ref}</span>
                    <span className="chip">{s.assetCount} asset{s.assetCount === 1 ? '' : 's'}</span>
                    {s.lastSyncStatus && (
                      <span
                        className="chip"
                        style={s.lastSyncStatus === 'ERROR' ? { color: 'var(--red)', borderColor: 'var(--red)' } : undefined}
                        title={s.lastSyncError ?? (s.lastSyncedAt ? `last sync ${new Date(s.lastSyncedAt).toLocaleString()}` : '')}
                      >
                        sync {s.lastSyncStatus.toLowerCase()}
                      </span>
                    )}
                    <span style={{ marginLeft: 'auto', display: 'flex', gap: 5, alignItems: 'center' }}>
                      <label style={{ display: 'flex', alignItems: 'center', gap: 4, color: 'var(--muted)', fontSize: 'calc(12px * var(--font-scale))' }}>
                        <input type="checkbox" checked={s.syncEnabled} onChange={() => toggleSync(s)} />
                        sync
                      </label>
                      <button disabled={syncingId === s.id} onClick={() => syncNow(s)}>
                        {syncingId === s.id ? 'Syncing…' : 'Sync now'}
                      </button>
                      <button className="danger" onClick={() => deleteSource(s)}>Forget</button>
                    </span>
                  </div>
                  {s.discoveries.length > 0 && (
                    <div style={{ marginTop: 6, display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                      <span className="count-badge">{s.discoveries.length}</span>
                      <span style={{ color: 'var(--muted)', fontSize: 'calc(12.5px * var(--font-scale))' }}>
                        new upstream: {s.discoveries.slice(0, 3).map((d) => d.path).join(', ')}
                        {s.discoveries.length > 3 ? '…' : ''}
                      </span>
                      <button onClick={() => reviewAndAdd(s)}>Review &amp; add</button>
                      <button onClick={() => dismissAll(s)}>Dismiss</button>
                    </div>
                  )}
                </div>
              ))}
            </div>
          </>
        )}

        <div className="actions">
          <button onClick={onClose}>Close</button>
        </div>
      </div>

      {detailAsset && (
        <div className="drawer-backdrop" onClick={(e) => { e.stopPropagation(); closeDetails(); }}>
          <div className="drawer" onClick={(e) => e.stopPropagation()}>
            <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8 }}>
              <span style={{ fontSize: 'calc(20px * var(--font-scale))' }} title={detailAsset.kind}>{kindIcon(detailAsset.kind)}</span>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontWeight: 600, fontSize: 'calc(15px * var(--font-scale))', wordBreak: 'break-word' }}>{detailAsset.name}</div>
                <div style={{ color: 'var(--muted)', fontSize: 'calc(12px * var(--font-scale))' }}>
                  {detailAsset.kind}
                  {detailAsset.status === 'ARCHIVED' && <span className="chip" style={{ marginLeft: 6, color: 'var(--amber)', borderColor: 'var(--amber)' }}>archived</span>}
                </div>
              </div>
              <button onClick={closeDetails}>✕</button>
            </div>

            {detailAsset.description && (
              <p style={{ marginTop: 12, color: 'var(--text)' }}>{detailAsset.description}</p>
            )}

            {detailAsset.tags.length > 0 && (
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 5, marginTop: 8 }}>
                {detailAsset.tags.map((t) => <span key={t} className="chip">{t}</span>)}
              </div>
            )}

            <dl className="drawer-meta">
              <dt>Location</dt>
              <dd title={detailAsset.location} style={{ wordBreak: 'break-all' }}>{detailAsset.location}</dd>

              {detailSource && (
                <>
                  <dt>Source</dt>
                  <dd title={detailSource.ref} style={{ wordBreak: 'break-all' }}>
                    {detailSource.type === 'repo' ? '🌐' : '📁'} {detailSource.ref}
                    {detailAsset.sourcePath && detailAsset.sourcePath !== '.' ? ` (${detailAsset.sourcePath})` : ''}
                  </dd>
                </>
              )}

              <dt>Content hash</dt>
              <dd><code>{detailAsset.contentHash.slice(0, 12)}</code></dd>

              <dt>Created</dt>
              <dd>{new Date(detailAsset.createdAt).toLocaleString()}</dd>

              <dt>Updated</dt>
              <dd>{new Date(detailAsset.updatedAt).toLocaleString()}</dd>
            </dl>

            <h3 style={{ margin: '16px 0 6px', fontSize: 'calc(13px * var(--font-scale))' }}>
              {detailContent ? detailContent.sourceFile : 'Content'}
            </h3>
            {detailLoading && <div style={{ color: 'var(--muted)' }}>loading…</div>}
            {detailError && <div className="error-text">{detailError}</div>}
            {detailContent && (
              <>
                <pre className="drawer-content">{detailContent.content || '(empty)'}</pre>
                {detailContent.truncated && (
                  <div style={{ color: 'var(--muted)', fontSize: 'calc(12px * var(--font-scale))', marginTop: 4 }}>
                    truncated — showing the first part of the file
                  </div>
                )}
              </>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
