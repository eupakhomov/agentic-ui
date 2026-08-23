import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { api, ApiError } from '../api/rest';
import { WsSession } from '../api/ws';
import type { Envelope, PermissionMode, SessionEntity } from '../protocol';
import { useStore } from '../store/store';
import { notify } from '../notify';
import Transcript from './Transcript';
import CloseDialog from './CloseDialog';
import GitPanel from './GitPanel';

const MODE_CYCLE: PermissionMode[] = ['default', 'acceptEdits', 'plan'];
const MODE_LABEL: Record<PermissionMode, string> = {
  default: 'Ask edits',
  acceptEdits: 'Auto-accept',
  plan: 'Plan',
  bypassPermissions: 'Bypass',
};

export default function SessionWidget({ sessionId, onClosed }: { sessionId: string; onClosed: () => void }) {
  const view = useStore((s) => s.views[sessionId]);
  const apply = useStore((s) => s.apply);
  const setWsStatus = useStore((s) => s.setWsStatus);
  const seed = useStore((s) => s.seed);
  const [entity, setEntity] = useState<SessionEntity | null>(null);
  const [input, setInput] = useState('');
  const [closing, setClosing] = useState(false);
  const [showGit, setShowGit] = useState(false);
  const [actionError, setActionError] = useState('');
  const wsRef = useRef<WsSession | null>(null);
  const nameRef = useRef<string>('');
  const liveRef = useRef(false);

  useEffect(() => {
    api.sessionDetail(sessionId).then((d) => {
      setEntity(d.session);
      seed(sessionId, (v) => ({
        ...v,
        permissionMode: d.session.permissionMode,
        capabilities: v.capabilities ?? d.session.capabilities,
        model: v.model ?? d.session.model,
        name: v.name ?? d.session.name,
        costBudgetUsd: v.costBudgetUsd ?? d.session.costBudgetUsd,
      }));
    }).catch(() => setEntity(null));
    const onEvent = (e: Envelope) => {
      apply(sessionId, e);
      // desktop notifications only for live events (not replay) on an unwatched tab
      if (!liveRef.current) {
        if (e.type === 'replay_complete' || e.seq === 0) liveRef.current = true;
        return;
      }
      const who = nameRef.current || 'session';
      if (e.type === 'permission_request') {
        notify(`${who} needs your input`, `${e.payload['toolName']} permission requested`);
      } else if (e.type === 'turn_complete') {
        notify(`${who} finished`, 'the agent completed its turn');
      } else if (e.type === 'state_changed' && e.payload['state'] === 'CRASHED') {
        notify(`${who} crashed`, 'the session needs a resume');
      }
    };
    const ws = new WsSession(sessionId, onEvent, (s) => {
      setWsStatus(sessionId, s);
      if (s !== 'open') liveRef.current = false;
    });
    wsRef.current = ws;
    return () => ws.stop();
  }, [sessionId, apply, setWsStatus, seed]);

  const send = useCallback((cmd: Record<string, unknown>) => wsRef.current?.send(cmd), []);

  const submit = useCallback(() => {
    const text = input.trim();
    if (!text) return;
    send({ type: 'user_message', text });
    setInput('');
  }, [input, send]);

  const cycleMode = useCallback(() => {
    if (!view) return;
    const supported = MODE_CYCLE.filter((m) => view.capabilities?.permissionModes.includes(m) ?? true);
    const current = supported.indexOf(view.permissionMode);
    const next = supported[(current + 1) % supported.length]!;
    send({ type: 'set_permission_mode', mode: next });
  }, [view, send]);

  const resume = useCallback(async () => {
    setActionError('');
    try {
      await api.resumeSession(sessionId);
    } catch (e) {
      setActionError(e instanceof ApiError ? e.message : String(e));
    }
  }, [sessionId]);

  const state = view?.state ?? 'CREATING';
  const running = state === 'RUNNING' || state === 'WAITING_INPUT';
  const budget = view?.costBudgetUsd ?? entity?.costBudgetUsd ?? null;
  nameRef.current = view?.name ?? entity?.name ?? '';
  const widgetClass = useMemo(() => {
    if (state === 'WAITING_INPUT') return 'widget waiting';
    if (state === 'CRASHED' || state === 'FAILED') return 'widget crashed';
    return 'widget';
  }, [state]);

  if (!view) return <div className="widget"><div className="overlay">loading…</div></div>;

  return (
    <div className={widgetClass}>
      <div className="widget-header">
        <span className={`dot ${state}`} title={state} />
        <span
          className="name"
          title={`${view.name ?? entity?.name ?? ''} — double-click to rename`}
          onDoubleClick={() => {
            const next = prompt('Session name:', view.name ?? entity?.name ?? '');
            if (next?.trim()) void api.patchSession(sessionId, { name: next.trim() });
          }}
        >
          {view.name ?? entity?.name ?? sessionId.slice(0, 8)}
        </span>
        {entity?.repoPath && (
          <span className="chip" title={entity.repoPath}>
            {entity.repoPath.split('/').pop()}
          </span>
        )}
        <span className="chip" title="branch">{entity?.branch}</span>
        {(view.model ?? entity?.model) && <span className="chip">{view.model ?? entity?.model}</span>}
        {entity?.ecosystemPath && <span className="chip" title={`context: ${entity.ecosystemPath}`}>🌐</span>}
        <span className="spacer" />
        <span
          className={`chip clickable mode-${view.permissionMode}`}
          title="click to switch permission mode"
          onClick={cycleMode}
          onMouseDown={(e) => e.stopPropagation()}
        >
          {MODE_LABEL[view.permissionMode]}
        </span>
        <span
          className={`chip${budget !== null ? ' clickable' : ''}`}
          title={budget !== null
            ? `$${view.costToDate.toFixed(3)} of $${budget} budget — click to change`
            : 'session cost to date'}
          onMouseDown={(e) => e.stopPropagation()}
          onClick={() => {
            if (budget === null) return;
            const next = prompt('Cost budget (USD):', String(budget));
            if (next && !Number.isNaN(Number(next))) void api.patchSession(sessionId, { costBudgetUsd: Number(next) });
          }}
        >
          ${view.costToDate.toFixed(3)}{budget !== null ? ` / $${budget}` : ''}
        </span>
        {state === 'CRASHED' && (
          <button onMouseDown={(e) => e.stopPropagation()} onClick={() => void resume()}>Resume</button>
        )}
        <button
          onMouseDown={(e) => e.stopPropagation()}
          onClick={() => setShowGit((v) => !v)}
          title="git panel"
        >⎇</button>
        <button
          onMouseDown={(e) => e.stopPropagation()}
          onClick={() => setClosing(true)}
          title="close session"
        >✕</button>
      </div>
      <div className="widget-body">
        {view.wsStatus !== 'open' && state !== 'CLOSED' && (
          <div className="overlay">disconnected — reconnecting…</div>
        )}
        {showGit && <GitPanel sessionId={sessionId} onClose={() => setShowGit(false)} />}
        <Transcript items={view.transcript} onPermission={(requestId, response) => send({ type: 'permission_response', requestId, ...response })} />
        <div className="inputbar">
          {actionError && <div className="error-text">{actionError}</div>}
          {view.queued.length > 0 && (
            <div className="queue-chips">
              {view.queued.map((q) => (
                <span key={q.pos} className="queue-chip" title={q.text}>
                  {q.text.length > 40 ? q.text.slice(0, 40) + '…' : q.text}
                  <button title="remove from queue" onClick={() => void api.deleteQueued(sessionId, q.pos)}>✕</button>
                </span>
              ))}
            </div>
          )}
          <div className="row">
            <textarea
              placeholder={state === 'PARKED' ? 'parked — sending wakes the session…' : running ? 'type to queue a message…' : 'message…'}
              value={input}
              rows={Math.min(5, input.split('\n').length)}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault();
                  submit();
                }
              }}
              disabled={state === 'CRASHED' || state === 'FAILED' || state === 'CLOSED'}
            />
            {running && view.capabilities?.interrupt !== false && (
              <button className="danger" title="interrupt" onClick={() => send({ type: 'interrupt' })}>⏹</button>
            )}
            <button className="primary" onClick={submit} disabled={state === 'CRASHED' || state === 'FAILED'}>Send</button>
          </div>
        </div>
      </div>
      {closing && (
        <CloseDialog
          sessionId={sessionId}
          onClosed={onClosed}
          onCancel={() => setClosing(false)}
        />
      )}
    </div>
  );
}
