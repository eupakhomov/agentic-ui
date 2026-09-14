import { useEffect } from 'react';
import type { TicketList } from '../protocol';
import { Refresh } from '../icons';
import { useBackdropDismiss } from '../hooks/useBackdropDismiss';

/** "just now" / "N min ago" — the picker header's cache-age line (Step A3). */
export function formatAge(fetchedAtIso: string): string {
  const ageMs = Date.now() - new Date(fetchedAtIso).getTime();
  const minutes = Math.floor(ageMs / 60_000);
  return minutes < 1 ? 'just now' : `${minutes} min ago`;
}

export default function TicketPickerDialog({
  list,
  busy,
  error,
  warm,
  onRefresh,
  onPick,
  onClose,
}: {
  list: TicketList | null;
  busy: boolean;
  error: string;
  warm: boolean;
  onRefresh: () => void;
  onPick: (ref: string) => void;
  onClose: () => void;
}) {
  const tickets = list?.tickets ?? null;

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'r' && !busy) onRefresh();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [busy, onRefresh]);

  const backdropDismiss = useBackdropDismiss(onClose);

  return (
    <div className="modal-backdrop" {...backdropDismiss}>
      <div className="modal" style={{ width: 'min(520px, 92vw)' }} onClick={(e) => e.stopPropagation()}>
        <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', gap: 8 }}>
          <h2 style={{ margin: 0 }}>
            Your tickets
            {list && <span style={{ color: 'var(--muted)', fontSize: 12.5, fontWeight: 'normal' }}> · as of {formatAge(list.fetchedAt)}</span>}
          </h2>
          <button
            className={`icon-btn${busy ? ' pulse' : ''}`}
            title="Refresh (r)"
            disabled={busy}
            onClick={onRefresh}
          >
            <Refresh />
          </button>
        </div>
        {busy && !tickets && (
          <div style={{ color: 'var(--muted)', fontSize: 12.5 }}>
            {warm
              ? 'fetching from Linear…'
              : 'fetching from Linear — can take up to 45s on the first call (spinning up the system session)…'}
          </div>
        )}
        {error && <div className="error-text">{error}</div>}
        {!error && !busy && tickets && tickets.length === 0 && (
          <div style={{ color: 'var(--muted)', fontSize: 12.5 }}>no open tickets assigned to you</div>
        )}
        {tickets && tickets.length > 0 && (
          <div className="ticket-list">
            {tickets.map((t) => (
              <button key={t.ref} className="ticket-row" onClick={() => onPick(t.ref)}>
                <span className="ref">{t.ref}</span>
                <span className="title">{t.title}</span>
                <span className="status">{t.status}</span>
              </button>
            ))}
          </div>
        )}
        <div className="actions">
          <button onClick={onClose}>Close</button>
        </div>
      </div>
    </div>
  );
}
