import {
  CODEX_CAPABILITIES,
  PROTOCOL_VERSION,
  type Command,
  type EffortLevel,
  type PermissionResponseCommand,
} from './protocol.js';
import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { CodexRpc } from './rpc.js';
import { allowDecision, denyDecision } from './approvals.js';
import { translateMcpConfig } from './mcp.js';
import { log, readLines, writeEvent } from './stdio.js';

/** Codex only offers a real analog for two of Claude's four permission modes — see
 * docs/plan/phase-5.13-codex-provider.md "Permission-mode mapping". */
export type CodexPermissionMode = 'default' | 'bypassPermissions';

export interface SidecarConfig {
  cwd: string;
  /** Codex's sandboxPolicy writableRoots (docs/plan/phase-11-monorepo.md) — the worktree root; equals cwd for a polyrepo session, wider than it for a monorepo one. */
  writableRoot: string;
  resume?: string;
  model?: string;
  permissionMode?: CodexPermissionMode;
  effort?: EffortLevel;
  /** maps to Codex's thread-level developerInstructions (ThreadStartParams/ThreadResumeParams) */
  appendSystemPrompt?: string;
  /** same Claude-shaped file the backend already writes; translated per mcp.ts */
  mcpConfigPath?: string;
  /** override for testing; defaults to the `codex` binary on PATH */
  codexBin?: string;
}

const TOOL_OUTPUT_LIMIT = Number(process.env['CLAUDE_UI_TOOL_OUTPUT_LIMIT'] ?? 16 * 1024);

function truncate(s: string): string {
  return s.length > TOOL_OUTPUT_LIMIT ? s.slice(0, TOOL_OUTPUT_LIMIT) : s;
}

/** Either a text turn or a compact request, queued and drained one at a time so a
 * compact can never race a turn/start on the same thread. */
type QueuedItem = { kind: 'turn'; text: string } | { kind: 'compact' };

/** Unbounded async FIFO — same shape as sidecar/src/session.ts's queue, driving one
 * turn at a time so Codex sessions queue messages exactly like Claude sessions do. */
class AsyncQueue<T> {
  private items: T[] = [];
  private waiters: ((item: T | undefined) => void)[] = [];
  private closed = false;

  push(item: T): void {
    if (this.closed) return;
    const waiter = this.waiters.shift();
    if (waiter) waiter(item);
    else this.items.push(item);
  }

  close(): void {
    this.closed = true;
    for (const waiter of this.waiters.splice(0)) waiter(undefined);
  }

  async next(): Promise<T | undefined> {
    const item = this.items.shift();
    if (item !== undefined) return item;
    if (this.closed) return undefined;
    return new Promise((res) => this.waiters.push(res));
  }
}

function approvalPolicyFor(mode: CodexPermissionMode): string {
  return mode === 'bypassPermissions' ? 'never' : 'on-request';
}

/** Full SandboxPolicy object shape, needed by turn/start (thread/start takes the
 * simpler SandboxMode string — see sandboxModeFor below). `writableRoots: [writableRoot]`
 * (docs/plan/phase-11-monorepo.md) is the worktree root — equal to `cwd` for a polyrepo
 * session, wider than it for a monorepo one, so a cross-package edit is allowed without
 * a prompt in the same way `sidecar/`'s `readOnlyDenial` allows it. */
export function sandboxPolicyFor(mode: CodexPermissionMode, writableRoot: string): Record<string, unknown> {
  return mode === 'bypassPermissions'
    ? { type: 'dangerFullAccess' }
    : { type: 'workspaceWrite', writableRoots: [writableRoot], networkAccess: false, excludeTmpdirEnvVar: false, excludeSlashTmp: false };
}

function sandboxModeFor(mode: CodexPermissionMode): string {
  return mode === 'bypassPermissions' ? 'danger-full-access' : 'workspace-write';
}

interface PendingApproval {
  availableDecisions: unknown;
  /** 'decision' = the item requestApproval methods ({decision}); 'elicitation' = mcpServer/elicitation/request ({action}) */
  kind: 'decision' | 'elicitation';
}

export async function runSession(config: SidecarConfig): Promise<never> {
  // Must happen before spawning: the bearer-token env vars this produces need to be
  // set on the codex app-server child's own environment at spawn time (see rpc.ts).
  let mcpServers: Record<string, unknown> = {};
  let mcpExtraEnv: Record<string, string> = {};
  if (config.mcpConfigPath) {
    try {
      const translated = await translateMcpConfig(config.mcpConfigPath);
      mcpServers = translated.mcpServers;
      mcpExtraEnv = translated.extraEnv;
    } catch (e) {
      log(`failed to load/translate --mcp-config ${config.mcpConfigPath}: ${String(e)}`);
    }
  }

  const rpc = new CodexRpc(config.codexBin ?? 'codex', ['app-server'], mcpExtraEnv);
  const inputQueue = new AsyncQueue<QueuedItem>();
  const itemsCache = new Map<string, Record<string, unknown>>();
  const pendingApprovals = new Map<string, PendingApproval>();

  let exiting: 'shutdown' | 'stdin_closed' | undefined;
  let threadId = '';
  let currentTurnId: string | undefined;
  let currentModel = '';
  let currentModelOverride = config.model;
  let currentPermissionMode: CodexPermissionMode = config.permissionMode ?? 'default';
  let latestUsage: unknown = {};
  let awaitingTurnComplete: ((turn: Record<string, unknown>) => void) | undefined;
  // Context-window snapshot from thread/tokenUsage/updated's `last`/`modelContextWindow`
  // (the current request's context, distinct from `latestUsage`'s cumulative `total`
  // used for cost estimation) — see docs/plan/phase-12-linear-cache-serena-context.md
  // Step C0 outcome.
  let latestContextTokens: number | undefined;
  let latestContextWindow: number | undefined;
  let awaitingCompact: (() => void) | undefined;
  // Belt-and-suspenders guard for 'compact' (decision 9) — counts items pushed onto
  // inputQueue but not yet fully drained, so a compact request arriving while a turn is
  // queued or running is rejected synchronously rather than silently queued behind it.
  let queuedOrRunning = 0;

  writeEvent({
    type: 'ready',
    pid: rpc.child.pid ?? process.pid,
    protocolVersion: PROTOCOL_VERSION,
    provider: 'codex',
    capabilities: CODEX_CAPABILITIES,
  });

  function denyAllPendingApprovals(reason: string): void {
    if (pendingApprovals.size > 0) log(`denying ${pendingApprovals.size} pending approval(s): ${reason}`);
    for (const [requestId, entry] of pendingApprovals) {
      rpc.respond(Number(requestId), entry.kind === 'elicitation'
        ? { action: 'decline', content: null }
        : { decision: denyDecision(entry.availableDecisions) });
    }
    pendingApprovals.clear();
  }

  function emitTurnComplete(turn: Record<string, unknown>): void {
    const status = String(turn['status'] ?? 'completed');
    const error = turn['error'] as { message?: string } | null | undefined;
    writeEvent({
      type: 'turn_complete',
      stopReason: error?.message ? `${status}: ${error.message}` : status,
      usage: latestUsage,
      // Codex reports no per-turn USD cost; the backend computes an estimate from
      // `usage` for provider=codex sessions (docs/plan/phase-5.13-codex-provider.md
      // Decision 2) — this adapter has no reason to know about pricing settings.
      costUsd: 0,
      durationMs: Number(turn['durationMs'] ?? 0),
      // Codex has no multi-step-within-a-turn counter the way Claude's SDK does —
      // one completed turn/start call is always reported as 1.
      numTurns: 1,
      model: currentModel,
    });
    currentTurnId = undefined;
    emitContextUsage();
  }

  function emitContextUsage(): void {
    if (latestContextTokens === undefined || latestContextWindow === undefined) return;
    writeEvent({ type: 'context_usage', tokens: latestContextTokens, window: latestContextWindow });
  }

  /** Sends thread/compact/start and waits for the thread/compacted notification. Token
   * deltas come from our own before/after snapshot of latestContextTokens — the
   * notification itself carries no pre/post counts (deprecated in favor of an item type
   * this adapter doesn't otherwise consume). Provisional: confirmed against the
   * installed codex-cli's protocol schema, not yet against a live compaction run — see
   * docs/plan/phase-12-linear-cache-serena-context.md Step C0 outcome. */
  async function doCompact(): Promise<void> {
    if (!threadId) {
      writeEvent({ type: 'error', message: 'compact: no active thread', fatal: false });
      return;
    }
    const preTokens = latestContextTokens;
    let failed = false;
    await new Promise<void>((resolve) => {
      awaitingCompact = () => {
        awaitingCompact = undefined;
        resolve();
      };
      rpc.call('thread/compact/start', { threadId }).catch((e: unknown) => {
        failed = true;
        awaitingCompact = undefined;
        writeEvent({ type: 'error', message: `compact failed: ${String(e)}`, fatal: false });
        resolve();
      });
    });
    if (failed) return;
    writeEvent({
      type: 'context_compacted',
      preTokens: preTokens ?? 0,
      postTokens: latestContextTokens ?? preTokens ?? 0,
      trigger: 'manual',
    });
    emitContextUsage();
  }

  function runTurn(text: string): Promise<void> {
    return new Promise((resolve) => {
      awaitingTurnComplete = (turn) => {
        awaitingTurnComplete = undefined;
        emitTurnComplete(turn);
        resolve();
      };
      const params: Record<string, unknown> = {
        threadId,
        input: [{ type: 'text', text, text_elements: [] }],
        approvalPolicy: approvalPolicyFor(currentPermissionMode),
        sandboxPolicy: sandboxPolicyFor(currentPermissionMode, config.writableRoot),
      };
      if (currentModelOverride) params['model'] = currentModelOverride;
      if (config.effort) params['effort'] = config.effort;
      rpc
        .call<{ turn: { id: string } }>('turn/start', params)
        .then((res) => {
          currentTurnId = res.turn.id;
        })
        .catch((e: unknown) => {
          awaitingTurnComplete = undefined;
          writeEvent({ type: 'error', message: `turn/start failed: ${String(e)}`, fatal: false });
          resolve();
        });
    });
  }

  function handleNotification(method: string, params: unknown): void {
    const p = (params ?? {}) as Record<string, unknown>;
    switch (method) {
      case 'item/agentMessage/delta':
        writeEvent({ type: 'stream_delta', deltaType: 'text', text: String(p['delta'] ?? '') });
        break;
      case 'item/started': {
        const item = p['item'] as Record<string, unknown>;
        if (!item) break;
        itemsCache.set(String(item['id']), item);
        if (item['type'] === 'commandExecution') {
          writeEvent({
            type: 'tool_started',
            toolUseId: String(item['id']),
            name: 'Bash',
            input: { command: item['command'], cwd: item['cwd'] },
          });
        } else if (item['type'] === 'fileChange') {
          writeEvent({
            type: 'tool_started',
            toolUseId: String(item['id']),
            name: 'Edit',
            input: { changes: item['changes'] },
          });
        } else if (item['type'] === 'mcpToolCall') {
          // mcp__<server>__<tool> matches the Claude Agent SDK's own MCP tool naming
          // convention, so the widget's tool-call rendering needs no provider branch.
          writeEvent({
            type: 'tool_started',
            toolUseId: String(item['id']),
            name: `mcp__${item['server']}__${item['tool']}`,
            input: (item['arguments'] as Record<string, unknown>) ?? {},
          });
        }
        break;
      }
      case 'item/completed': {
        const item = p['item'] as Record<string, unknown>;
        if (!item) break;
        const id = String(item['id']);
        itemsCache.delete(id);
        if (item['type'] === 'commandExecution') {
          const status = item['status'];
          const isError = status === 'failed' || status === 'declined';
          const output =
            status === 'declined'
              ? 'Denied by user.'
              : `${item['aggregatedOutput'] ?? ''}${item['exitCode'] ? `\n[exit code ${item['exitCode']}]` : ''}`;
          writeEvent({ type: 'tool_result', toolUseId: id, isError, output: truncate(output), truncated: output.length > TOOL_OUTPUT_LIMIT });
        } else if (item['type'] === 'fileChange') {
          const status = item['status'];
          const isError = status === 'failed' || status === 'declined';
          const changes = (item['changes'] as { path: string; diff: string }[] | undefined) ?? [];
          const output = status === 'declined' ? 'Denied by user.' : changes.map((c) => `${c.path}:\n${c.diff}`).join('\n\n');
          writeEvent({ type: 'tool_result', toolUseId: id, isError, output: truncate(output), truncated: output.length > TOOL_OUTPUT_LIMIT });
        } else if (item['type'] === 'mcpToolCall') {
          const error = item['error'] as { message?: string } | null | undefined;
          const result = item['result'] as { content?: { type: string; text?: string }[] } | null | undefined;
          const output = error
            ? (error.message ?? 'MCP tool call failed')
            : (result?.content ?? [])
                .map((b) => (b.type === 'text' && typeof b.text === 'string' ? b.text : JSON.stringify(b)))
                .join('\n');
          writeEvent({ type: 'tool_result', toolUseId: id, isError: !!error, output: truncate(output), truncated: output.length > TOOL_OUTPUT_LIMIT });
        } else if (item['type'] === 'agentMessage' && typeof item['text'] === 'string' && item['text']) {
          writeEvent({ type: 'assistant_message', content: [{ type: 'text', text: item['text'] }] });
        }
        break;
      }
      case 'thread/tokenUsage/updated': {
        const usage = p['tokenUsage'] as Record<string, unknown> | undefined;
        if (usage && usage['total']) latestUsage = usage['total'];
        const last = usage?.['last'] as Record<string, unknown> | undefined;
        if (last && typeof last['totalTokens'] === 'number') latestContextTokens = last['totalTokens'];
        if (typeof usage?.['modelContextWindow'] === 'number') latestContextWindow = usage['modelContextWindow'];
        break;
      }
      case 'turn/completed': {
        const turn = p['turn'] as Record<string, unknown> | undefined;
        if (turn && awaitingTurnComplete) awaitingTurnComplete(turn);
        break;
      }
      case 'thread/compacted':
        if (awaitingCompact) awaitingCompact();
        break;
      case 'error':
        writeEvent({ type: 'error', message: typeof p === 'string' ? p : JSON.stringify(p), fatal: false });
        break;
      case 'warning':
      case 'configWarning':
        writeEvent({ type: 'error', message: String(p['summary'] ?? p['message'] ?? JSON.stringify(p)), fatal: false });
        break;
      default:
        // Confirmed-noisy notifications (mcpServer/*, remoteControl/*, account/*,
        // thread/status/changed, turn/started, turn/diff/updated,
        // serverRequest/resolved, item/started|completed for userMessage/reasoning
        // items, …) — dropped silently, see the event-mapping table's last row.
        break;
    }
  }

  function handleServerRequest(method: string, id: number, params: unknown): void {
    const p = (params ?? {}) as Record<string, unknown>;
    if (method === 'item/commandExecution/requestApproval') {
      pendingApprovals.set(String(id), { availableDecisions: p['availableDecisions'], kind: 'decision' });
      writeEvent({
        type: 'permission_request',
        requestId: String(id),
        toolName: 'Bash',
        input: { command: p['command'], cwd: p['cwd'], ...(p['reason'] ? { reason: p['reason'] } : {}) },
        suggestions: Array.isArray(p['availableDecisions']) ? (p['availableDecisions'] as unknown[]) : [],
      });
      return;
    }
    if (method === 'item/fileChange/requestApproval') {
      const cached = itemsCache.get(String(p['itemId']));
      pendingApprovals.set(String(id), { availableDecisions: p['availableDecisions'], kind: 'decision' });
      writeEvent({
        type: 'permission_request',
        requestId: String(id),
        toolName: 'Edit',
        input: { changes: cached?.['changes'] ?? [], ...(p['reason'] ? { reason: p['reason'] } : {}) },
        suggestions: Array.isArray(p['availableDecisions']) ? (p['availableDecisions'] as unknown[]) : [],
      });
      return;
    }
    if (method === 'mcpServer/elicitation/request') {
      // Codex ≥0.151 surfaces MCP-server elicitations here — including its OWN approval
      // prompt for thread-scoped MCP tool calls (meta codex_approval_kind = 'mcp_tool_call',
      // a Form with an empty requestedSchema). Response shape is
      // McpServerElicitationRequestResponse: { action: accept|decline|cancel, content } —
      // NOT the { decision } shape of item/*/requestApproval (confirmed live: replying
      // { decision } fails deserialization with "missing field `action`").
      const meta = (p['_meta'] ?? {}) as Record<string, unknown>;
      const server = typeof p['serverName'] === 'string' ? (p['serverName'] as string) : 'mcp';
      const tool = typeof meta['tool_name'] === 'string' ? (meta['tool_name'] as string) : undefined;
      const params = meta['tool_params'];
      pendingApprovals.set(String(id), { availableDecisions: undefined, kind: 'elicitation' });
      writeEvent({
        type: 'permission_request',
        requestId: String(id),
        // same mcp__<server>__<tool> naming as mapItem's mcpToolCall, so the dashboard's
        // permission card renders it like the Claude provider's prompt for the same tool
        toolName: tool ? `mcp__${server}__${tool}` : `mcp__${server}`,
        input: params && typeof params === 'object'
          ? (params as Record<string, unknown>)
          : { ...(typeof p['message'] === 'string' ? { message: p['message'] } : {}) },
        suggestions: [],
      });
      return;
    }
    log(`unhandled server request method: ${method} — auto-denying so the child never hangs`);
    rpc.respond(id, { decision: 'decline' });
  }

  function handlePermissionResponse(cmd: PermissionResponseCommand): void {
    const entry = pendingApprovals.get(cmd.requestId);
    if (!entry) {
      log(`permission_response for unknown/already-settled request ${cmd.requestId}`);
      return;
    }
    pendingApprovals.delete(cmd.requestId);
    if (entry.kind === 'elicitation') {
      rpc.respond(Number(cmd.requestId), cmd.behavior === 'allow'
        ? { action: 'accept', content: {} }
        : { action: 'decline', content: null });
      return;
    }
    const decision = cmd.behavior === 'allow' ? allowDecision() : denyDecision(entry.availableDecisions);
    rpc.respond(Number(cmd.requestId), { decision });
  }

  function handleCommand(line: string): void {
    let cmd: Command;
    try {
      cmd = JSON.parse(line) as Command;
    } catch {
      writeEvent({ type: 'error', message: `malformed command line: ${line.slice(0, 200)}`, fatal: false });
      return;
    }
    switch (cmd.type) {
      case 'user_message':
        queuedOrRunning++;
        inputQueue.push({ kind: 'turn', text: cmd.text });
        break;
      case 'compact':
        if (queuedOrRunning > 0) {
          writeEvent({ type: 'error', message: 'compact: turn in progress', fatal: false });
          break;
        }
        queuedOrRunning++;
        inputQueue.push({ kind: 'compact' });
        break;
      case 'permission_response':
        handlePermissionResponse(cmd);
        break;
      case 'interrupt':
        denyAllPendingApprovals('interrupted by user');
        if (threadId && currentTurnId) {
          void rpc.call('turn/interrupt', { threadId, turnId: currentTurnId }).catch((e: unknown) => {
            writeEvent({ type: 'error', message: `interrupt failed: ${String(e)}`, fatal: false });
          });
        }
        break;
      case 'set_permission_mode':
        if (cmd.mode !== 'default' && cmd.mode !== 'bypassPermissions') {
          writeEvent({ type: 'error', message: `codex provider does not support permission mode '${cmd.mode}'`, fatal: false });
          break;
        }
        currentPermissionMode = cmd.mode;
        writeEvent({ type: 'permission_mode_changed', mode: cmd.mode });
        break;
      case 'set_model':
        currentModelOverride = cmd.model;
        writeEvent({ type: 'model_changed', model: cmd.model });
        break;
      case 'shutdown':
        exiting = 'shutdown';
        denyAllPendingApprovals('session shutting down');
        inputQueue.close();
        break;
      default:
        writeEvent({
          type: 'error',
          message: `unknown command type: ${(cmd as { type?: string }).type ?? '<none>'}`,
          fatal: false,
        });
    }
  }

  rpc.onNotification(handleNotification);
  rpc.onServerRequest(handleServerRequest);

  readLines(process.stdin, handleCommand, () => {
    if (!exiting) {
      exiting = 'stdin_closed';
      denyAllPendingApprovals('stdin closed');
      inputQueue.close();
    }
  });

  try {
    await rpc.call('initialize', {
      clientInfo: { name: 'claude-ui-sidecar-codex', title: 'claude-ui', version: '0.1.0' },
      capabilities: null,
    });

    // Reuse the worktree's existing skill materialization (AssetProvisioningService
    // already writes it for every provider) — Codex reads the same SKILL.md format
    // but only discovers it via an explicit extra root, confirmed live (see
    // docs/plan/phase-5.13-codex-provider.md's skills follow-up).
    const skillsDir = join(config.cwd, '.claude', 'skills');
    if (existsSync(skillsDir)) {
      try {
        await rpc.call('skills/extraRoots/set', { extraRoots: [skillsDir] });
      } catch (e) {
        writeEvent({ type: 'error', message: `skills/extraRoots/set failed: ${String(e)}`, fatal: false });
      }
    }

    const startMethod = config.resume ? 'thread/resume' : 'thread/start';
    const startParams: Record<string, unknown> = config.resume
      ? { threadId: config.resume, approvalPolicy: approvalPolicyFor(currentPermissionMode), sandbox: sandboxModeFor(currentPermissionMode) }
      : { cwd: config.cwd, approvalPolicy: approvalPolicyFor(currentPermissionMode), sandbox: sandboxModeFor(currentPermissionMode) };
    if (config.model) startParams['model'] = config.model;
    if (config.appendSystemPrompt) startParams['developerInstructions'] = config.appendSystemPrompt;
    if (Object.keys(mcpServers).length > 0) startParams['config'] = { mcp_servers: mcpServers };

    const started = await rpc.call<{ thread: { id: string }; model: string; cwd: string }>(startMethod, startParams);
    threadId = started.thread.id;
    currentModel = started.model;

    writeEvent({
      type: 'system_init',
      providerSessionId: threadId,
      model: currentModel,
      cwd: started.cwd,
      tools: [],
      // Real per-server lifecycle arrives async via mcpServer/startupStatus/updated
      // notifications, which this adapter doesn't stream into the journal for this
      // pass (see the plan doc's "Also out of scope" note) — this is a static
      // snapshot of what was configured, not a live connection state.
      mcpServers: Object.keys(mcpServers).map((name) => ({ name, status: 'configuring' })),
      permissionMode: currentPermissionMode,
    });

    for (;;) {
      const item = await inputQueue.next();
      if (item === undefined) break;
      if (item.kind === 'turn') await runTurn(item.text);
      else await doCompact();
      queuedOrRunning--;
    }
  } catch (e) {
    writeEvent({ type: 'error', message: String(e instanceof Error ? e.stack ?? e.message : e), fatal: true });
    writeEvent({ type: 'exiting', reason: 'fatal' });
    rpc.child.kill();
    process.exit(1);
  }

  writeEvent({ type: 'exiting', reason: exiting ?? 'shutdown' });
  rpc.child.kill();
  process.exit(0);
}
