import { create } from 'zustand';
import type { Capabilities, Envelope, PermissionMode, QueuedMessage, SessionState } from '../protocol';
import type { WsStatus } from '../api/ws';

// ---------------------------------------------------------------------------
// Transcript view model: WS events reduce into a flat item list per session.
// ---------------------------------------------------------------------------

export type TranscriptItem =
  | { kind: 'user'; text: string }
  | { kind: 'thinking'; text: string; estimatedTokens: number; done: boolean }
  | { kind: 'text'; text: string; done: boolean }
  | { kind: 'tool'; toolUseId: string; name: string; input: unknown; output?: string; isError?: boolean; truncated?: boolean }
  | { kind: 'permission'; requestId: string; toolName: string; input: Record<string, unknown>; plan: string | null; decision: 'allow' | 'deny' | null }
  | { kind: 'turn_footer'; stopReason: string; costUsd: number; durationMs: number }
  | { kind: 'note'; level: 'info' | 'warn' | 'error'; text: string };

export interface SessionView {
  state: SessionState;
  permissionMode: PermissionMode;
  capabilities: Capabilities | null;
  model: string | null;
  transcript: TranscriptItem[];
  queued: QueuedMessage[];
  costToDate: number;
  wsStatus: WsStatus;
  pendingPermission: { requestId: string; toolName: string; input: Record<string, unknown>; plan: string | null } | null;
}

const emptyView = (): SessionView => ({
  state: 'CREATING',
  permissionMode: 'default',
  capabilities: null,
  model: null,
  transcript: [],
  queued: [],
  costToDate: 0,
  wsStatus: 'connecting',
  pendingPermission: null,
});

function last<T>(arr: T[]): T | undefined {
  return arr[arr.length - 1];
}

/** Applies one journal event to a session view (mutates a fresh copy). */
function reduce(view: SessionView, e: Envelope): SessionView {
  const v: SessionView = { ...view, transcript: [...view.transcript] };
  const t = v.transcript;
  const p = e.payload;

  switch (e.type) {
    case 'state_changed':
      v.state = p['state'] as SessionState;
      if (v.state === 'IDLE' || v.state === 'CRASHED') v.pendingPermission = null;
      break;
    case 'ready':
      v.capabilities = p['capabilities'] as Capabilities;
      break;
    case 'system_init':
      v.model = (p['model'] as string) ?? v.model;
      break;
    case 'user_message':
      t.push({ kind: 'user', text: p['text'] as string });
      break;
    case 'stream_delta': {
      const deltaType = p['deltaType'] as 'text' | 'thinking';
      const text = p['text'] as string;
      const tail = last(t);
      if (deltaType === 'thinking') {
        if (tail?.kind === 'thinking' && !tail.done) t[t.length - 1] = { ...tail, text: tail.text + text };
        else t.push({ kind: 'thinking', text, estimatedTokens: 0, done: false });
      } else {
        if (tail?.kind === 'text' && !tail.done) t[t.length - 1] = { ...tail, text: tail.text + text };
        else {
          markThinkingDone(t);
          t.push({ kind: 'text', text, done: false });
        }
      }
      break;
    }
    case 'thinking_progress': {
      const tail = last(t);
      if (tail?.kind === 'thinking' && !tail.done) {
        t[t.length - 1] = { ...tail, estimatedTokens: p['estimatedTokens'] as number };
      }
      break;
    }
    case 'assistant_message': {
      // close the streamed text block; recover text if deltas were absent (edge cases)
      const tail = last(t);
      if (tail?.kind === 'text' && !tail.done) t[t.length - 1] = { ...tail, done: true };
      else {
        const blocks = (p['content'] as { type: string; text?: string }[] | undefined) ?? [];
        const text = blocks.filter((b) => b.type === 'text' && b.text).map((b) => b.text).join('');
        if (text && tail?.kind !== 'text') t.push({ kind: 'text', text, done: true });
      }
      markThinkingDone(t);
      break;
    }
    case 'tool_started':
      t.push({ kind: 'tool', toolUseId: p['toolUseId'] as string, name: p['name'] as string, input: p['input'] });
      break;
    case 'tool_result': {
      const id = p['toolUseId'] as string;
      for (let i = t.length - 1; i >= 0; i--) {
        const item = t[i]!;
        if (item.kind === 'tool' && item.toolUseId === id) {
          t[i] = { ...item, output: p['output'] as string, isError: p['isError'] as boolean, truncated: p['truncated'] as boolean };
          break;
        }
      }
      break;
    }
    case 'permission_request': {
      const input = p['input'] as Record<string, unknown>;
      const toolName = p['toolName'] as string;
      const plan = toolName === 'ExitPlanMode' && typeof input['plan'] === 'string' ? (input['plan'] as string) : null;
      const requestId = p['requestId'] as string;
      t.push({ kind: 'permission', requestId, toolName, input, plan, decision: null });
      v.pendingPermission = { requestId, toolName, input, plan };
      break;
    }
    case 'permission_response': {
      const id = p['requestId'] as string;
      for (let i = t.length - 1; i >= 0; i--) {
        const item = t[i]!;
        if (item.kind === 'permission' && item.requestId === id) {
          t[i] = { ...item, decision: p['behavior'] as 'allow' | 'deny' };
          break;
        }
      }
      if (v.pendingPermission?.requestId === id) v.pendingPermission = null;
      break;
    }
    case 'permission_mode_changed':
      v.permissionMode = p['mode'] as PermissionMode;
      break;
    case 'turn_complete': {
      markThinkingDone(t);
      const tail = last(t);
      if (tail?.kind === 'text' && !tail.done) t[t.length - 1] = { ...tail, done: true };
      const cost = (p['costUsd'] as number) ?? 0;
      t.push({ kind: 'turn_footer', stopReason: p['stopReason'] as string, costUsd: cost, durationMs: p['durationMs'] as number });
      v.costToDate = view.costToDate + cost;
      break;
    }
    case 'queue_updated':
      v.queued = (p['queued'] as QueuedMessage[]) ?? [];
      break;
    case 'warning':
      t.push({ kind: 'note', level: 'warn', text: p['message'] as string });
      break;
    case 'error':
      t.push({ kind: 'note', level: p['fatal'] ? 'error' : 'warn', text: p['message'] as string });
      break;
    case 'interrupt':
      t.push({ kind: 'note', level: 'info', text: 'interrupted by user' });
      break;
    default:
      break; // exiting, command echoes, future types
  }
  return v;
}

function markThinkingDone(t: TranscriptItem[]): void {
  for (let i = t.length - 1; i >= 0; i--) {
    const item = t[i]!;
    if (item.kind === 'thinking' && !item.done) {
      t[i] = { ...item, done: true };
      return;
    }
    if (item.kind === 'user') return;
  }
}

// ---------------------------------------------------------------------------
// Store
// ---------------------------------------------------------------------------

interface Store {
  views: Record<string, SessionView>;
  apply: (sessionId: string, e: Envelope) => void;
  setWsStatus: (sessionId: string, status: WsStatus) => void;
  seed: (sessionId: string, seedFn: (v: SessionView) => SessionView) => void;
  remove: (sessionId: string) => void;
}

export const useStore = create<Store>((set) => ({
  views: {},
  apply: (sessionId, e) =>
    set((s) => ({ views: { ...s.views, [sessionId]: reduce(s.views[sessionId] ?? emptyView(), e) } })),
  setWsStatus: (sessionId, status) =>
    set((s) => ({ views: { ...s.views, [sessionId]: { ...(s.views[sessionId] ?? emptyView()), wsStatus: status } } })),
  seed: (sessionId, seedFn) =>
    set((s) => ({ views: { ...s.views, [sessionId]: seedFn(s.views[sessionId] ?? emptyView()) } })),
  remove: (sessionId) =>
    set((s) => {
      const views = { ...s.views };
      delete views[sessionId];
      return { views };
    }),
}));
