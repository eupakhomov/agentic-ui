import { describe, expect, it } from 'vitest';
import { toContextUsageEvent } from '../src/session.js';

type Usage = Parameters<typeof toContextUsageEvent>[0];

function usage(over: Partial<Usage> = {}): Usage {
  return {
    totalTokens: 1000,
    rawMaxTokens: 200000,
    categories: [],
    mcpTools: [],
    ...over,
  };
}

const cat = (name: string, tokens: number) => ({ name, tokens, color: 'x' });
const tool = (name: string, serverName: string, tokens: number, isLoaded?: boolean) => ({
  name,
  serverName,
  tokens,
  ...(isLoaded === undefined ? {} : { isLoaded }),
});

describe('toContextUsageEvent', () => {
  it('keeps the totals and omits autoCompactAt when the SDK has none', () => {
    const e = toContextUsageEvent(usage());
    expect(e.type).toBe('context_usage');
    expect(e.tokens).toBe(1000);
    expect(e.window).toBe(200000);
    expect('autoCompactAt' in e).toBe(false);
  });

  it('passes autoCompactAt through', () => {
    expect(toContextUsageEvent(usage({ autoCompactThreshold: 167000 })).autoCompactAt).toBe(167000);
  });

  it('drops zero-token categories and sorts the rest largest first', () => {
    const e = toContextUsageEvent(
      usage({ categories: [cat('Messages', 50), cat('Free space', 0), cat('System prompt', 300)] }),
    );
    expect(e.breakdown?.categories).toEqual([
      { name: 'System prompt', tokens: 300 },
      { name: 'Messages', tokens: 50 },
    ]);
  });

  it('rolls MCP tools up per server and flags a server as deferred only when none of its tools are loaded', () => {
    const e = toContextUsageEvent(
      usage({
        mcpTools: [
          tool('a', 'linear', 100, false),
          tool('b', 'linear', 200, false),
          tool('c', 'memory', 40, true),
          tool('d', 'memory', 10, false),
          tool('e', 'plain', 5),
          tool('f', 'empty', 0, true),
        ],
      }),
    );
    expect(e.breakdown?.mcpServers).toEqual([
      { name: 'linear', tokens: 300, deferred: true },
      { name: 'memory', tokens: 50, deferred: false },
      { name: 'plain', tokens: 5, deferred: false },
    ]);
  });

  it('maps the message breakdown and keeps the 5 heaviest tools by call+result', () => {
    const e = toContextUsageEvent(
      usage({
        messageBreakdown: {
          toolCallTokens: 10,
          toolResultTokens: 90,
          attachmentTokens: 1,
          assistantMessageTokens: 20,
          userMessageTokens: 5,
          redirectedContextTokens: 0,
          unattributedTokens: 0,
          toolCallsByType: [
            { name: 'Bash', callTokens: 5, resultTokens: 50 },
            { name: 'Read', callTokens: 5, resultTokens: 40 },
            { name: 'Grep', callTokens: 1, resultTokens: 1 },
            { name: 'Glob', callTokens: 2, resultTokens: 2 },
            { name: 'Edit', callTokens: 3, resultTokens: 3 },
            { name: 'Write', callTokens: 4, resultTokens: 4 },
            { name: 'Unused', callTokens: 0, resultTokens: 0 },
          ],
          attachmentsByType: [],
        },
      }),
    );
    expect(e.breakdown?.messages).toEqual({
      toolResults: 90,
      toolCalls: 10,
      assistant: 20,
      user: 5,
      attachments: 1,
    });
    expect(e.breakdown?.topTools.map((t) => t.name)).toEqual(['Bash', 'Read', 'Write', 'Edit', 'Glob']);
    expect(e.breakdown?.topTools[0]).toEqual({ name: 'Bash', tokens: 55 });
  });

  it('omits messages and has no top tools when the SDK reports no message breakdown', () => {
    const e = toContextUsageEvent(usage());
    expect(e.breakdown?.messages).toBeUndefined();
    expect(e.breakdown?.topTools).toEqual([]);
  });
});
