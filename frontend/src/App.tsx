import { useCallback, useEffect, useState } from 'react';
import { api, ApiError, setToken, token } from './api/rest';
import type { SessionSummary } from './protocol';
import Dashboard from './components/Dashboard';

type Gate = 'checking' | 'needed' | 'passed';

export default function App() {
  const [gate, setGate] = useState<Gate>('checking');
  const [gateError, setGateError] = useState('');
  const [tokenInput, setTokenInput] = useState('');
  const [sessions, setSessions] = useState<SessionSummary[] | null>(null);

  const tryEnter = useCallback(async () => {
    try {
      const list = await api.listSessions();
      setSessions(list);
      setGate('passed');
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        setToken(null);
        setGate('needed');
      } else {
        setGateError(e instanceof Error ? e.message : String(e));
        setGate('needed');
      }
    }
  }, []);

  useEffect(() => {
    void tryEnter();
  }, [tryEnter]);

  if (gate === 'checking') {
    return <div className="gate">connecting…</div>;
  }
  if (gate === 'needed' || sessions === null) {
    return (
      <div className="gate">
        <div className="modal">
          <h2>claude-ui</h2>
          <p style={{ color: 'var(--muted)' }}>
            {token() === null ? 'Enter the access token for this dashboard.' : ''}
          </p>
          {gateError && <div className="error-text">{gateError}</div>}
          <form
            onSubmit={(e) => {
              e.preventDefault();
              setGateError('');
              setToken(tokenInput.trim() || null);
              void tryEnter();
            }}
          >
            <input
              style={{ width: '100%' }}
              type="password"
              placeholder="access token"
              value={tokenInput}
              onChange={(e) => setTokenInput(e.target.value)}
              autoFocus
            />
            <div className="actions" style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 12 }}>
              <button className="primary" type="submit">Connect</button>
            </div>
          </form>
        </div>
      </div>
    );
  }
  return <Dashboard initialSessions={sessions} />;
}
