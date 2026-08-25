import type { TicketSummary } from '../protocol';

export default function TicketPickerDialog({
  tickets,
  busy,
  error,
  onPick,
  onClose,
}: {
  tickets: TicketSummary[] | null;
  busy: boolean;
  error: string;
  onPick: (ref: string) => void;
  onClose: () => void;
}) {
  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal" style={{ width: 'min(520px, 92vw)' }} onClick={(e) => e.stopPropagation()}>
        <h2>Your tickets</h2>
        {busy && (
          <div style={{ color: 'var(--muted)', fontSize: 12.5 }}>
            fetching from Linear — can take up to 45s on the first call (spinning up the system session)…
          </div>
        )}
        {error && <div className="error-text">{error}</div>}
        {!busy && !error && tickets && tickets.length === 0 && (
          <div style={{ color: 'var(--muted)', fontSize: 12.5 }}>no open tickets assigned to you</div>
        )}
        {!busy && !error && tickets && tickets.length > 0 && (
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
