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

describe('nested subagent transcript', () => {
  it('routes a tool_started with parentToolUseId under the owning Task call, not top level', () => {
    useStore.getState().apply(SID, env('tool_started', { toolUseId: 'tu_task', name: 'Task', input: { description: 'explore' } }));
    useStore.getState().apply(SID, env('tool_started', { toolUseId: 'tu_read', name: 'Read', input: { file_path: 'a.ts' }, parentToolUseId: 'tu_task' }));

    const t = useStore.getState().views[SID]!.transcript;
    expect(t).toHaveLength(1);
    const task = t[0] as { kind: 'tool'; items?: unknown[] };
    expect(task.items).toHaveLength(1);
    expect(task.items![0]).toMatchObject({ kind: 'tool', toolUseId: 'tu_read', name: 'Read' });
  });

  it('resolves a nested tool_result against the nested tool_started, not a same-id top-level one', () => {
    useStore.getState().apply(SID, env('tool_started', { toolUseId: 'tu_task', name: 'Task', input: {} }));
    useStore.getState().apply(SID, env('tool_started', { toolUseId: 'tu_shared', name: 'Bash', input: { command: 'ls' }, parentToolUseId: 'tu_task' }));
    useStore.getState().apply(SID, env('tool_result', { toolUseId: 'tu_shared', output: 'ok', isError: false, parentToolUseId: 'tu_task' }));

    const task = useStore.getState().views[SID]!.transcript[0] as { items?: { toolUseId: string; output?: string }[] };
    expect(task.items).toHaveLength(1);
    expect(task.items![0]).toMatchObject({ toolUseId: 'tu_shared', output: 'ok' });
  });

  it('coalesces nested thinking deltas and applies thinking_progress to the nested item', () => {
    useStore.getState().apply(SID, env('tool_started', { toolUseId: 'tu_task', name: 'Task', input: {} }));
    useStore.getState().apply(SID, env('stream_delta', { deltaType: 'thinking', text: 'pon', parentToolUseId: 'tu_task' }));
    useStore.getState().apply(SID, env('stream_delta', { deltaType: 'thinking', text: 'dering', parentToolUseId: 'tu_task' }));
    useStore.getState().apply(SID, env('thinking_progress', { estimatedTokens: 42, estimatedTokensDelta: 42 }));

    const task = useStore.getState().views[SID]!.transcript[0] as { items?: { kind: string; text: string; estimatedTokens: number; done: boolean }[] };
    expect(task.items).toHaveLength(1);
    expect(task.items![0]).toMatchObject({ kind: 'thinking', text: 'pondering', estimatedTokens: 42, done: false });

    // top-level transcript is unaffected by the nested progress update
    expect(useStore.getState().views[SID]!.transcript).toHaveLength(1);
  });

  it('falls back to flat top-level when parentToolUseId matches no known tool call', () => {
    useStore.getState().apply(SID, env('tool_started', { toolUseId: 'tu_orphan', name: 'Read', input: {}, parentToolUseId: 'tu_missing' }));

    const t = useStore.getState().views[SID]!.transcript;
    expect(t).toHaveLength(1);
    expect(t[0]).toMatchObject({ kind: 'tool', toolUseId: 'tu_orphan' });
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

describe('context_usage', () => {
  it('records tokens/window/autoCompactAt', () => {
    useStore.getState().apply(SID, env('context_usage', { tokens: 38000, window: 200000, autoCompactAt: 178000 }));
    const v = useStore.getState().views[SID]!;
    expect(v.contextTokens).toBe(38000);
    expect(v.contextWindow).toBe(200000);
    expect(v.autoCompactAt).toBe(178000);
  });

  it('leaves autoCompactAt null when the provider does not report one', () => {
    useStore.getState().apply(SID, env('context_usage', { tokens: 100, window: 1000 }));
    expect(useStore.getState().views[SID]!.autoCompactAt).toBeNull();
  });
});

describe('context_compacted', () => {
  it('pushes a transcript divider and re-arms the warn flag', () => {
    useStore.getState().apply(SID, env('context_usage', { tokens: 190000, window: 200000 }));
    useStore.getState().evaluateContextWarn(SID, 70);
    expect(useStore.getState().views[SID]!.ctxSuggestionVisible).toBe(true);

    useStore.getState().apply(SID, env('context_compacted', { preTokens: 190000, postTokens: 20000, trigger: 'manual' }));

    const v = useStore.getState().views[SID]!;
    expect(v.transcript.at(-1)).toEqual({ kind: 'context_compacted', preTokens: 190000, postTokens: 20000, trigger: 'manual' });
    expect(v.ctxWarnArmed).toBe(true);
    expect(v.ctxSuggestionVisible).toBe(false);
  });
});

describe('evaluateContextWarn / dismissContextWarn', () => {
  it('does nothing below the threshold', () => {
    useStore.getState().apply(SID, env('context_usage', { tokens: 50000, window: 200000 })); // 25%
    useStore.getState().evaluateContextWarn(SID, 70);
    expect(useStore.getState().views[SID]!.ctxSuggestionVisible).toBe(false);
  });

  it('fires once on crossing and does not re-fire on a later evaluation while still above threshold', () => {
    useStore.getState().apply(SID, env('context_usage', { tokens: 150000, window: 200000 })); // 75%
    useStore.getState().evaluateContextWarn(SID, 70);
    expect(useStore.getState().views[SID]!.ctxSuggestionVisible).toBe(true);
    expect(useStore.getState().views[SID]!.ctxWarnArmed).toBe(false);

    useStore.getState().dismissContextWarn(SID);
    expect(useStore.getState().views[SID]!.ctxSuggestionVisible).toBe(false);

    // still above threshold, no compaction happened — must not re-show
    useStore.getState().apply(SID, env('context_usage', { tokens: 160000, window: 200000 })); // 80%
    useStore.getState().evaluateContextWarn(SID, 70);
    expect(useStore.getState().views[SID]!.ctxSuggestionVisible).toBe(false);
  });

  it('a lower reading after a crossing does not re-trigger, even if it rises again, without a compaction', () => {
    useStore.getState().apply(SID, env('context_usage', { tokens: 150000, window: 200000 })); // 75%
    useStore.getState().evaluateContextWarn(SID, 70);
    useStore.getState().dismissContextWarn(SID);

    useStore.getState().apply(SID, env('context_usage', { tokens: 100000, window: 200000 })); // 50%, dips below
    useStore.getState().evaluateContextWarn(SID, 70);
    expect(useStore.getState().views[SID]!.ctxSuggestionVisible).toBe(false);

    useStore.getState().apply(SID, env('context_usage', { tokens: 150000, window: 200000 })); // back to 75%
    useStore.getState().evaluateContextWarn(SID, 70);
    expect(useStore.getState().views[SID]!.ctxSuggestionVisible).toBe(false); // still armed=false — no compaction happened
  });

  it('re-arms after a compaction, so the next crossing fires again', () => {
    useStore.getState().apply(SID, env('context_usage', { tokens: 150000, window: 200000 }));
    useStore.getState().evaluateContextWarn(SID, 70);
    useStore.getState().apply(SID, env('context_compacted', { preTokens: 150000, postTokens: 20000, trigger: 'manual' }));

    useStore.getState().apply(SID, env('context_usage', { tokens: 150000, window: 200000 }));
    useStore.getState().evaluateContextWarn(SID, 70);
    expect(useStore.getState().views[SID]!.ctxSuggestionVisible).toBe(true);
  });
});
