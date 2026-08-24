import { useCallback, useEffect, useMemo, useState } from 'react';
import GridLayout, { type Layout } from 'react-grid-layout';
import { api } from '../api/rest';
import type { SessionSummary } from '../protocol';
import SessionWidget from './SessionWidget';
import CreateSessionDialog from './CreateSessionDialog';
import TemplateManager from './TemplateManager';
import SettingsDialog from './SettingsDialog';
import { useStore } from '../store/store';
import { notificationsEnabled, toggleNotifications } from '../notify';

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
  const [systemSessionIds, setSystemSessionIds] = useState<string[]>(
    initialSessions.filter((s) => s.state !== 'CLOSED' && s.kind === 'system').map((s) => s.id),
  );
  const [showSystem, setShowSystem] = useState(() => localStorage.getItem('claude-ui.showSystem') === '1');
  const [layout, setLayout] = useState<Layout[]>(loadLayout());
  const [showCreate, setShowCreate] = useState(false);
  const [showTemplates, setShowTemplates] = useState(false);
  const [showSettings, setShowSettings] = useState(false);
  const [width, setWidth] = useState(window.innerWidth - 24);
  const removeView = useStore((s) => s.remove);

  useEffect(() => {
    const onResize = () => setWidth(window.innerWidth - 24);
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);

  const views = useStore((s) => s.views);
  useEffect(() => {
    const anyRunning = Object.values(views).some((v) => v.state === 'RUNNING' || v.state === 'WAITING_INPUT');
    if (!anyRunning) return;
    const guard = (e: BeforeUnloadEvent) => e.preventDefault();
    window.addEventListener('beforeunload', guard);
    return () => window.removeEventListener('beforeunload', guard);
  }, [views]);

  const visibleIds = useMemo(
    () => [...sessionIds, ...(showSystem ? systemSessionIds : [])],
    [sessionIds, systemSessionIds, showSystem],
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

  const onCreated = useCallback((id: string) => {
    setSessionIds((ids) => [...ids, id]);
    setShowCreate(false);
  }, []);

  const onClosed = useCallback((id: string) => {
    setSessionIds((ids) => ids.filter((x) => x !== id));
    setSystemSessionIds((ids) => ids.filter((x) => x !== id));
    removeView(id);
  }, [removeView]);

  const refresh = useCallback(async () => {
    const list = await api.listSessions();
    const live = list.filter((s) => s.state !== 'CLOSED');
    setSessionIds(live.filter((s) => s.kind !== 'system').map((s) => s.id));
    setSystemSessionIds(live.filter((s) => s.kind === 'system').map((s) => s.id));
  }, []);

  return (
    <>
      <div className="topbar">
        <h1>claude-ui</h1>
        <NotifyToggle />
        <button onClick={() => void refresh()}>Refresh</button>
        <button onClick={() => setShowTemplates(true)}>Templates</button>
        <button
          disabled={systemSessionIds.length === 0}
          title={
            systemSessionIds.length === 0
              ? 'no system sessions yet (created by backend tasks like ticket import)'
              : showSystem ? 'hide system sessions' : `show ${systemSessionIds.length} system session(s) (backend-initiated tasks)`
          }
          onClick={() => setShowSystem((v) => {
            const next = !v;
            localStorage.setItem('claude-ui.showSystem', next ? '1' : '0');
            return next;
          })}
        >
          🤖{systemSessionIds.length > 0 ? ` ${systemSessionIds.length}` : ''}
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
              <SessionWidget sessionId={id} onClosed={() => onClosed(id)} />
            </div>
          ))}
        </GridLayout>
      </div>
      {showCreate && <CreateSessionDialog onCreated={onCreated} onCancel={() => setShowCreate(false)} />}
      {showTemplates && <TemplateManager onClose={() => setShowTemplates(false)} />}
      {showSettings && <SettingsDialog onClose={() => setShowSettings(false)} />}
    </>
  );
}
