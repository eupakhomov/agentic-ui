import { isAbsolute, resolve } from 'node:path';
import type { PermissionResponseCommand } from './protocol.js';
import { writeEvent, log } from './stdio.js';

/** SDK-shaped permission results (structurally compatible with PermissionResult). */
export type BridgeResult =
  | { behavior: 'allow'; updatedInput: Record<string, unknown> }
  | { behavior: 'deny'; message: string };

const FILE_MODIFYING_TOOLS = new Set(['Edit', 'Write', 'NotebookEdit']);

/** Paths a file-modifying tool may target, keyed off the tool's input shape. */
function targetPath(toolName: string, input: Record<string, unknown>): string | undefined {
  const p = input['file_path'] ?? input['notebook_path'];
  return typeof p === 'string' ? p : undefined;
}

/**
 * Context dirs are read-only by policy: file-modifying tools targeting paths outside
 * `writableRoot` are denied without a round-trip to the UI. Bash is deliberately not
 * policed here — it goes through the normal approval flow where the user sees the
 * command. `writableRoot` defaults to `cwd` — byte-identical to before Phase 11
 * (docs/plan/phase-11-monorepo.md) for a polyrepo session, where they're the same
 * directory; a monorepo session passes the worktree root while `cwd` is the service
 * subfolder inside it, so a relative path is still resolved against `cwd` but the
 * write-boundary check is against the wider `writableRoot`.
 */
export function readOnlyDenial(
  toolName: string,
  input: Record<string, unknown>,
  cwd: string,
  writableRoot: string = cwd,
): string | undefined {
  if (!FILE_MODIFYING_TOOLS.has(toolName)) return undefined;
  const raw = targetPath(toolName, input);
  if (raw === undefined) return undefined;
  const abs = isAbsolute(raw) ? resolve(raw) : resolve(cwd, raw);
  const root = resolve(writableRoot);
  if (abs === root || abs.startsWith(root + '/')) return undefined;
  return `${toolName} on ${raw} was auto-denied: this session may only modify files under its own worktree (${root}). Context directories are read-only.`;
}

/**
 * Bridges the SDK's canUseTool callback to permission_request / permission_response
 * over the protocol. One pending promise per outstanding request.
 */
export class PermissionBridge {
  private pending = new Map<
    string,
    { resolver: (result: BridgeResult) => void; input: Record<string, unknown> }
  >();
  private counter = 0;

  request(
    toolName: string,
    input: Record<string, unknown>,
    suggestions: unknown[],
  ): Promise<BridgeResult> {
    const requestId = `p${++this.counter}`;
    return new Promise<BridgeResult>((resolver) => {
      this.pending.set(requestId, { resolver, input });
      writeEvent({ type: 'permission_request', requestId, toolName, input, suggestions });
    });
  }

  resolve(cmd: PermissionResponseCommand): boolean {
    const entry = this.pending.get(cmd.requestId);
    if (!entry) {
      log(`permission_response for unknown/settled request ${cmd.requestId}`);
      return false;
    }
    this.pending.delete(cmd.requestId);
    if (cmd.behavior === 'allow') {
      entry.resolver({ behavior: 'allow', updatedInput: cmd.updatedInput ?? entry.input });
    } else {
      entry.resolver({ behavior: 'deny', message: cmd.message ?? 'Denied by user' });
    }
    return true;
  }

  /** Reject all outstanding requests (on interrupt/shutdown) so the SDK never hangs. */
  denyAll(reason: string): void {
    for (const [, entry] of this.pending) entry.resolver({ behavior: 'deny', message: reason });
    this.pending.clear();
  }

  get pendingCount(): number {
    return this.pending.size;
  }
}
