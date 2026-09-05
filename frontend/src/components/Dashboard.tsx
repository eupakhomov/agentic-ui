import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import GridLayout, { type Layout } from 'react-grid-layout';
import { api } from '../api/rest';
import type { SessionSummary } from '../protocol';
import SessionWidget from './SessionWidget';
import CreateSessionDialog from './CreateSessionDialog';
import TemplateManager from './TemplateManager';
import SettingsDialog from './SettingsDialog';
import UsageDashboard from './UsageDashboard';
import LibraryDialog from './LibraryDialog';
import MemoryDialog from './MemoryDialog';
import { useStore } from '../store/store';
import { notificationsEnabled, notify, toggleNotifications } from '../notify';

const LAYOUT_KEY = 'claude-ui.layout';
const COLS = 12;

function NotifyToggle() {
  const [on, setOn] = useState(notificationsEnabled());
  return (
    <button
      title={on ? 'desktop notifications on (finished / needs input / crashed)' : 'enable desktop notifications'}
      onClick={() => void toggleNotifications().then(setOn)}
    >
      {on ? '🔔' : '🔕'}
    </button>
  );
}

function loadLayout(): Layout[] {
  try {
    return JSON.parse(localStorage.getItem(LAYOUT_KEY) ?? '[]') as Layout[];
  } catch {
    return [];
  }
}

export default function Dashboard({ initialSessions }: { initialSessions: SessionSummary[] }) {
  const [sessionIds, setSessionIds] = useState<string[]>(
    initialSessions.filter((s) => s.state !== 'CLOSED' && s.kind !== 'system').map((s) => s.id),
  );
  const [systemSessions, setSystemSessions] = useState<SessionSummary[]>(
    initialSessions.filter((s) => s.state !== 'CLOSED' && s.kind === 'system'),
  );
  const [showSystem, setShowSystem] = useState(() => localStorage.getItem('claude-ui.showSystem') === '1');
  const [layout, setLayout] = useState<Layout[]>(loadLayout());
  const [showCreate, setShowCreate] = useState(false);
  const [showTemplates, setShowTemplates] = useState(false);
  const [showSettings, setShowSettings] = useState(false);
  const [showUsage, setShowUsage] = useState(false);
  const [showLibrary, setShowLibrary] = useState(false);
  const [showMemory, setShowMemory] = useState(false);
  const [staleCount, setStaleCount] = useState(0);
  const [discoveryCount, setDiscoveryCount] = useState(0);
  const [pendingMemoryCount, setPendingMemoryCount] = useState(0);
  const [width, setWidth] = useState(window.innerWidth - 24);
  // seeds a just-created session's compose box (e.g. an edited-but-unsent ticket import
  // draft); read once by SessionWidget's initial state, no cleanup needed afterward
  const [pendingDraft, setPendingDraft] = useState<{ id: string; text: string } | null>(null);
  const removeView = useStore((s) => s.remove);

  useEffect(() => {
    const onResize = () => setWidth(window.innerWidth - 24);
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);

  const refreshStale = useCallback(() => {
    void api.staleSessions().then((list) => setStaleCount(list.length)).catch(() => {});
  }, []);
  useEffect(() => refreshStale(), [refreshStale]);

  // library source-sync discoveries drive the 📚 badge; a rising count on a background
  // tab also fires a desktop notification (sync is server-side, so polling is the transport)
  const prevDiscoveries = useRef(-1);
  const refreshDiscoveries = useCallback(() => {
    void api.librarySources().then((sources) => {
      const total = sources.reduce((n, s) => n + s.discoveries.length, 0);
      if (prevDiscoveries.current >= 0 && total > prevDiscoveries.current) {
        const withNew = sources.filter((s) => s.discoveries.length > 0).map((s) => s.ref).join(', ');
        notify('Skill library', `${total} new file(s) found in ${withNew}`);
      }
      prevDiscoveries.current = total;
      setDiscoveryCount(total);
    }).catch(() => {});
  }, []);
  useEffect(() => {
    refreshDiscoveries();
    const timer = setInterval(refreshDiscoveries, 60_000);
    return () => clearInterval(timer);
  }, [refreshDiscoveries]);

  // pending memory-reflection proposals drive the 🧠 badge, same polling shape as 📚 above
  const prevPending = useRef(-1);
  const refreshPendingMemory = useCallback(() => {
    void api.memoryProposals('PENDING').then((list) => {
      if (prevPending.current >= 0 && list.length > prevPending.current) {
        notify('Memory', `${list.length} reflection${list.length > 1 ? 's' : ''} awaiting approval`);
      }
      prevPending.current = list.length;
      setPendingMemoryCount(list.length);
    }).catch(() => {});
  }, []);
  useEffect(() => {
    refreshPendingMemory();
    const timer = setInterval(refreshPendingMemory, 60_000);
    return () => clearInterval(timer);
  }, [refreshPendingMemory]);

  const views = useStore((s) => s.views);
  useEffect(() => {
    const anyRunning = Object.values(views).some((v) => v.state === 'RUNNING' || v.state === 'WAITING_INPUT');
    if (!anyRunning) return;
    const guard = (e: BeforeUnloadEvent) => e.preventDefault();
    window.addEventListener('beforeunload', guard);
    return () => window.removeEventListener('beforeunload', guard);
  }, [views]);

  // views[id] only exists once that session's widget has mounted its own WebSocket (see
  // SessionWidget); fall back to the last-fetched summary state until then, so the topbar
  // dot doesn't go stale after e.g. a resume that happened while the panel was open.
  const systemState = systemSessions[0] && (views[systemSessions[0].id]?.state ?? systemSessions[0].state);

  const visibleIds = useMemo(
    () => [...sessionIds, ...(showSystem ? systemSessions.map((s) => s.id) : [])],
    [sessionIds, systemSessions, showSystem],
  );

  const fullLayout = useMemo(() => {
    const known = new Map(layout.map((l) => [l.i, l]));
    return visibleIds.map((id, index) => {
      const existing = known.get(id);
      if (existing) return existing;
      return { i: id, x: (index * 6) % COLS, y: Infinity, w: 6, h: 14, minW: 3, minH: 6 };
    });
  }, [visibleIds, layout]);

  const onLayoutChange = useCallback((next: Layout[]) => {
    setLayout(next);
    localStorage.setItem(LAYOUT_KEY, JSON.stringify(next));
  }, []);

  const onCreated = useCallback((id: string, draftInput?: string) => {
    setSessionIds((ids) => [...ids, id]);
    setPendingDraft(draftInput ? { id, text: draftInput } : null);
    setShowCreate(false);
    // the create flow (e.g. ticket import) may have spun up the system session before this
    // page ever loaded it — refresh so the topbar 🤖 toggle reflects it immediately
    void api.listSessions().then((list) => {
      setSystemSessions(list.filter((s) => s.kind === 'system' && s.state !== 'CLOSED'));
    });
  }, []);

  const onClosed = useCallback((id: string) => {
    setSessionIds((ids) => ids.filter((x) => x !== id));
    setSystemSessions((list) => list.filter((s) => s.id !== id));
    removeView(id);
  }, [removeView]);

  const refresh = useCallback(async () => {
    const list = await api.listSessions();
    const live = list.filter((s) => s.state !== 'CLOSED');
    setSessionIds(live.filter((s) => s.kind !== 'system').map((s) => s.id));
    setSystemSessions(live.filter((s) => s.kind === 'system'));
  }, []);

  return (
    <>
      <div className="topbar">
        <h1>claude-ui</h1>
        <NotifyToggle />
        <button onClick={() => void refresh()}>Refresh</button>
        <button onClick={() => setShowTemplates(true)}>Templates</button>
        <button
          disabled={systemSessions.length === 0}
          title={
            systemSessions.length === 0
              ? 'no system session yet (created by backend tasks like ticket import)'
              : showSystem ? 'hide system session' : `show system session (${systemState!.toLowerCase()})`
          }
          style={{ display: 'flex', alignItems: 'center', gap: 5 }}
          onClick={() => setShowSystem((v) => {
            const next = !v;
            localStorage.setItem('claude-ui.showSystem', next ? '1' : '0');
            return next;
          })}
        >
          🤖{systemState && <span className={`dot ${systemState}`} />}
        </button>
        <button
          title={staleCount > 0 ? `Usage — ${staleCount} idle session${staleCount > 1 ? 's' : ''} to clean up` : 'Usage'}
          style={{ display: 'flex', alignItems: 'center', gap: 5 }}
          onClick={() => setShowUsage(true)}
        >
          📊{staleCount > 0 && <span className="count-badge">{staleCount}</span>}
        </button>
        <button
          title={discoveryCount > 0 ? `Skill library — ${discoveryCount} new file(s) in synced sources` : 'Skill library'}
          style={{ display: 'flex', alignItems: 'center', gap: 5 }}
          onClick={() => setShowLibrary(true)}
        >
          📚{discoveryCount > 0 && <span className="count-badge">{discoveryCount}</span>}
        </button>
        <button
          title={pendingMemoryCount > 0 ? `Memory — ${pendingMemoryCount} reflection(s) awaiting approval` : 'Memory'}
          style={{ display: 'flex', alignItems: 'center', gap: 5 }}
          onClick={() => setShowMemory(true)}
        >
          🧠{pendingMemoryCount > 0 && <span className="count-badge">{pendingMemoryCount}</span>}
        </button>
        <button title="Settings" onClick={() => setShowSettings(true)}>⚙️</button>
        <button className="primary" onClick={() => setShowCreate(true)}>+ New Session</button>
      </div>
      <div className="grid-wrap">
        <GridLayout
          className="layout"
          layout={fullLayout}
          cols={COLS}
          rowHeight={30}
          width={width}
          onLayoutChange={onLayoutChange}
          draggableHandle=".widget-header"
        >
          {visibleIds.map((id) => (
            <div key={id}>
              <SessionWidget
                sessionId={id}
                initialInput={pendingDraft?.id === id ? pendingDraft.text : undefined}
                onClosed={() => onClosed(id)}
                onDuplicated={onCreated}
              />
            </div>
          ))}
        </GridLayout>
      </div>
      {showCreate && <CreateSessionDialog onCreated={onCreated} onCancel={() => setShowCreate(false)} />}
      {showTemplates && <TemplateManager onClose={() => setShowTemplates(false)} />}
      {showSettings && <SettingsDialog onClose={() => setShowSettings(false)} />}
      {showUsage && <UsageDashboard onClose={() => { setShowUsage(false); refreshStale(); }} />}
      {showLibrary && <LibraryDialog onClose={() => { setShowLibrary(false); refreshDiscoveries(); }} />}
      {showMemory && <MemoryDialog onClose={() => { setShowMemory(false); refreshPendingMemory(); }} />}
    </>
  );
}
