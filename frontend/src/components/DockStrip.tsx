import { useStore } from '../store/store';

/** Bottom strip of chips for minimized sessions — stays connected/updating (hidden, not unmounted). */
export default function DockStrip({ ids, onRestore }: { ids: string[]; onRestore: (id: string) => void }) {
  const views = useStore((s) => s.views);
  if (ids.length === 0) return null;
  return (
    <div className="dock-strip">
      {ids.map((id) => {
        const v = views[id];
        return (
          <button
            key={id}
            className={`dock-chip${v?.state === 'WAITING_INPUT' ? ' pulse' : ''}`}
            onClick={() => onRestore(id)}
            title={`restore ${v?.name ?? id}`}
          >
            <span className={`dot ${v?.state ?? 'CREATING'}`} />
            <span className="dock-name">{v?.name ?? id.slice(0, 8)}</span>
            <span className="dock-cost">${(v?.costToDate ?? 0).toFixed(2)}</span>
          </button>
        );
      })}
    </div>
  );
}
