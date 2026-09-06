import { beforeEach, describe, expect, it } from 'vitest';
import { useStore } from './store';
import type { Envelope } from '../protocol';

// docs/plan/phase-9-production-hardening.md T6: event application in store/store.ts.
// `reduce` itself isn't exported, so these drive it the same way the app does — through
// the store's public apply()/seed() actions — and assert on the resulting SessionView.

const SID = 's1';

function env(type: string, payload: Record<string, unknown> = {}, seq = 1): Envelope {
  return { seq, type, payload };
}

beforeEach(() => {
  useStore.setState({ views: {}, focusedId: null });
});

describe('state_changed', () => {
  it('updates state', () => {
    useStore.getState().apply(SID, env('state_changed', { state: 'RUNNING' }));
    expect(useStore.getState().views[SID]?.state).toBe('RUNNING');
  });

  it('clears a pending permission when the session goes IDLE', () => {
    useStore.getState().apply(SID, env('permission_request', {
      requestId: 'r1', toolName: 'Bash', input: {},
    }));
    expect(useStore.getState().views[SID]?.pendingPermission).not.toBeNull();

    useStore.getState().apply(SID, env('state_changed', { state: 'IDLE' }));

    expect(useStore.getState().views[SID]?.pendingPermission).toBeNull();
  });

  it('clears a pending permission on CRASHED too', () => {
    useStore.getState().apply(SID, env('permission_request', { requestId: 'r1', toolName: 'Bash', input: {} }));
    useStore.getState().apply(SID, env('state_changed', { state: 'CRASHED' }));
    expect(useStore.getState().views[SID]?.pendingPermission).toBeNull();
  });
});

describe('queue_updated', () => {
  it('replaces the queued list', () => {
    useStore.getState().apply(SID, env('queue_updated', { queued: [{ pos: 1, text: 'a' }] }));
    expect(useStore.getState().views[SID]?.queued).toEqual([{ pos: 1, text: 'a' }]);

    useStore.getState().apply(SID, env('queue_updated', { queued: [] }));
    expect(useStore.getState().views[SID]?.queued).toEqual([]);
  });
});

describe('reflection events', () => {
  it('reflection_proposed adds an info note with the episode text', () => {
    useStore.getState().apply(SID, env('reflection_proposed', { episode: 'did a thing' }));
    const t = useStore.getState().views[SID]!.transcript;
    expect(t.at(-1)).toMatchObject({ kind: 'note', level: 'info' });
    expect((t.at(-1) as { text: string }).text).toContain('did a thing');
  });

  it('reflection_complete summarizes created/updated/archived counts', () => {
    useStore.getState().apply(SID, env('reflection_complete', {
      episode: 'summary', created: ['a', 'b'], updated: ['c'], archived: [],
    }));
    const note = useStore.getState().views[SID]!.transcript.at(-1) as { text: string };
    expect(note.text).toContain('2 created');
    expect(note.text).toContain('1 updated');
    expect(note.text).not.toContain('archived');
  });

  it('reflection_complete with no ops omits the parenthetical', () => {
    useStore.getState().apply(SID, env('reflection_complete', { episode: 'nothing to do' }));
    const note = useStore.getState().views[SID]!.transcript.at(-1) as { text: string };
    expect(note.text).toBe('reflection applied: nothing to do');
  });

  it('reflection_discarded adds a plain note', () => {
    useStore.getState().apply(SID, env('reflection_discarded'));
    expect(useStore.getState().views[SID]!.transcript.at(-1)).toMatchObject({
      kind: 'note', level: 'info', text: 'reflection discarded',
    });
  });
});

describe('budget events', () => {
  it('budget_updated sets costBudgetUsd, null clears it', () => {
    useStore.getState().apply(SID, env('budget_updated', { costBudgetUsd: '5.50' }));
    expect(useStore.getState().views[SID]?.costBudgetUsd).toBe(5.5);

    useStore.getState().apply(SID, env('budget_updated', { costBudgetUsd: null }));
    expect(useStore.getState().views[SID]?.costBudgetUsd).toBeNull();
  });

  it('budget_exhausted appends a warning note with both amounts', () => {
    useStore.getState().apply(SID, env('budget_exhausted', { costToDate: '10.00', costBudgetUsd: '10.00' }));
    const note = useStore.getState().views[SID]!.transcript.at(-1) as { level: string; text: string };
    expect(note.level).toBe('warn');
    expect(note.text).toContain('10.00');
  });
});

describe('transcript reduction', () => {
  it('coalesces consecutive text stream_delta events into one item until done', () => {
    useStore.getState().apply(SID, env('stream_delta', { deltaType: 'text', text: 'Hel' }));
    useStore.getState().apply(SID, env('stream_delta', { deltaType: 'text', text: 'lo' }));
    const t = useStore.getState().views[SID]!.transcript;
    expect(t).toHaveLength(1);
    expect(t[0]).toMatchObject({ kind: 'text', text: 'Hello', done: false });
  });

  it('turn_complete closes an open text block, appends a footer, and accumulates cost', () => {
    useStore.getState().apply(SID, env('stream_delta', { deltaType: 'text', text: 'hi' }));
    useStore.getState().apply(SID, env('turn_complete', { stopReason: 'end_turn', costUsd: 0.02, durationMs: 100 }));
    useStore.getState().apply(SID, env('turn_complete', { stopReason: 'end_turn', costUsd: 0.03, durationMs: 50 }));

    const view = useStore.getState().views[SID]!;
    expect(view.transcript[0]).toMatchObject({ kind: 'text', done: true });
    expect(view.transcript.filter((i) => i.kind === 'turn_footer')).toHaveLength(2);
    expect(view.costToDate).toBeCloseTo(0.05);
  });

  it('tool_result attaches output to the matching tool_started by toolUseId', () => {
    useStore.getState().apply(SID, env('tool_started', { toolUseId: 'tu1', name: 'Bash', input: { cmd: 'ls' } }));
    useStore.getState().apply(SID, env('tool_result', { toolUseId: 'tu1', output: 'file.txt', isError: false }));

    const tool = useStore.getState().views[SID]!.transcript[0];
    expect(tool).toMatchObject({ kind: 'tool', toolUseId: 'tu1', output: 'file.txt', isError: false });
  });

  it('permission_response resolves the matching pending permission and clears it', () => {
    useStore.getState().apply(SID, env('permission_request', { requestId: 'r1', toolName: 'Bash', input: {} }));
    useStore.getState().apply(SID, env('permission_response', { requestId: 'r1', behavior: 'allow' }));

    const view = useStore.getState().views[SID]!;
    expect(view.pendingPermission).toBeNull();
    expect(view.transcript[0]).toMatchObject({ kind: 'permission', decision: 'allow' });
  });
});

describe('store actions outside reduce', () => {
  it('setWsStatus updates only wsStatus, preserving the rest of the view', () => {
    useStore.getState().apply(SID, env('state_changed', { state: 'RUNNING' }));
    useStore.getState().setWsStatus(SID, 'open');
    const view = useStore.getState().views[SID]!;
    expect(view.wsStatus).toBe('open');
    expect(view.state).toBe('RUNNING');
  });

  it('remove deletes the view entirely', () => {
    useStore.getState().apply(SID, env('state_changed', { state: 'RUNNING' }));
    useStore.getState().remove(SID);
    expect(useStore.getState().views[SID]).toBeUndefined();
  });

  it('seed applies a transform function, defaulting to an empty view', () => {
    useStore.getState().seed(SID, (v) => ({ ...v, name: 'seeded' }));
    expect(useStore.getState().views[SID]?.name).toBe('seeded');
  });
});
