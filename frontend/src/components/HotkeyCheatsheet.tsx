const ROWS: [string, string][] = [
  ['?', 'this cheatsheet'],
  ['n', 'new session'],
  ['j / k  (also ] / [)', 'focus next / previous widget'],
  ['1 – 9', 'focus widget N (grid order)'],
  ['Enter / i', 'focus the composer'],
  ['y / d', 'approve / deny the oldest pending permission'],
  ['g', 'toggle git panel'],
  ['f', 'maximize / restore widget'],
  ['x', 'minimize widget'],
  ['e', 'Exposé (all sessions)'],
  ['m', 'memory'],
  ['l', 'skill library'],
  ['u', 'usage'],
  ['t', 'templates'],
  [',', 'settings'],
  ['Esc', 'close dialog → blur composer → exit maximize/Exposé'],
];

export default function HotkeyCheatsheet({ onClose }: { onClose: () => void }) {
  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h2>Keyboard shortcuts</h2>
        <table className="cheatsheet-table">
          <tbody>
            {ROWS.map(([key, desc]) => (
              <tr key={key}>
                <td><kbd>{key}</kbd></td>
                <td>{desc}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <p style={{ color: 'var(--muted)' }}>
          Inert while typing in a field or while a dialog is open (Esc still works).
        </p>
        <div className="actions">
          <button className="primary" onClick={onClose}>Close</button>
        </div>
      </div>
    </div>
  );
}
