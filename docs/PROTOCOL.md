# Provider Adapter Protocol (v1)

The contract between the backend and a per-session **provider adapter** process.
`sidecar/` is the Claude reference implementation (`sidecar/src/protocol.ts` is the
typed source of truth); any process speaking this protocol plugs into the same
backend and UI. Provider-specific behavior stays inside the adapter.

## Transport

- NDJSON over stdio: one JSON object per line. stdin carries **commands**, stdout
  carries **events** and nothing else. All logging goes to stderr.
- No line-length limit on either side. Malformed or unknown input must produce a
  non-fatal `error` event, never a crash.
- Closing stdin is equivalent to `shutdown`. Exit code 0 = clean end, non-zero = fatal.

## Adapter invocation (Claude sidecar)

```
node sidecar/dist/index.js --cwd <dir>
  [--resume <provider-session-id>] [--model <m>] [--fallback-model <m>]
  [--permission-mode default|acceptEdits|plan|bypassPermissions]
  [--allowed-tools <csv>] [--disallowed-tools <csv>] [--mcp-config <path.json>]
  [--append-system-prompt <text>] [--context-dir <path>]...
  [--thinking off|adaptive|<budgetTokens>] [--effort low|medium|high|xhigh|max]
  [--max-turns <n>]
```

`--context-dir` (repeatable) attaches read-only context directories: reads are
allowed, file-modifying tools targeting paths outside `--cwd` are auto-denied by the
adapter with an explanatory non-fatal `error` event (no permission round-trip).
Bash is not path-policed — it flows through the normal approval path.

## Commands (backend → adapter)

| type | fields | semantics |
|---|---|---|
| `user_message` | `text` | queue the next user turn |
| `permission_response` | `requestId`, `behavior: allow\|deny`, `updatedInput?`, `message?` | settle a `permission_request`; `updatedInput` replaces the tool input on allow (e.g. an edited Bash command); `message` is the denial reason shown to the model |
| `interrupt` | — | abort the in-flight turn; outstanding permission requests are denied internally; the session stays alive |
| `set_permission_mode` | `mode` | switch permission mode mid-session; acknowledged by `permission_mode_changed` |
| `shutdown` | — | finish current work, then exit 0 with `exiting` |

## Events (adapter → backend)

| type | fields | when |
|---|---|---|
| `ready` | `pid`, `protocolVersion: 1`, `provider`, `capabilities` | first event after start |
| `system_init` | `providerSessionId`, `model`, `cwd`, `tools[]`, `mcpServers[]`, `permissionMode` | provider session established; the backend must persist `providerSessionId` to enable `--resume` |
| `stream_delta` | `deltaType: text\|thinking`, `text` | incremental generation output |
| `assistant_message` | `content[]` (Anthropic-format blocks) | each completed assistant message |
| `tool_started` | `toolUseId`, `name`, `input` | tool call issued |
| `tool_result` | `toolUseId`, `isError`, `output` (≤16 KB), `truncated` | tool finished |
| `permission_request` | `requestId`, `toolName`, `input`, `suggestions[]` | user approval needed; adapter blocks that tool until the matching `permission_response` (no timeout — waiting is the UI's job) |
| `thinking_progress` | `estimatedTokens`, `estimatedTokensDelta` | running token estimate while the model thinks (drive spinners/pills; not billed usage) |
| `permission_mode_changed` | `mode` | confirms `set_permission_mode` |
| `turn_complete` | `stopReason`, `usage`, `costUsd`, `durationMs`, `numTurns` | end of an agentic turn |
| `error` | `message`, `fatal` | non-fatal: guard denials, malformed input, transient provider errors; fatal: precedes exit 1 |
| `exiting` | `reason: shutdown\|fatal\|stdin_closed` | last event before exit |

## Capabilities

`ready.capabilities` declares what the adapter supports; the UI renders controls from
this, never from the provider name:

```json
{
  "permissionModes": ["default", "acceptEdits", "plan", "bypassPermissions"],
  "thinking": true, "effort": true, "planMode": true, "resume": true, "skills": true,
  "agents": true, "mcp": true, "interrupt": true, "fallbackModel": true,
  "updatedInput": true
}
```

## Behavioral notes (Claude adapter)

- **Not every Bash call prompts.** Claude Code sandboxes harmless commands (e.g. a
  pure `echo`) and runs them without a permission request; commands with side effects
  outside the sandbox trigger `permission_request`. UIs must not assume one prompt per
  Bash invocation.
- `permission_request.suggestions` carries the SDK's proposed permission rules
  (opaque; forward to the UI for future "always allow" affordances).
- Skills/agents are discovered from `<cwd>/.claude/skills` and `.claude/agents` at
  process start (the adapter loads project settings); changes require a respawn —
  which `--resume` makes cheap.
- Thinking control is `--thinking` (off / adaptive / fixed budget) + `--effort`
  (reasoning-effort level). On Claude 5 models thinking is **redacted**: raw deltas
  carry token estimates, not text — the adapter requests `display: 'summarized'`, so
  UIs receive readable summarized `stream_delta(thinking)` text plus
  `thinking_progress` estimates. Adaptive mode means the model decides when to think:
  trivial prompts legitimately produce zero thinking events even with thinking
  enabled. (The SDK's `maxThinkingTokens` is deprecated and not used.)
- Model IDs in `system_init` are concrete (e.g. `claude-sonnet-5`) even when the
  adapter was launched with an alias (`sonnet`).
- After `interrupt`, the turn ends with a `turn_complete` (possibly an error subtype)
  or a non-fatal `error`; the process remains usable for further turns.
- A `rate_limit_event` from the provider is currently ignored by the adapter
  (backend-level rate-limit surfacing arrives with Phase 4).

## WebSocket contract (backend ↔ UI)

Defined in Phase 2; will wrap these events in a journal envelope
`{seq, ts, type, payload}` with `afterSeq` replay. This file will be extended then.
