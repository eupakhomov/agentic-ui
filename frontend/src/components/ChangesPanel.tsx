import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { api, ApiError } from '../api/rest';
import type { ChangedFile, ChangedFileView, ChangeList } from '../protocol';
import { Close, Refresh } from '../icons';
import DiffView, { type DiffMode } from '../changes/DiffView';
import { buildAskPrompt, changeSignature, type LineSelection } from '../changes/selection';

const WIDTH_KEY = 'artus.changesWidth';
const DEFAULT_WIDTH = 520;
const MIN_WIDTH = 340;
/** panel width from which "auto" shows the two-sided diff */
const SPLIT_FROM = 900;

type ModeChoice = 'auto' | DiffMode;

function loadWidth(): number {
  try {
    const n = Number(localStorage.getItem(WIDTH_KEY));
    return Number.isFinite(n) && n >= MIN_WIDTH ? n : DEFAULT_WIDTH;
  } catch {
    return DEFAULT_WIDTH;
  }
}

const STATUS_LETTER: Record<ChangedFile['status'], string> = { added: 'A', modified: 'M', deleted: 'D', renamed: 'R' };
const WHERE_LABEL: Record<ChangedFile['where'][number], string> = {
  committed: 'committed', staged: 'staged', unstaged: 'unstaged', untracked: 'new',
};

interface Props {
  sessionId: string;
  changes: ChangeList | null;
  loading: boolean;
  error: string;
  onRefresh: () => void;
  onClose: () => void;
  /** text to drop into the composer */
  onAsk: (text: string) => void;
  /** narrow widget: the panel covers the widget instead of sitting beside it */
  overlay: boolean;
}

/**
 * Phase 17 C: read-only view of what the session's branch would carry into a PR — a file list and
 * a syntax-highlighted diff — plus "ask about these lines", which hands the selection to the
 * composer. There is deliberately no editing: the agent owns the worktree.
 */
export default function ChangesPanel({ sessionId, changes, loading, error, onRefresh, onClose, onAsk, overlay }: Props) {
  const [width, setWidth] = useState(loadWidth);
  const [panelW, setPanelW] = useState(0);
  const [modeChoice, setModeChoice] = useState<ModeChoice>('auto');
  const [selectedPath, setSelectedPath] = useState<string | null>(null);
  const [file, setFile] = useState<ChangedFileView | null>(null);
  const [fileSig, setFileSig] = useState<string | null>(null);
  const [fileError, setFileError] = useState('');
  const [reloadNonce, setReloadNonce] = useState(0);
  const [selection, setSelection] = useState<LineSelection | null>(null);
  const rootRef = useRef<HTMLElement>(null);
  const files = changes?.files ?? [];

  useLayoutEffect(() => {
    const el = rootRef.current;
    if (!el) return;
    const ro = new ResizeObserver(() => setPanelW(el.getBoundingClientRect().width));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  // keep a valid selection: first file by default, and move on when the open one stops being a change
  useEffect(() => {
    if (files.length === 0) {
      setSelectedPath(null);
      return;
    }
    if (!selectedPath || !files.some((f) => f.path === selectedPath)) {
      // a vanished open file is reported by the banner, not by silently jumping away from it
      if (!selectedPath || !file) setSelectedPath(files[0]!.path);
    }
  }, [files, selectedPath, file]);

  const selectedRow = files.find((f) => f.path === selectedPath) ?? null;
  const rowRef = useRef<ChangedFile | null>(null);
  rowRef.current = selectedRow;

  useEffect(() => {
    if (!selectedPath) {
      setFile(null);
      return;
    }
    let cancelled = false;
    setFile(null);
    setFileError('');
    api.sessionChangedFile(sessionId, selectedPath)
      .then((f) => {
        if (cancelled) return;
        setFile(f);
        setFileSig(rowRef.current ? changeSignature(rowRef.current) : null);
      })
      .catch((e) => { if (!cancelled) setFileError(e instanceof ApiError ? e.message : String(e)); });
    return () => { cancelled = true; };
  }, [sessionId, selectedPath, reloadNonce]);

  const stale = file !== null && fileSig !== null && (selectedRow === null || changeSignature(selectedRow) !== fileSig);

  const mode: DiffMode = modeChoice === 'auto' ? (panelW >= SPLIT_FROM ? 'split' : 'unified') : modeChoice;

  const ask = useCallback(() => {
    if (!selection || !selectedPath) return;
    onAsk(buildAskPrompt(selectedPath, selection));
  }, [selection, selectedPath, onAsk]);

  const startResize = (e: React.MouseEvent) => {
    e.preventDefault();
    const startX = e.clientX;
    const startW = width;
    const max = Math.max(MIN_WIDTH, window.innerWidth * 0.8);
    let latest = startW;
    const move = (ev: MouseEvent) => {
      latest = Math.min(max, Math.max(MIN_WIDTH, startW + (startX - ev.clientX)));
      setWidth(latest);
    };
    const up = () => {
      window.removeEventListener('mousemove', move);
      window.removeEventListener('mouseup', up);
      try { localStorage.setItem(WIDTH_KEY, String(Math.round(latest))); } catch { /* storage unavailable */ }
    };
    window.addEventListener('mousemove', move);
    window.addEventListener('mouseup', up);
  };

  const onKeyDown = (e: React.KeyboardEvent) => {
    const t = e.target as HTMLElement;
    if (t.isContentEditable || t.tagName === 'INPUT' || t.tagName === 'TEXTAREA') return;
    if (e.metaKey || e.ctrlKey || e.altKey) return;
    const step = e.key === 'j' || e.key === 'ArrowDown' ? 1 : e.key === 'k' || e.key === 'ArrowUp' ? -1 : 0;
    if (step === 0 || files.length === 0) return;
    e.preventDefault();
    e.stopPropagation(); // j/k would otherwise switch sessions
    const idx = files.findIndex((f) => f.path === selectedPath);
    const next = files[Math.min(files.length - 1, Math.max(0, idx + step))]!;
    setSelectedPath(next.path);
    document.querySelector<HTMLElement>(`[data-change-path="${CSS.escape(next.path)}"]`)?.focus();
  };

  const base = changes?.baseKnown ? `${changes.baseBranch}@${changes.mergeBase?.slice(0, 7) ?? ''}` : null;
  const original = file?.baseContent ?? '';
  const modified = file?.content ?? '';
  const showDiff = file !== null && !file.binary;

  const summary = useMemo(() => {
    const add = files.reduce((n, f) => n + f.additions, 0);
    const del = files.reduce((n, f) => n + f.deletions, 0);
    return { add, del };
  }, [files]);

  return (
    <aside
      ref={rootRef}
      className={`changes-panel${overlay ? ' overlay' : ''}`}
      style={overlay ? undefined : { width }}
      onKeyDown={onKeyDown}
    >
      {!overlay && <div className="changes-resize" onMouseDown={startResize} title="drag to resize" />}
      <div className="changes-head">
        <strong>Changes</strong>
        <span className="chip" title={base ? `working tree vs merge-base with ${changes?.baseBranch}` : 'base branch not found'}>
          {base ? `vs ${base}` : 'uncommitted only'}
        </span>
        {files.length > 0 && (
          <span className="changes-sum">
            {files.length} file{files.length === 1 ? '' : 's'} <span className="add">+{summary.add}</span> <span className="del">−{summary.del}</span>
          </span>
        )}
        <span style={{ flex: 1 }} />
        <button
          className="icon-btn"
          title={`diff layout: ${modeChoice === 'auto' ? `auto (${mode})` : modeChoice} — click to change`}
          onClick={() => setModeChoice((m) => (m === 'auto' ? 'unified' : m === 'unified' ? 'split' : 'auto'))}
        >
          <span className="changes-mode">{modeChoice === 'auto' ? 'auto' : modeChoice === 'unified' ? '1' : '2'}</span>
        </button>
        <button className={`icon-btn${loading ? ' pulse' : ''}`} title="refresh" onClick={onRefresh}><Refresh /></button>
        <button className="icon-btn" title="close (c)" onClick={onClose}><Close /></button>
      </div>
      {error && <div className="error-text" style={{ padding: '6px 10px' }}>{error}</div>}
      {changes && !changes.baseKnown && (
        <div className="t-note warn" style={{ margin: '6px 10px' }}>
          base branch “{changes.baseBranch}” not found — showing uncommitted changes only
        </div>
      )}
      {files.length === 0 && !error ? (
        <div className="changes-empty">{changes === null ? 'loading…' : 'no changes against the base branch'}</div>
      ) : (
        <>
          <ul className="changes-list" role="listbox" aria-label="changed files">
            {files.map((f) => {
              const slash = f.path.lastIndexOf('/');
              return (
                <li key={f.path}>
                  <button
                    data-change-path={f.path}
                    role="option"
                    aria-selected={f.path === selectedPath}
                    className={`changes-row${f.path === selectedPath ? ' selected' : ''}`}
                    onClick={() => setSelectedPath(f.path)}
                    title={f.oldPath ? `${f.oldPath} → ${f.path}` : f.path}
                  >
                    <span className={`changes-status ${f.status}`}>{STATUS_LETTER[f.status]}</span>
                    <span className="changes-path">
                      <span className="dir">{slash >= 0 ? f.path.slice(0, slash + 1) : ''}</span>{f.path.slice(slash + 1)}
                    </span>
                    <span className="changes-where">{f.where.map((w) => WHERE_LABEL[w]).join(' · ')}</span>
                    <span className="changes-counts">
                      {f.additions > 0 && <span className="add">+{f.additions}</span>}
                      {f.deletions > 0 && <span className="del">−{f.deletions}</span>}
                    </span>
                  </button>
                </li>
              );
            })}
          </ul>
          <div className="changes-viewhead">
            <span className="changes-viewpath" title={selectedPath ?? ''}>{selectedPath ?? ''}</span>
            {selection && (
              <button className="primary with-icon" onClick={ask} title="put these lines in the message box (Enter)">
                Ask about {selection.toLine - selection.fromLine + 1} line{selection.toLine === selection.fromLine ? '' : 's'}
              </button>
            )}
          </div>
          {stale && (
            <div className="t-note warn changes-banner">
              {selectedRow === null ? 'this file is no longer changed' : 'changed on disk since it was loaded'}
              <button onClick={() => { setSelection(null); setReloadNonce((n) => n + 1); }}>Reload</button>
            </div>
          )}
          <div className="changes-diff">
            {fileError && <div className="error-text" style={{ padding: 10 }}>{fileError}</div>}
            {file?.binary && <div className="changes-empty">binary or non-UTF-8 file — no preview</div>}
            {file?.truncated && !file.binary && (
              <div className="t-note warn" style={{ margin: 6 }}>
                file is {Math.round(file.size / 1024)} KB — showing the first 1 MB
              </div>
            )}
            {showDiff && selectedPath && (
              <DiffView
                path={selectedPath}
                original={original}
                modified={modified}
                mode={mode}
                onSelection={setSelection}
                onAsk={ask}
              />
            )}
            {selectedPath && !file && !fileError && <div className="changes-empty">loading…</div>}
          </div>
        </>
      )}
    </aside>
  );
}
