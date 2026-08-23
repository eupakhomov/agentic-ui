# Phase 1 — Session Engine Sidecar

**Goal:** a standalone Node/TypeScript process (`sidecar/`) that wraps one Claude Code
session via `@anthropic-ai/claude-agent-sdk` and speaks a small NDJSON protocol over
stdio. Proven **from the terminal, with no backend involved**. This is the riskiest
integration in the project, so it comes first and gets exercised hardest.

**Estimated effort:** 1–2 days.

## Why a sidecar (recap)
The Agent SDK gives stable, typed access to streaming messages, `canUseTool`
permission callbacks, interrupts and resume — the same things the raw CLI only exposes
through an undocumented control protocol. The sidecar translates SDK events into our
own contract so the Java backend never depends on SDK/CLI internals.

The NDJSON contract below is deliberately provider-neutral: it is the **provider
adapter interface**, and this sidecar is its Claude *reference implementation*. Any
process that speaks the contract (e.g. a future Codex CLI adapter, Phase 5) plugs into
the same backend/UI. Adapters announce what they support via the `capabilities` object
in `ready`; the UI renders controls from that, so a rich adapter is never dumbed down
by a poor one.

## Package layout

```
sidecar/
├─ package.json            # deps: @anthropic-ai/claude-agent-sdk; dev: typescript, tsx
├─ tsconfig.json
└─ src/
   ├─ index.ts             # arg parsing, wiring, shutdown handling
   ├─ protocol.ts          # TypeScript types for every command/event (source of truth)
   ├─ session.ts           # SDK query() lifecycle, streaming-input generator
   ├─ permissions.ts       # canUseTool → permission_request/response bridge (pending map)
   └─ stdio.ts             # NDJSON framing: line reader (stdin), writer (stdout)
```

## Invocation

```
node dist/index.js \
  --cwd <worktree-path> \
  [--resume <provider-session-id>] \
  [--model sonnet|opus|haiku|<full-id>] \
  [--permission-mode default|acceptEdits|plan|bypassPermissions] \
  [--allowed-tools "Read,Edit,Bash(npm test:*)"] \
  [--disallowed-tools "WebSearch"] \
  [--mcp-config <path.json>] \
  [--append-system-prompt <text>] \
  [--context-dir <path>]... \
  [--max-thinking-tokens <n>] \
  [--max-turns <n>] \
  [--fallback-model <model>]
```

`--max-thinking-tokens` maps to SDK `maxThinkingTokens` (0 = thinking off);
`--max-turns` caps agentic turns per user message (runaway protection, SDK `maxTurns`);
`--fallback-model` is used by the SDK when the primary model is overloaded.

`--context-dir` (repeatable) maps to the SDK's `additionalDirectories`: the ecosystem
parent folder (and any extra paths) become readable so Claude can consult sibling
services. Context dirs are **read-only by policy**: `canUseTool` auto-denies
file-modifying tools (`Edit`, `Write`, `NotebookEdit`) whose target path falls outside
`--cwd`, with a denial message explaining why — no round-trip to the UI. (Bash can
still write anywhere; that remains governed by the normal Bash permission flow, where
the user sees the command before approving.)

Rules:
- stdout carries **only** protocol NDJSON; all logs go to stderr.
- One SDK `query()` with an async-generator input stream (streaming input mode) so a
  single process serves unlimited turns; `includePartialMessages: true` for token deltas.
- Exit code 0 on clean `shutdown`, non-zero on fatal errors (bad cwd, SDK failure).

## Protocol (v1) — authoritative once implemented, mirrored into `docs/PROTOCOL.md`

### Commands (backend/driver → sidecar, one JSON per line on stdin)

| type | fields | semantics |
|---|---|---|
| `user_message` | `text` | queue next user turn |
| `permission_response` | `requestId`, `behavior: "allow"\|"deny"`, `updatedInput?`, `message?` | resolve a pending `permission_request`; `updatedInput` lets the UI edit e.g. a Bash command before approval |
| `interrupt` | — | abort the in-flight turn (SDK `interrupt()`) |
| `set_permission_mode` | `mode: "default"\|"acceptEdits"\|"plan"\|"bypassPermissions"` | switch permission mode mid-session (SDK `setPermissionMode`); acknowledged with a `permission_mode_changed` event |
| `shutdown` | — | finish/abort current work, close input stream, exit 0 |

### Events (sidecar → backend, one JSON per line on stdout)

| type | fields | when |
|---|---|---|
| `ready` | `pid`, `protocolVersion: 1`, `provider: "claude"`, `capabilities` | process started, before SDK init. `capabilities`: `{permissionModes: [...], thinking, planMode, resume, skills, agents, mcp, interrupt, fallbackModel, updatedInput}` — booleans unless listed; the UI gates controls on these |
| `system_init` | `providerSessionId`, `model`, `cwd`, `tools[]`, `mcpServers[]`, `permissionMode` | SDK init message; **backend must persist `providerSessionId`** (enables `--resume`) |
| `stream_delta` | `deltaType: "text"\|"thinking"`, `text` | partial-message text chunks during generation |
| `assistant_message` | `content[]` (SDK content blocks) | each completed assistant message |
| `tool_started` | `toolUseId`, `name`, `input` | tool call issued |
| `tool_result` | `toolUseId`, `isError`, `output` (truncated to 16 KB, `truncated: true` flag) | tool finished |
| `permission_request` | `requestId`, `toolName`, `input`, `suggestions[]` (SDK permission suggestions, if any) | `canUseTool` fired; sidecar blocks this tool until response (no timeout — waiting is the UI's job) |
| `permission_mode_changed` | `mode` | confirms a `set_permission_mode` command |
| `turn_complete` | `stopReason`, `usage`, `costUsd`, `durationMs`, `numTurns` | SDK result message per turn |
| `error` | `message`, `fatal: bool` | SDK/stream errors; `fatal:true` precedes exit |
| `exiting` | `reason: "shutdown"\|"fatal"\|"stdin_closed"` | last event before process exit |

Unknown command types → `error` event (`fatal:false`), never a crash.
Malformed stdin line → same. Closing stdin ≙ `shutdown`.

## Tasks

1. Scaffold `sidecar/` (npm, tsconfig, strict TS, build via `tsc`, dev via `tsx`).
2. `stdio.ts`: robust line framing (no length limit, handles partial chunks), typed send/receive.
3. `session.ts`: SDK `query()` with streaming input generator fed by a command queue;
   map SDK message stream → protocol events (incl. `includePartialMessages` deltas).
   `settingSources` must include `"project"` so skills/settings materialized into the
   worktree's `.claude/` by the backend (Phase 2) are actually discovered — the SDK
   skips filesystem settings by default.
4. `permissions.ts`: `canUseTool` implementation — emit `permission_request`, await
   matching `permission_response` via pending-promise map, translate allow/deny +
   `updatedInput` into the SDK's expected return shape. Before any of that: the
   read-only guard — auto-deny `Edit`/`Write`/`NotebookEdit` targeting paths outside
   `--cwd` (context dirs), emitting a non-fatal informational `error` event so the
   transcript shows why.
5. Interrupt + shutdown handling; `--resume` pass-through; option plumbing for model,
   permission mode, tool lists, MCP config.
6. **Driver script** `npm run drive -- <args>`: tiny REPL that pretty-prints events and
   lets you type raw commands or shorthands (`:allow p1`, `:deny p1 reason`, `:int`) —
   this is the manual-test harness and stays in the repo permanently.
7. Write `docs/PROTOCOL.md` (sidecar half) from `protocol.ts`.

## Out of scope
- Anything Java/HTTP/WS; git worktrees (driver runs in any scratch directory);
  persistence; multi-session anything (one sidecar = one session by design).

## Definition of Done
- [ ] `npm run build` clean under strict TypeScript; `protocol.ts` fully types every message.
- [ ] Full happy path via driver: `user_message` → `system_init` + `stream_delta`s → `assistant_message` → `turn_complete` with real `usage`/`costUsd`.
- [ ] Multi-turn: a second `user_message` in the same process keeps conversation context.
- [ ] Permission flow: with `--permission-mode default` and no allowlist, a Bash-using prompt yields `permission_request`; **allow** runs the tool, **deny** makes Claude adapt without crashing, **allow with `updatedInput`** runs the modified command.
- [ ] `interrupt` mid-generation stops the turn; process stays alive and accepts the next turn.
- [ ] Thinking: with `--max-thinking-tokens 10000`, a hard problem emits `thinking` deltas; with `0`, none.
- [ ] `set_permission_mode acceptEdits` mid-session makes the next file edit apply without a `permission_request`; switching back to `default` restores per-edit prompts. `plan` mode: the plan-approval moment (ExitPlanMode) surfaces as a `permission_request` the driver can approve.
- [ ] `--max-turns 2` stops a long agentic task after 2 turns with the corresponding `stopReason`.
- [ ] Resume: kill the sidecar (SIGKILL), restart with `--resume <providerSessionId>`, ask "what did we just talk about" — context is preserved.
- [ ] `ready` announces the full Claude capabilities set; `docs/PROTOCOL.md` documents the contract as the provider adapter interface, Claude specifics marked as one implementation.
- [ ] Context dirs: with `--context-dir <other-folder>`, Claude can read/search files there; asking it to edit a file there is auto-denied with a clear message and the session continues normally.
- [ ] Skills discovery: a skill placed in `<cwd>/.claude/skills/<name>/SKILL.md` before start is visible to Claude (asking "what skills are available" lists it) and invocable.
- [ ] Closing stdin exits 0 with `exiting`; invalid JSON on stdin produces `error`, not a crash.
- [ ] stdout contains exclusively valid NDJSON during all of the above (verified by piping through `jq -c .`).
- [ ] `docs/PROTOCOL.md` sidecar section written.

## Manual test script

Run in a scratch dir with a few source files. `T` = terminal with `npm run drive`.

| # | Action | Expected |
|---|---|---|
| 1 | `npm run drive -- --cwd /tmp/scratch --model sonnet` | `ready`, then prompt |
| 2 | Send `hello, what files are here?` | `system_init` (note `providerSessionId`), `stream_delta`s render incrementally, tool events for listing, `turn_complete` with cost |
| 3 | Send `run "echo hi" with bash` | `permission_request` for Bash |
| 4 | `:allow <requestId>` | `tool_started`/`tool_result` with `hi`, turn completes |
| 5 | Same prompt again, `:deny <id> not now` | Claude acknowledges denial gracefully |
| 6 | Bash request, allow with `updatedInput` cmd `echo changed` | result shows `changed` |
| 7 | Send a long prompt ("write a poem about every planet"), `:int` mid-stream | stream stops, process alive, next turn works |
| 8 | `kill -9 <pid>`; restart with `--resume <id>`; ask what was discussed | prior context recalled |
| 8b | Restart with `--context-dir /tmp/other`; ask to summarize a file there, then to edit it | read succeeds; edit auto-denied with explanation, session continues |
| 8c | Drop a test skill into `<cwd>/.claude/skills/`, restart driver, ask for available skills and invoke it | skill listed and executes |
| 8d | `:mode acceptEdits`, ask for a file edit; then `:mode default`, ask for another | first edit applies silently; second raises `permission_request` |
| 8e | Start with `--max-thinking-tokens 10000`, pose a tricky refactor question | `thinking` deltas precede the answer |
| 9 | `printf 'garbage\n' \| node dist/index.js --cwd /tmp/scratch` | `error` event, no crash; EOF → `exiting`, exit 0 |
| 10 | Repeat step 2 piping stdout to `jq -c . > /dev/null` | jq reports zero parse errors |
