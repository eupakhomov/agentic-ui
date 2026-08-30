/**
 * Minimal bidirectional JSON-RPC 2.0 client over the `codex app-server` child
 * process's stdio. Confirmed live (docs/plan/phase-5.13-codex-provider.md Task 1):
 * plain newline-delimited JSON, no Content-Length framing.
 *
 * Server-request ids and our own outbound call ids are independent, possibly
 * overlapping counters — never ambiguous in practice because a response to one of
 * our calls never carries a `method` field, while a server-initiated request always
 * does. That's the correlation rule this class relies on, not numeric ranges.
 */
import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import { readLines, log } from './stdio.js';

export type ServerRequestHandler = (method: string, id: number, params: unknown) => void;
export type NotificationHandler = (method: string, params: unknown) => void;

export class CodexRpc {
  readonly child: ChildProcessWithoutNullStreams;
  private nextId = 1;
  private pending = new Map<number, { resolve: (r: unknown) => void; reject: (e: Error) => void }>();
  private serverRequestHandler: ServerRequestHandler = () => {};
  private notificationHandler: NotificationHandler = () => {};

  constructor(codexBin: string, args: string[]) {
    this.child = spawn(codexBin, args, { stdio: ['pipe', 'pipe', 'pipe'] });
    readLines(this.child.stdout, (line) => this.onLine(line), () => log('codex app-server stdout closed'));
    this.child.stderr.setEncoding('utf8');
    this.child.stderr.on('data', (d: string) => log('codex:', d.trimEnd()));
  }

  onServerRequest(handler: ServerRequestHandler): void {
    this.serverRequestHandler = handler;
  }

  onNotification(handler: NotificationHandler): void {
    this.notificationHandler = handler;
  }

  private onLine(line: string): void {
    let msg: Record<string, unknown>;
    try {
      msg = JSON.parse(line) as Record<string, unknown>;
    } catch {
      log('failed to parse app-server line as JSON:', line.slice(0, 300));
      return;
    }
    const id = msg['id'];
    const method = msg['method'];
    // Response to one of our own outbound calls: has an id, no method.
    if (typeof id === 'number' && typeof method !== 'string') {
      const entry = this.pending.get(id);
      if (!entry) {
        log('response for unknown/already-settled request id', id);
        return;
      }
      this.pending.delete(id);
      if ('error' in msg && msg['error'] != null) {
        entry.reject(new Error(JSON.stringify(msg['error'])));
      } else {
        entry.resolve(msg['result']);
      }
      return;
    }
    // Server-initiated request: has both an id and a method, expects a reply.
    if (typeof id === 'number' && typeof method === 'string') {
      this.serverRequestHandler(method, id, msg['params']);
      return;
    }
    // Notification: method, no id.
    if (typeof method === 'string') {
      this.notificationHandler(method, msg['params']);
      return;
    }
    log('unrecognized app-server line shape:', line.slice(0, 300));
  }

  call<T = unknown>(method: string, params?: unknown): Promise<T> {
    const id = this.nextId++;
    return new Promise<T>((resolve, reject) => {
      this.pending.set(id, { resolve: resolve as (r: unknown) => void, reject });
      this.child.stdin.write(JSON.stringify({ method, id, params }) + '\n');
    });
  }

  respond(id: number, result: unknown): void {
    this.child.stdin.write(JSON.stringify({ id, result }) + '\n');
  }

  /** Reject every outstanding outbound call (on interrupt/shutdown) so nothing hangs. */
  rejectAllPending(reason: string): void {
    for (const [, entry] of this.pending) entry.reject(new Error(reason));
    this.pending.clear();
  }
}
