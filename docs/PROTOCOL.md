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
| `set_model` | `model` | switch model mid-session; takes effect starting the next assistant response; acknowledged by `model_changed` |
| `shutdown` | — | finish current work, then exit 0 with `exiting` |

## Events (adapter → backend)

| type | fields | when |
|---|---|---|
| `ready` | `pid`, `protocolVersion: 1`, `provider`, `capabilities` | first event after start |
| `system_init` | `providerSessionId`, `model`, `cwd`, `tools[]`, `mcpServers[]`, `permissionMode` | provider session established — arrives with the **first turn**, not at startup (`ready` is the liveness signal); the backend must persist `providerSessionId` to enable `--resume` |
| `stream_delta` | `deltaType: text\|thinking`, `text` | incremental generation output |
| `assistant_message` | `content[]` (Anthropic-format blocks) | each completed assistant message |
| `tool_started` | `toolUseId`, `name`, `input` | tool call issued |
| `tool_result` | `toolUseId`, `isError`, `output` (≤16 KB), `truncated` | tool finished |
| `permission_request` | `requestId`, `toolName`, `input`, `suggestions[]` | user approval needed; adapter blocks that tool until the matching `permission_response` (no timeout — waiting is the UI's job) |
| `thinking_progress` | `estimatedTokens`, `estimatedTokensDelta` | running token estimate while the model thinks (drive spinners/pills; not billed usage) |
| `permission_mode_changed` | `mode` | confirms `set_permission_mode` |
| `model_changed` | `model` | confirms `set_model` |
| `turn_complete` | `stopReason`, `usage`, `costUsd`, `durationMs`, `numTurns`, `model` | end of an agentic turn; `model` is the model that produced it (tracked from the preceding `system_init`, which fires at the start of every turn) |
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
  "updatedInput": true, "modelSwitch": true
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

## Behavioral notes (Codex adapter)

`sidecar-codex/` wraps `codex app-server`'s JSON-RPC-over-stdio protocol (the same one
IDE integrations use), not `codex exec`, which is non-interactive and has no
approval round-trip. Full mapping tables and rationale:
`docs/plan/phase-5.13-codex-provider.md`. Live-confirmed quirks (codex-cli 0.151.0):

- **Not every command/edit prompts, either** — same "not every Bash call prompts"
  behavior as Claude: `default` mode (`workspace-write` sandbox + `on-request`
  approval policy) auto-allows anything the sandbox already permits (in-workspace
  reads/writes, no network); only an action the sandbox denies by default (network
  access, writing outside the workspace) triggers a `permission_request`.
- **Deny semantics differ by item kind.** Declining a file-change approval lets the
  turn continue normally (the model sees the denial and responds in text). Declining a
  command-exec approval can instead **abort the whole turn** (`turn_complete` with
  `stopReason: interrupted`, no closing assistant message) when Codex didn't offer a
  plain `"decline"` option for that specific request (observed: only `"accept"` and
  `"cancel"` offered) — `"cancel"` is a turn-level abort, not a per-call denial. The
  adapter always picks from that request's own `availableDecisions`, never a hardcoded
  decision string.
- Only the **item-based** approval scheme (`item/commandExecution/requestApproval`,
  `item/fileChange/requestApproval`) was observed firing; the legacy scheme
  (`execCommandApproval`, `applyPatchApproval`) never appeared. An unrecognized
  server-request method is auto-declined with a logged warning, never left hanging.
- Usage arrives as running token counts via `thread/tokenUsage/updated` notifications
  during the turn, not on the `Turn` object itself — the adapter tracks the
  last-seen value and attaches it to `turn_complete.usage`. Codex reports no per-turn
  USD; `turn_complete.costUsd` is always `0` from this adapter — the backend
  overwrites it with an estimate from `usage` for `provider: codex` sessions
  (`SessionService.applyCodexCostEstimate`, Settings-editable price table).
- `numTurns` in `turn_complete` is always `1` — Codex has no multi-step-within-a-turn
  counter the way Claude's SDK does; one completed `turn/start` call is one turn.
- `set_permission_mode`/`set_model` apply starting the **next** turn (no live
  mid-turn change), acknowledged optimistically like the Claude adapter.
- A large volume of notifications (`mcpServer/*` for Codex's always-on internal
  `codex_apps` server, `remoteControl/*`, `account/*`, `thread/status/changed`,
  `turn/started`, `turn/diff/updated`, `serverRequest/resolved`, …) is expected and
  dropped silently — roughly 15 notification types beyond the ones this adapter maps,
  in a single two-tool-call turn.

## WebSocket contract (backend ↔ UI)

- Endpoint: `ws://host:8080/ws/sessions/{sessionId}?afterSeq=<n>`.
- Subprotocol carries auth: the client requests `["claude-ui.v1", "bearer.<token>"]`;
  the server validates the bearer entry and echoes `claude-ui.v1`. With no token
  configured (loopback-only mode) the bearer entry may be omitted.
- **Outbound**: every adapter event, wrapped in a journal envelope
  `{seq, ts, type, payload}` — `seq` is the per-session monotonic journal sequence.
  The backend also journals/broadcasts its own event types: `state_changed {state}`,
  `user_message {text}` (inbound messages echoed into the transcript),
  `queue_updated {queued:[{pos,text}]}`, `warning {message}`, `error`,
  `permission_response` (echo of the user's decision), and
  `pr_status_changed {url, status, previousStatus, headSha}` — emitted by the background
  PR-check poller (`PrCheckPollingService`) whenever a session's tracked PR's aggregate
  check-suite status changes; `status`/`previousStatus` are one of
  `PENDING|SUCCESS|FAILURE|MERGED|CLOSED|ERROR`.
- On connect the journal is replayed from `afterSeq`, terminated by
  `{seq, type: "replay_complete", payload:{lastSeq}}`, then live events follow —
  no gaps, no duplicates (seq strictly increases).
- **Inbound** commands: `user_message` (queued FIFO if a turn is running),
  `permission_response`, `interrupt`, `set_permission_mode`, `set_model`. Invalid input
  returns a non-journaled `{type: "command_error", payload:{message}}` frame.
- Slow consumers are disconnected (close code 1013); reconnect with the last seen
  `afterSeq` to catch up losslessly from the journal.
