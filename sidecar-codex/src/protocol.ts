/**
 * Provider adapter protocol, version 1.
 *
 * NDJSON over stdio: each line on stdin is one Command, each line on stdout is one
 * Event. stdout carries protocol JSON exclusively; all logging goes to stderr.
 * The contract is provider-neutral — `sidecar/` is the Claude reference
 * implementation. See docs/PROTOCOL.md.
 *
 * This file is a synced copy of `sidecar/src/protocol.ts`'s shared types (kept as a
 * plain copy rather than a cross-package import to avoid coupling this package's
 * build to `sidecar/`'s build order — see docs/plan/phase-5.13-codex-provider.md
 * Decision 4). If protocol v1 changes, update both copies. `CODEX_CAPABILITIES` at
 * the bottom is specific to this package.
 */

export const PROTOCOL_VERSION = 1;

export type PermissionMode = 'default' | 'acceptEdits' | 'plan' | 'bypassPermissions';

// ---------------------------------------------------------------------------
// Commands (backend/driver -> sidecar)
// ---------------------------------------------------------------------------

export interface UserMessageCommand {
  type: 'user_message';
  text: string;
}

export interface PermissionResponseCommand {
  type: 'permission_response';
  requestId: string;
  behavior: 'allow' | 'deny';
  /** allow only: replaces the tool input (e.g. an edited Bash command) */
  updatedInput?: Record<string, unknown>;
  /** deny only: reason shown to the model */
  message?: string;
}

export interface InterruptCommand {
  type: 'interrupt';
}

export interface SetPermissionModeCommand {
  type: 'set_permission_mode';
  mode: PermissionMode;
}

export interface SetModelCommand {
  type: 'set_model';
  model: string;
}

export interface ShutdownCommand {
  type: 'shutdown';
}

export type Command =
  | UserMessageCommand
  | PermissionResponseCommand
  | InterruptCommand
  | SetPermissionModeCommand
  | SetModelCommand
  | ShutdownCommand;

// ---------------------------------------------------------------------------
// Events (sidecar -> backend/driver)
// ---------------------------------------------------------------------------

export type EffortLevel = 'low' | 'medium' | 'high' | 'xhigh' | 'max';

/** 'off' | 'adaptive' (model decides) | fixed token budget */
export type ThinkingSetting = 'off' | 'adaptive' | number;

export interface ModelInfo {
  id: string;
  label: string;
  tier: 'cheap' | 'standard' | 'premium';
}

export interface Capabilities {
  permissionModes: PermissionMode[];
  thinking: boolean;
  /** supports reasoning-effort levels (low..max) */
  effort: boolean;
  planMode: boolean;
  resume: boolean;
  skills: boolean;
  agents: boolean;
  mcp: boolean;
  interrupt: boolean;
  fallbackModel: boolean;
  /** permission responses may carry updatedInput */
  updatedInput: boolean;
  /** supports set_model mid-session */
  modelSwitch: boolean;
  /** known model catalog for this provider; empty = no fixed list (free-text model input) */
  models: ModelInfo[];
  /**
   * Session/create-option field names this provider rejects outright (backend enforces
   * at creation time — see docs/plan/phase-10-review-followups.md R1). Field names match
   * the create-options JSON keys, e.g. "allowedTools", "maxTurns".
   */
  unsupportedSessionFields: string[];
  /** supports read-only ecosystem/service context directories (--context-dir); false degrades with a warning, not a hard reject */
  contextDirs: boolean;
  /** turn_complete.costUsd is authoritative; false = the backend estimates from token usage against a price table */
  reportsCostUsd: boolean;
}

export interface ReadyEvent {
  type: 'ready';
  pid: number;
  protocolVersion: typeof PROTOCOL_VERSION;
  provider: string;
  capabilities: Capabilities;
}

export interface SystemInitEvent {
  type: 'system_init';
  providerSessionId: string;
  model: string;
  cwd: string;
  tools: string[];
  mcpServers: { name: string; status: string }[];
  permissionMode: PermissionMode;
}

export interface StreamDeltaEvent {
  type: 'stream_delta';
  deltaType: 'text' | 'thinking';
  text: string;
}

export interface AssistantMessageEvent {
  type: 'assistant_message';
  /** Anthropic-format content blocks (text / thinking / tool_use) */
  content: unknown[];
}

export interface ToolStartedEvent {
  type: 'tool_started';
  toolUseId: string;
  name: string;
  input: Record<string, unknown>;
}

export interface ToolResultEvent {
  type: 'tool_result';
  toolUseId: string;
  isError: boolean;
  output: string;
  truncated: boolean;
}

export interface PermissionRequestEvent {
  type: 'permission_request';
  requestId: string;
  toolName: string;
  input: Record<string, unknown>;
  /** provider-suggested permission updates, opaque to the backend */
  suggestions: unknown[];
}

/**
 * Live progress while the model thinks with redacted thinking (Claude 5 models):
 * a running token estimate for the current thinking block. UIs use this for
 * spinners/"thinking…" pills; it is not billed usage.
 */
export interface ThinkingProgressEvent {
  type: 'thinking_progress';
  estimatedTokens: number;
  estimatedTokensDelta: number;
}

export interface PermissionModeChangedEvent {
  type: 'permission_mode_changed';
  mode: PermissionMode;
}

export interface ModelChangedEvent {
  type: 'model_changed';
  model: string;
}

export interface TurnCompleteEvent {
  type: 'turn_complete';
  stopReason: string;
  usage: unknown;
  costUsd: number;
  durationMs: number;
  numTurns: number;
  /** model that produced this turn (last-seen system_init.model at completion time) */
  model: string;
}

export interface ErrorEvent {
  type: 'error';
  message: string;
  fatal: boolean;
}

export interface ExitingEvent {
  type: 'exiting';
  reason: 'shutdown' | 'fatal' | 'stdin_closed';
}

export type Event =
  | ReadyEvent
  | SystemInitEvent
  | StreamDeltaEvent
  | AssistantMessageEvent
  | ToolStartedEvent
  | ToolResultEvent
  | PermissionRequestEvent
  | ThinkingProgressEvent
  | PermissionModeChangedEvent
  | ModelChangedEvent
  | TurnCompleteEvent
  | ErrorEvent
  | ExitingEvent;

/**
 * Codex has no plan mode, no static subagent-file equivalent, and no thinking-budget
 * axis distinct from reasoning effort. skills/mcp were flipped true in the
 * 2026-08-30 follow-up once both were confirmed live-feasible. See
 * docs/plan/phase-5.13-codex-provider.md "Capabilities announcement" for the
 * live-confirmed rationale behind each value — must stay in sync with
 * ProviderController.java's Java-side constant.
 */
export const CODEX_CAPABILITIES: Capabilities = {
  permissionModes: ['default', 'bypassPermissions'],
  thinking: false,
  effort: true,
  planMode: false,
  resume: true,
  skills: true,
  agents: false,
  mcp: true,
  interrupt: true,
  fallbackModel: false,
  updatedInput: false,
  modelSwitch: true,
  // Codex resolves its own default model/alias with no fixed enumeration we can offer
  // up front (docs/plan/phase-5.13-codex-provider.md: "no hardcoded Codex model list")
  // — empty means the frontend falls back to a free-text model field.
  models: [],
  unsupportedSessionFields: ['allowedTools', 'disallowedTools', 'thinking', 'maxTurns', 'fallbackModel', 'agentSources'],
  contextDirs: false,
  reportsCostUsd: false,
};
