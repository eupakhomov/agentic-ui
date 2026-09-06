import { describe, expect, it } from 'vitest';
import { translateSdkMessage } from '../src/session.js';

function state(currentModel = '') {
  return { currentModel };
}

describe('translateSdkMessage', () => {
  it('translates system/init into system_init and records the model', () => {
    const s = state();
    const { events, logs } = translateSdkMessage(
      {
        type: 'system',
        subtype: 'init',
        session_id: 'abc123',
        model: 'sonnet',
        cwd: '/work/s1',
        tools: ['Edit', 'Bash'],
        mcp_servers: [{ name: 'linear', status: 'connected' }],
        permissionMode: 'default',
      },
      s,
    );
    expect(logs).toEqual([]);
    expect(events).toEqual([
      {
        type: 'system_init',
        providerSessionId: 'abc123',
        model: 'sonnet',
        cwd: '/work/s1',
        tools: ['Edit', 'Bash'],
        mcpServers: [{ name: 'linear', status: 'connected' }],
        permissionMode: 'default',
      },
    ]);
    expect(s.currentModel).toBe('sonnet');
  });

  it('translates system/thinking_tokens into thinking_progress', () => {
    const { events } = translateSdkMessage(
      { type: 'system', subtype: 'thinking_tokens', estimated_tokens: 120, estimated_tokens_delta: 20 },
      state(),
    );
    expect(events).toEqual([{ type: 'thinking_progress', estimatedTokens: 120, estimatedTokensDelta: 20 }]);
  });

  it('ignores unrecognized system subtypes', () => {
    const { events, logs } = translateSdkMessage({ type: 'system', subtype: 'something_else' }, state());
    expect(events).toEqual([]);
    expect(logs).toEqual([]);
  });

  it('translates a text content_block_delta into a stream_delta', () => {
    const { events } = translateSdkMessage(
      { type: 'stream_event', event: { type: 'content_block_delta', delta: { type: 'text_delta', text: 'hi' } } },
      state(),
    );
    expect(events).toEqual([{ type: 'stream_delta', deltaType: 'text', text: 'hi' }]);
  });

  it('translates a thinking content_block_delta into a stream_delta', () => {
    const { events } = translateSdkMessage(
      {
        type: 'stream_event',
        event: { type: 'content_block_delta', delta: { type: 'thinking_delta', thinking: 'pondering' } },
      },
      state(),
    );
    expect(events).toEqual([{ type: 'stream_delta', deltaType: 'thinking', text: 'pondering' }]);
  });

  it('logs unmapped stream deltas instead of emitting an event', () => {
    const { events, logs } = translateSdkMessage(
      { type: 'stream_event', event: { type: 'content_block_delta', delta: { type: 'input_json_delta' } } },
      state(),
    );
    expect(events).toEqual([]);
    expect(logs).toHaveLength(1);
    expect(logs[0]).toContain('unmapped stream delta');
  });

  it('translates an assistant message into assistant_message plus tool_started per tool_use block', () => {
    const { events } = translateSdkMessage(
      {
        type: 'assistant',
        message: {
          content: [
            { type: 'text', text: 'ok' },
            { type: 'tool_use', id: 'tu1', name: 'Bash', input: { command: 'ls' } },
          ],
        },
      },
      state(),
    );
    expect(events).toEqual([
      {
        type: 'assistant_message',
        content: [
          { type: 'text', text: 'ok' },
          { type: 'tool_use', id: 'tu1', name: 'Bash', input: { command: 'ls' } },
        ],
      },
      { type: 'tool_started', toolUseId: 'tu1', name: 'Bash', input: { command: 'ls' } },
    ]);
  });

  it('translates a user message tool_result into tool_result, truncating long output', () => {
    const long = 'x'.repeat(17 * 1024);
    const { events } = translateSdkMessage(
      {
        type: 'user',
        message: { content: [{ type: 'tool_result', tool_use_id: 'tu1', content: long, is_error: false }] },
      },
      state(),
    );
    expect(events).toHaveLength(1);
    const ev = events[0] as { type: string; truncated: boolean; output: string; isError: boolean };
    expect(ev.type).toBe('tool_result');
    expect(ev.isError).toBe(false);
    expect(ev.truncated).toBe(true);
    expect(ev.output.length).toBe(16384);
  });

  it('ignores a user message with non-array content', () => {
    const { events } = translateSdkMessage({ type: 'user', message: { content: 'plain text' } }, state());
    expect(events).toEqual([]);
  });

  it('translates a result message into turn_complete, stamping the tracked model', () => {
    const { events } = translateSdkMessage(
      {
        type: 'result',
        subtype: 'success',
        usage: { input_tokens: 1 },
        total_cost_usd: 0.02,
        duration_ms: 500,
        num_turns: 3,
      },
      state('opus'),
    );
    expect(events).toEqual([
      {
        type: 'turn_complete',
        stopReason: 'success',
        usage: { input_tokens: 1 },
        costUsd: 0.02,
        durationMs: 500,
        numTurns: 3,
        model: 'opus',
      },
    ]);
  });

  it('surfaces a non-nominal rate_limit_event as a non-fatal error', () => {
    const { events } = translateSdkMessage(
      { type: 'rate_limit_event', rate_limit: { status: 'rejected' } },
      state(),
    );
    expect(events).toHaveLength(1);
    expect(events[0]).toMatchObject({ type: 'error', fatal: false });
  });

  it('stays silent on a nominal rate_limit_event', () => {
    const { events } = translateSdkMessage({ type: 'rate_limit_event', rate_limit: { status: 'allowed' } }, state());
    expect(events).toEqual([]);
  });

  it('logs unhandled message types', () => {
    const { events, logs } = translateSdkMessage({ type: 'some_future_type' }, state());
    expect(events).toEqual([]);
    expect(logs).toEqual(['unhandled SDK message type: some_future_type']);
  });
});
