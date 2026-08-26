import { useEffect, useMemo, useState } from 'react';
import { api, ApiError } from '../api/rest';
import type { StaleSession, TurnUsage } from '../protocol';
import CloseDialog from './CloseDialog';

type Grouping = 'day' | 'month';

const RANGE_PRESETS = [
  { label: 'Last 30 days', days: 30 },
  { label: 'Last 90 days', days: 90 },
  { label: 'Last 6 months', days: 183 },
];

function bucketKey(ts: string, grouping: Grouping): string {
  const d = new Date(ts);
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  return grouping === 'month' ? `${y}-${m}` : `${y}-${m}-${String(d.getDate()).padStart(2, '0')}`;
}

function bucketLabel(key: string, grouping: Grouping): string {
  const [y, m, d] = key.split('-').map(Number);
  const date = new Date(y!, m! - 1, d ?? 1);
  return grouping === 'month'
    ? date.toLocaleDateString(undefined, { month: 'short', year: 'numeric' })
    : date.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}

function fmtUsd(n: number): string {
  return `$${n.toFixed(2)}`;
}

export default function UsageDashboard({ onClose }: { onClose: () => void }) {
  const [turns, setTurns] = useState<TurnUsage[] | null>(null);
  const [stale, setStale] = useState<StaleSession[] | null>(null);
  const [error, setError] = useState('');
  const [grouping, setGrouping] = useState<Grouping>('day');
  const [rangeDays, setRangeDays] = useState(30);
  const [model, setModel] = useState('all');
  const [closingId, setClosingId] = useState<string | null>(null);
  const [hover, setHover] = useState<string | null>(null);

  useEffect(() => {
    void api.usage(6).then(setTurns).catch((e) => setError(e instanceof ApiError ? e.message : String(e)));
    void api.staleSessions().then(setStale).catch(() => setStale([]));
  }, []);

  const thisMonthTotal = useMemo(() => {
    if (!turns) return 0;
    const now = new Date();
    return turns
      .filter((t) => {
        const d = new Date(t.ts);
        return d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth();
      })
      .reduce((sum, t) => sum + t.costUsd, 0);
  }, [turns]);

  const models = useMemo(
    () => [...new Set((turns ?? []).map((t) => t.model).filter((m): m is string => !!m))].sort(),
    [turns],
  );

  const filtered = useMemo(() => {
    if (!turns) return [];
    const cutoff = Date.now() - rangeDays * 86400000;
    return turns.filter((t) => new Date(t.ts).getTime() >= cutoff && (model === 'all' || t.model === model));
  }, [turns, rangeDays, model]);

  const buckets = useMemo(() => {
    const map = new Map<string, number>();
    for (const t of filtered) {
      const key = bucketKey(t.ts, grouping);
      map.set(key, (map.get(key) ?? 0) + t.costUsd);
    }
    return [...map.entries()].sort(([a], [b]) => a.localeCompare(b));
  }, [filtered, grouping]);

  const bySession = useMemo(() => {
    const map = new Map<string, number>();
    for (const t of filtered) map.set(t.sessionName, (map.get(t.sessionName) ?? 0) + t.costUsd);
    return [...map.entries()].sort(([, a], [, b]) => b - a);
  }, [filtered]);

  const byModel = useMemo(() => {
    const map = new Map<string, number>();
    for (const t of filtered) {
      const key = t.model ?? '(unknown)';
      map.set(key, (map.get(key) ?? 0) + t.costUsd);
    }
    return [...map.entries()].sort(([, a], [, b]) => b - a);
  }, [filtered]);

  const maxBucket = Math.max(1, ...buckets.map(([, v]) => v));
  const chartW = 640;
  const chartH = 160;
  const barSlot = buckets.length > 0 ? chartW / buckets.length : chartW;
  const barW = Math.max(2, Math.min(24, barSlot - 2));
  const hoverBucket = hover ? buckets.find(([k]) => k === hover) : undefined;

  return (
    <>
      <div className="modal-backdrop" onClick={onClose}>
        <div className="modal wide" onClick={(e) => e.stopPropagation()}>
          <h2>Usage</h2>

          {stale && stale.length > 0 && (
            <div className="housekeeping">
              <div className="head">🧹 {stale.length} idle session{stale.length > 1 ? 's' : ''} still {stale.length > 1 ? 'have' : 'has'} a worktree on disk</div>
              <div className="stale-list">
                {stale.map((s) => (
                  <div className="stale-row" key={s.id}>
                    <span className={`dot ${s.state}`} />
                    <span className="name">{s.name}</span>
                    <span className="branch">{s.branch}</span>
                    <span className="idle">idle {Math.max(0, Math.floor((Date.now() - new Date(s.updatedAt).getTime()) / 86400000))}d</span>
                    {!s.worktreeExists && <span className="note">worktree already gone</span>}
                    {s.worktreeExists && s.dirty && <span className="note warn">⚠️ uncommitted changes</span>}
                    <button onClick={() => setClosingId(s.id)}>Close…</button>
                  </div>
                ))}
              </div>
            </div>
          )}

          {error && <div className="error-text">{error}</div>}

          {!turns ? (
            <p style={{ color: 'var(--muted)' }}>Loading…</p>
          ) : (
            <>
              <div className="stat-tile">
                <div className="label">This month ({new Date().toLocaleDateString(undefined, { month: 'long' })})</div>
                <div className="value">{fmtUsd(thisMonthTotal)}</div>
              </div>

              <div className="usage-filters">
                <select value={rangeDays} onChange={(e) => setRangeDays(Number(e.target.value))}>
                  {RANGE_PRESETS.map((p) => <option key={p.days} value={p.days}>{p.label}</option>)}
                </select>
                <select value={model} onChange={(e) => setModel(e.target.value)}>
                  <option value="all">All models</option>
                  {models.map((m) => <option key={m} value={m}>{m}</option>)}
                </select>
                <div className="grouping-toggle">
                  <button className={grouping === 'day' ? 'active' : ''} onClick={() => setGrouping('day')}>Day</button>
                  <button className={grouping === 'month' ? 'active' : ''} onClick={() => setGrouping('month')}>Month</button>
                </div>
              </div>

              {buckets.length === 0 ? (
                <p style={{ color: 'var(--muted)' }}>No usage in this range.</p>
              ) : (
                <div className="usage-chart-wrap">
                  <span className="chart-max-label">{fmtUsd(maxBucket)}</span>
                  <svg viewBox={`0 0 ${chartW} ${chartH + 20}`} width="100%" height={chartH + 20} preserveAspectRatio="none">
                    <line x1={0} y1={chartH} x2={chartW} y2={chartH} className="baseline" />
                    {buckets.map(([key, value], i) => {
                      const h = (value / maxBucket) * (chartH - 8);
                      const x = i * barSlot + (barSlot - barW) / 2;
                      const y = chartH - h;
                      return (
                        <g key={key} onPointerEnter={() => setHover(key)} onPointerLeave={() => setHover((h2) => (h2 === key ? null : h2))}>
                          <rect x={x - 4} y={0} width={barW + 8} height={chartH} fill="transparent" />
                          <rect x={x} y={y} width={barW} height={Math.max(1, h)} rx={4} className={`bar${hover === key ? ' hovered' : ''}`} />
                          {buckets.length <= 14 && (
                            <text x={x + barW / 2} y={chartH + 14} textAnchor="middle" className="bucket-label">
                              {bucketLabel(key, grouping)}
                            </text>
                          )}
                        </g>
                      );
                    })}
                  </svg>
                  {hoverBucket && (
                    <div
                      className="chart-tooltip"
                      style={{ left: `${((buckets.findIndex(([k]) => k === hover) + 0.5) / buckets.length) * 100}%` }}
                    >
                      <div className="v">{fmtUsd(hoverBucket[1])}</div>
                      <div className="l">{bucketLabel(hoverBucket[0], grouping)}</div>
                    </div>
                  )}
                </div>
              )}

              <div className="usage-tables">
                <div>
                  <h3>By session</h3>
                  <table className="usage-table">
                    <tbody>
                      {bySession.map(([name, cost]) => (
                        <tr key={name}><td>{name}</td><td className="num">{fmtUsd(cost)}</td></tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                <div>
                  <h3>By model</h3>
                  <table className="usage-table">
                    <tbody>
                      {byModel.map(([name, cost]) => (
                        <tr key={name}><td>{name}</td><td className="num">{fmtUsd(cost)}</td></tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            </>
          )}

          <div className="actions">
            <button onClick={onClose}>Close</button>
          </div>
        </div>
      </div>
      {closingId && (
        <CloseDialog
          sessionId={closingId}
          onClosed={() => {
            setStale((list) => list?.filter((s) => s.id !== closingId) ?? null);
            setClosingId(null);
          }}
          onCancel={() => setClosingId(null)}
        />
      )}
    </>
  );
}
