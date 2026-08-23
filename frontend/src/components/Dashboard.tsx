import { useCallback, useEffect, useMemo, useState } from 'react';
import GridLayout, { type Layout } from 'react-grid-layout';
import { api } from '../api/rest';
import type { SessionSummary } from '../protocol';
import SessionWidget from './SessionWidget';
import CreateSessionDialog from './CreateSessionDialog';
import TemplateManager from './TemplateManager';
import { useStore } from '../store/store';

const LAYOUT_KEY = 'claude-ui.layout';
const COLS = 12;

function loadLayout(): Layout[] {
  try {
    return JSON.parse(localStorage.getItem(LAYOUT_KEY) ?? '[]') as Layout[];
  } catch {
    return [];
  }
}

export default function Dashboard({ initialSessions }: { initialSessions: SessionSummary[] }) {
  const [sessionIds, setSessionIds] = useState<string[]>(
    initialSessions.filter((s) => s.state !== 'CLOSED').map((s) => s.id),
  );
  const [layout, setLayout] = useState<Layout[]>(loadLayout());
  const [showCreate, setShowCreate] = useState(false);
  const [showTemplates, setShowTemplates] = useState(false);
  const [width, setWidth] = useState(window.innerWidth - 24);
  const removeView = useStore((s) => s.remove);

  useEffect(() => {
    const onResize = () => setWidth(window.innerWidth - 24);
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);

  const fullLayout = useMemo(() => {
    const known = new Map(layout.map((l) => [l.i, l]));
    return sessionIds.map((id, index) => {
      const existing = known.get(id);
      if (existing) return existing;
      return { i: id, x: (index * 6) % COLS, y: Infinity, w: 6, h: 14, minW: 3, minH: 6 };
    });
  }, [sessionIds, layout]);

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
    removeView(id);
  }, [removeView]);

  const refresh = useCallback(async () => {
    const list = await api.listSessions();
    setSessionIds(list.filter((s) => s.state !== 'CLOSED').map((s) => s.id));
  }, []);

  return (
    <>
      <div className="topbar">
        <h1>claude-ui</h1>
        <button onClick={() => void refresh()}>Refresh</button>
        <button onClick={() => setShowTemplates(true)}>Templates</button>
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
          {sessionIds.map((id) => (
            <div key={id}>
              <SessionWidget sessionId={id} onClosed={() => onClosed(id)} />
            </div>
          ))}
        </GridLayout>
      </div>
      {showCreate && <CreateSessionDialog onCreated={onCreated} onCancel={() => setShowCreate(false)} />}
      {showTemplates && <TemplateManager onClose={() => setShowTemplates(false)} />}
    </>
  );
}
