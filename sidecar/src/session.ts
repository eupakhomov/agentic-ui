import { query, type Options, type SDKUserMessage } from '@anthropic-ai/claude-agent-sdk';
import {
  CLAUDE_CAPABILITIES,
  PROTOCOL_VERSION,
  type Command,
  type EffortLevel,
  type PermissionMode,
  type ThinkingSetting,
} from './protocol.js';
import { PermissionBridge, readOnlyDenial } from './permissions.js';
import { log, readLines, writeEvent } from './stdio.js';

export interface SidecarConfig {
  cwd: string;
  resume?: string;
  model?: string;
  fallbackModel?: string;
  permissionMode?: PermissionMode;
  allowedTools?: string[];
  disallowedTools?: string[];
  mcpConfigPath?: string;
  appendSystemPrompt?: string;
  contextDirs: string[];
  thinking?: ThinkingSetting;
  effort?: EffortLevel;
  maxTurns?: number;
}

function thinkingConfig(
  setting: ThinkingSetting,
): { type: 'disabled' } | { type: 'adaptive'; display: 'summarized' } | { type: 'enabled'; budgetTokens: number; display: 'summarized' } {
  if (setting === 'off') return { type: 'disabled' };
  // summarized display: on redacted-thinking models (Claude 5) this is the only way
  // to get user-visible thinking text; the raw stream carries token estimates only
  if (setting === 'adaptive') return { type: 'adaptive', display: 'summarized' };
  return { type: 'enabled', budgetTokens: setting, display: 'summarized' };
}

const TOOL_OUTPUT_LIMIT = Number(process.env['CLAUDE_UI_TOOL_OUTPUT_LIMIT'] ?? 16 * 1024);

/** Unbounded async FIFO bridging stdin commands to the SDK's streaming input. */
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

function toolResultToString(content: unknown): string {
  if (typeof content === 'string') return content;
  if (Array.isArray(content)) {
    return content
      .map((block) => {
        const b = block as { type?: string; text?: string };
        return b.type === 'text' && typeof b.text === 'string' ? b.text : JSON.stringify(block);
      })
      .join('\n');
  }
  return JSON.stringify(content ?? '');
}

async function loadMcpServers(path: string): Promise<Record<string, unknown>> {
  const { readFile } = await import('node:fs/promises');
  const parsed = JSON.parse(await readFile(path, 'utf8')) as Record<string, unknown>;
  // accept both a bare server map and the CLI's {"mcpServers": {...}} shape
  return (parsed['mcpServers'] as Record<string, unknown>) ?? parsed;
}

export async function runSession(config: SidecarConfig): Promise<never> {
  const bridge = new PermissionBridge();
  const inputQueue = new AsyncQueue<SDKUserMessage>();
  let exiting: 'shutdown' | 'stdin_closed' | undefined;

  writeEvent({
    type: 'ready',
    pid: process.pid,
    protocolVersion: PROTOCOL_VERSION,
    provider: 'claude',
    capabilities: CLAUDE_CAPABILITIES,
  });

  async function* userMessages(): AsyncGenerator<SDKUserMessage> {
    for (;;) {
      const msg = await inputQueue.next();
      if (msg === undefined) return;
      yield msg;
    }
  }

  const options: Options = {
    cwd: config.cwd,
    includePartialMessages: true,
    settingSources: ['project'],
    systemPrompt: config.appendSystemPrompt
      ? { type: 'preset', preset: 'claude_code', append: config.appendSystemPrompt }
      : { type: 'preset', preset: 'claude_code' },
    ...(config.resume ? { resume: config.resume } : {}),
    ...(config.model ? { model: config.model } : {}),
    ...(config.fallbackModel ? { fallbackModel: config.fallbackModel } : {}),
    ...(config.permissionMode ? { permissionMode: config.permissionMode } : {}),
    ...(config.allowedTools ? { allowedTools: config.allowedTools } : {}),
    ...(config.disallowedTools ? { disallowedTools: config.disallowedTools } : {}),
    ...(config.contextDirs.length > 0 ? { additionalDirectories: config.contextDirs } : {}),
    ...(config.thinking !== undefined ? { thinking: thinkingConfig(config.thinking) } : {}),
    ...(config.effort !== undefined ? { effort: config.effort } : {}),
    ...(config.maxTurns !== undefined ? { maxTurns: config.maxTurns } : {}),
    ...(config.mcpConfigPath ? { mcpServers: (await loadMcpServers(config.mcpConfigPath)) as never } : {}),
    stderr: (data: string) => log('cli:', data.trimEnd()),
    canUseTool: async (toolName, input, { suggestions }) => {
      const denial = readOnlyDenial(toolName, input, config.cwd);
      if (denial) {
        writeEvent({ type: 'error', message: denial, fatal: false });
        return { behavior: 'deny', message: denial };
      }
      return bridge.request(toolName, input, suggestions ?? []);
    },
  };

  const q = query({ prompt: userMessages(), options });

  const handleCommand = (line: string): void => {
    let cmd: Command;
    try {
      cmd = JSON.parse(line) as Command;
    } catch {
      writeEvent({ type: 'error', message: `malformed command line: ${line.slice(0, 200)}`, fatal: false });
      return;
    }
    switch (cmd.type) {
      case 'user_message':
        inputQueue.push({
          type: 'user',
          message: { role: 'user', content: [{ type: 'text', text: cmd.text }] },
          parent_tool_use_id: null,
          session_id: '',
        } as SDKUserMessage);
        break;
      case 'permission_response':
        bridge.resolve(cmd);
        break;
      case 'interrupt':
        bridge.denyAll('Interrupted by user');
        void q.interrupt().catch((e: unknown) => {
          writeEvent({ type: 'error', message: `interrupt failed: ${String(e)}`, fatal: false });
        });
        break;
      case 'set_permission_mode':
        void q
          .setPermissionMode(cmd.mode)
          .then(() => writeEvent({ type: 'permission_mode_changed', mode: cmd.mode }))
          .catch((e: unknown) =>
            writeEvent({ type: 'error', message: `set_permission_mode failed: ${String(e)}`, fatal: false }),
          );
        break;
      case 'shutdown':
        exiting = 'shutdown';
        bridge.denyAll('Session shutting down');
        inputQueue.close();
        break;
      default:
        writeEvent({
          type: 'error',
          message: `unknown command type: ${(cmd as { type?: string }).type ?? '<none>'}`,
          fatal: false,
        });
    }
  };

  readLines(process.stdin, handleCommand, () => {
    if (!exiting) {
      exiting = 'stdin_closed';
      bridge.denyAll('stdin closed');
      inputQueue.close();
    }
  });

  try {
    for await (const message of q) {
      switch (message.type) {
        case 'system':
          if ((message as { subtype: string }).subtype === 'thinking_tokens') {
            const m = message as unknown as { estimated_tokens: number; estimated_tokens_delta: number };
            writeEvent({
              type: 'thinking_progress',
              estimatedTokens: m.estimated_tokens,
              estimatedTokensDelta: m.estimated_tokens_delta,
            });
          } else if (message.subtype === 'init') {
            writeEvent({
              type: 'system_init',
              providerSessionId: message.session_id,
              model: message.model,
              cwd: message.cwd,
              tools: message.tools,
              mcpServers: message.mcp_servers,
              permissionMode: message.permissionMode as PermissionMode,
            });
          }
          break;
        case 'stream_event': {
          const event = message.event as {
            type: string;
            delta?: { type: string; text?: string; thinking?: string };
          };
          if (event.type === 'content_block_delta' && event.delta) {
            if (event.delta.type === 'text_delta' && event.delta.text) {
              writeEvent({ type: 'stream_delta', deltaType: 'text', text: event.delta.text });
            } else if (event.delta.type === 'thinking_delta' && event.delta.thinking) {
              writeEvent({ type: 'stream_delta', deltaType: 'thinking', text: event.delta.thinking });
            } else {
              log('unmapped stream delta:', JSON.stringify(event.delta).slice(0, 300));
            }
          }
          break;
        }
        case 'assistant': {
          const content = message.message.content as unknown[];
          writeEvent({ type: 'assistant_message', content });
          for (const block of content) {
            const b = block as { type: string; id?: string; name?: string; input?: Record<string, unknown> };
            if (b.type === 'tool_use' && b.id && b.name) {
              writeEvent({ type: 'tool_started', toolUseId: b.id, name: b.name, input: b.input ?? {} });
            }
          }
          break;
        }
        case 'user': {
          const content = message.message.content;
          if (Array.isArray(content)) {
            for (const block of content) {
              const b = block as {
                type: string;
                tool_use_id?: string;
                content?: unknown;
                is_error?: boolean;
              };
              if (b.type === 'tool_result' && b.tool_use_id) {
                const full = toolResultToString(b.content);
                writeEvent({
                  type: 'tool_result',
                  toolUseId: b.tool_use_id,
                  isError: b.is_error === true,
                  output: full.slice(0, TOOL_OUTPUT_LIMIT),
                  truncated: full.length > TOOL_OUTPUT_LIMIT,
                });
              }
            }
          }
          break;
        }
        case 'result':
          writeEvent({
            type: 'turn_complete',
            stopReason: message.subtype,
            usage: message.usage,
            costUsd: message.total_cost_usd,
            durationMs: message.duration_ms,
            numTurns: message.num_turns,
          });
          break;
        case 'rate_limit_event' as never: {
          // surface only non-nominal rate-limit statuses so the UI shows trouble, not noise
          const rl = (message as unknown as { rate_limit?: { status?: string } }).rate_limit;
          if (rl?.status && rl.status !== 'allowed') {
            writeEvent({
              type: 'error',
              message: `provider rate limit: ${JSON.stringify(rl).slice(0, 300)}`,
              fatal: false,
            });
          }
          break;
        }
        default:
          log('unhandled SDK message type:', (message as { type: string }).type);
      }
    }
  } catch (e) {
    writeEvent({ type: 'error', message: String(e instanceof Error ? e.stack ?? e.message : e), fatal: true });
    writeEvent({ type: 'exiting', reason: 'fatal' });
    process.exit(1);
  }

  writeEvent({ type: 'exiting', reason: exiting ?? 'shutdown' });
  process.exit(0);
}
