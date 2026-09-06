import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { PassThrough } from 'node:stream';

/**
 * CodexRpc spawns `codex app-server` and speaks newline-delimited JSON-RPC over its
 * stdio. We replace node:child_process's spawn with a fake child whose stdin/stdout/
 * stderr are plain PassThrough streams, so we can write "server" lines onto stdout
 * and inspect exactly what CodexRpc writes onto stdin - real framing/correlation
 * logic under test, no real subprocess involved.
 */

let spawnCalls: { cmd: string; args: string[]; opts: Record<string, unknown> }[] = [];
let fakeChild: {
  stdout: PassThrough;
  stderr: PassThrough;
  stdin: PassThrough;
};

vi.mock('node:child_process', () => ({
  spawn: vi.fn((cmd: string, args: string[], opts: Record<string, unknown>) => {
    spawnCalls.push({ cmd, args, opts });
    return fakeChild;
  }),
}));

import { CodexRpc } from '../src/rpc.js';

function readWrittenLines(stdin: PassThrough): string[] {
  const raw = (stdin as unknown as { _written?: string })._written ?? '';
  return raw.split('\n').filter((l) => l.trim() !== '');
}

function makeFakeChild(): typeof fakeChild {
  const stdin = new PassThrough();
  (stdin as unknown as { _written: string })._written = '';
  stdin.on('data', (chunk: Buffer) => {
    (stdin as unknown as { _written: string })._written += chunk.toString('utf8');
  });
  return {
    stdout: new PassThrough(),
    stderr: new PassThrough(),
    stdin,
  };
}

describe('CodexRpc: JSON-RPC-over-stdio framing', () => {
  beforeEach(() => {
    spawnCalls = [];
    fakeChild = makeFakeChild();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('frames an outbound call as {method,id,params} and resolves on a matching response', async () => {
    const rpc = new CodexRpc('codex', ['app-server']);
    const promise = rpc.call('thread/start', { cwd: '/work' });

    const lines = readWrittenLines(fakeChild.stdin);
    expect(lines).toHaveLength(1);
    const sent = JSON.parse(lines[0]!);
    expect(sent).toEqual({ method: 'thread/start', id: 1, params: { cwd: '/work' } });

    fakeChild.stdout.write(JSON.stringify({ id: 1, result: { threadId: 't1' } }) + '\n');
    await expect(promise).resolves.toEqual({ threadId: 't1' });
  });

  it('rejects the matching pending call when the response carries an error', async () => {
    const rpc = new CodexRpc('codex', ['app-server']);
    const promise = rpc.call('thread/start', {});
    fakeChild.stdout.write(JSON.stringify({ id: 1, error: { code: -1, message: 'boom' } }) + '\n');
    await expect(promise).rejects.toThrow(/boom/);
  });

  it('assigns independently incrementing ids across multiple calls, resolved out of order', async () => {
    const rpc = new CodexRpc('codex', ['app-server']);
    const p1 = rpc.call('a', {});
    const p2 = rpc.call('b', {});
    const lines = readWrittenLines(fakeChild.stdin).map((l) => JSON.parse(l));
    expect(lines.map((l) => l.id)).toEqual([1, 2]);

    // respond to the second call first
    fakeChild.stdout.write(JSON.stringify({ id: 2, result: 'second' }) + '\n');
    fakeChild.stdout.write(JSON.stringify({ id: 1, result: 'first' }) + '\n');

    await expect(p2).resolves.toBe('second');
    await expect(p1).resolves.toBe('first');
  });

  it('routes a server-initiated request (id + method) to the server-request handler and does not treat it as a call response', async () => {
    const rpc = new CodexRpc('codex', ['app-server']);
    const handler = vi.fn();
    rpc.onServerRequest(handler);

    fakeChild.stdout.write(JSON.stringify({ id: 7, method: 'approval/requestCommand', params: { cmd: 'rm -rf /' } }) + '\n');

    expect(handler).toHaveBeenCalledWith('approval/requestCommand', 7, { cmd: 'rm -rf /' });
  });

  it('routes a notification (method, no id) to the notification handler', async () => {
    const rpc = new CodexRpc('codex', ['app-server']);
    const handler = vi.fn();
    rpc.onNotification(handler);

    fakeChild.stdout.write(JSON.stringify({ method: 'thread/tokenCount', params: { tokens: 42 } }) + '\n');

    expect(handler).toHaveBeenCalledWith('thread/tokenCount', { tokens: 42 });
  });

  it('respond() writes {id,result} with no method field', () => {
    const rpc = new CodexRpc('codex', ['app-server']);
    rpc.respond(9, { decision: 'accept' });
    const lines = readWrittenLines(fakeChild.stdin).map((l) => JSON.parse(l));
    expect(lines).toEqual([{ id: 9, result: { decision: 'accept' } }]);
  });

  it('ignores malformed JSON lines and unrecognized shapes without throwing', () => {
    const requestHandler = vi.fn();
    const notifHandler = vi.fn();
    const rpc = new CodexRpc('codex', ['app-server']);
    rpc.onServerRequest(requestHandler);
    rpc.onNotification(notifHandler);

    expect(() => {
      fakeChild.stdout.write('not json at all\n');
      fakeChild.stdout.write(JSON.stringify({ foo: 'bar' }) + '\n'); // no id, no method
    }).not.toThrow();

    expect(requestHandler).not.toHaveBeenCalled();
    expect(notifHandler).not.toHaveBeenCalled();
  });

  it('logs and ignores a response for an unknown or already-settled id', async () => {
    const rpc = new CodexRpc('codex', ['app-server']);
    // id 1 was never requested
    expect(() => {
      fakeChild.stdout.write(JSON.stringify({ id: 1, result: 'stray' }) + '\n');
    }).not.toThrow();
    void rpc; // constructed only to exercise onLine via its stdout wiring
  });

  it('rejectAllPending rejects every outstanding call with the given reason and clears them', async () => {
    const rpc = new CodexRpc('codex', ['app-server']);
    const p1 = rpc.call('a', {});
    const p2 = rpc.call('b', {});

    rpc.rejectAllPending('shutting down');

    await expect(p1).rejects.toThrow('shutting down');
    await expect(p2).rejects.toThrow('shutting down');

    // a late response to an already-cleared id is now just an unknown/settled response
    expect(() => {
      fakeChild.stdout.write(JSON.stringify({ id: 1, result: 'late' }) + '\n');
    }).not.toThrow();
  });

  it('merges extraEnv onto the spawned child environment for MCP bearer tokens', () => {
    new CodexRpc('codex', ['app-server'], { CODEX_MCP_TOKEN_LINEAR: 'secret-token' });
    expect(spawnCalls).toHaveLength(1);
    const env = spawnCalls[0]!.opts['env'] as Record<string, string>;
    expect(env['CODEX_MCP_TOKEN_LINEAR']).toBe('secret-token');
  });

  it('spawns with the process environment (no extraEnv given)', () => {
    new CodexRpc('codex', ['app-server']);
    const env = spawnCalls[0]!.opts['env'] as Record<string, string>;
    expect(env).toBe(process.env);
  });
});
