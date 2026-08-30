# Phase 5.13 — Codex CLI provider adapter

Second implementation of the provider adapter interface (`docs/PROTOCOL.md`), wrapping
OpenAI's Codex CLI (`codex`, tested against `codex-cli 0.151.0`) so Codex sessions run
side by side with Claude sessions in the same dashboard, chosen per session. This is
the proof that the backend/UI are genuinely provider-agnostic — no UI code should ever
branch on `provider === 'codex'`; it must branch on `capabilities` only, exactly as the
Claude adapter already does.

## Why this doc exists

`phase-5-extensions.md` §5.13 and `ARCHITECTURE.md` §5.13 sketched this as "wrap
Codex's headless protocol, reduced capabilities, register under
`claude-ui.providers.codex`." Investigating the actual installed CLI surfaced enough
that needs pinning down before writing code that this earns its own doc, per the
pattern already used for 5.3/Phase 6.

## What research changed vs. the original sketch

- **`codex exec` cannot be the transport.** It's genuinely non-interactive — no
  approval round-trip on stdin (`--ask-for-approval` doesn't even exist as an `exec`
  flag; only `--sandbox` and `--approve-for-me`). The DoD requires an interactive
  tool-approval round trip, so the real target is **`codex app-server`**, the
  JSON-RPC-over-stdio protocol the VS Code/IDE integrations use.
- **`app-server` is `[experimental]`** per the CLI's own `--help`, and its full surface
  (`codex app-server generate-ts --experimental`) is enormous — realtime voice,
  plugins, marketplace, remote control, etc. The **non-experimental default surface**
  (`codex app-server generate-ts`, no flag) is a much smaller thread/turn-based API and
  is what this adapter targets. Even that stable surface showed **two parallel
  approval schemes** live at once in the generated types — legacy
  (`execCommandApproval`/`applyPatchApproval`) and item-based
  (`item/commandExecution/requestApproval`/`item/fileChange/requestApproval`). Which
  one the installed version actually fires is not resolvable by reading `.d.ts`-style
  bindings — it needs to be observed by actually driving the process. That's Task 1.
- **Codex does report a `skills/list` concept now**, contradicting the original
  sketch's "no skills" assumption — out of scope for this pass regardless (see below),
  but the capabilities announcement shouldn't casually claim `skills: false` without
  having looked.
- **No per-turn USD cost.** `Turn`/`ThreadTokenUsage` carry only token counts
  (`TokenUsageBreakdown`: input/output/cached/reasoning). The one USD-bearing type
  (`ThreadUsage.estimatedUsageUsdMicros`) is only reachable via `account/usage/read`,
  which is account-wide ChatGPT-plan credit usage, not per-session/per-turn cost, and
  is meaningless for API-key auth. Confirms cost must be computed locally from token
  counts.

## Decisions

1. **Scope: MVP matching the original DoD.** Prompt + interactive tool-approval round
   trip, resume, model switch, interrupt. No skills, no MCP config passthrough, no plan
   mode, no thinking/effort-summary streaming beyond plain assistant text. These are
   documented "out of scope for this pass," not rejected — see below.
2. **Cost: estimated from token counts against a Settings-editable price table.** Codex
   gives us tokens, not dollars, so `costUsd` for Codex turns is computed as
   `Σ rate[model][kind] × tokens[kind]` using a small per-model rate table stored as a
   **persisted setting** (`codex.pricing`, JSON: `{ "<model>": { "inputPer1M":
   number, "cachedInputPer1M": number, "outputPer1M": number } }`), shipped with
   built-in defaults for the models Codex currently offers so it works out of the box,
   editable in the Settings dialog without a redeploy. This feeds both the per-session
   cost budget guard and the usage dashboard the same way Claude's real cost does —
   those consumers don't need to know the number is estimated.
3. **Provider picker: per-session + template, plus a Settings default.** A `provider`
   `<select>` (from the *configured* provider ids — i.e. whatever's under
   `claude-ui.providers.*`, not a hardcoded two-item list) in `CreateSessionDialog`
   and `TemplateManager`, defaulting to a new persisted setting `session.default-
   provider` (Settings dialog → "Sessions", next to ecosystem root; defaults to
   `claude` if unset so existing installs are unaffected).
4. **New sibling package `sidecar-codex/`**, not a mode flag inside `sidecar/`. The
   existing `sidecar/` is deeply coupled to `@anthropic-ai/claude-agent-sdk`; the only
   shared surface with a Codex adapter is the NDJSON stdio plumbing (`stdio.ts`'s
   `readLines`/`writeEvent`/`log` helpers and `protocol.ts`'s types), which
   `sidecar-codex/` imports from `sidecar/dist` (or a tiny extracted `sidecar-
   protocol/` package if the import path proves awkward — decide during Task 2, not
   here) rather than duplicating.
5. **Auth mirrors the existing pattern**: sidecars inherit the invoking user's ambient
   CLI credentials. `sidecar-codex` assumes `codex login` has already been run
   (ChatGPT-plan or API-key auth, whichever the user has) and does zero credential
   handling of its own — same posture as the Claude sidecar and `~/.claude`. Add
   "codex CLI, logged in" as a new row in CLAUDE.md's Prerequisites table.
6. **Approval-scheme ambiguity: resolved.** Task 1 (done — see its notes) drove a real
   `codex app-server` (0.151.0) by hand and recorded the actual wire traffic for four
   scenarios: a no-approval turn, a command-exec approve, a command-exec deny, and a
   file-change deny. Only the **item-based** scheme fired
   (`item/commandExecution/requestApproval` / `item/fileChange/requestApproval`); the
   legacy `execCommandApproval`/`applyPatchApproval` methods never appeared. The
   adapter targets the item-based scheme as primary and treats an unrecognized
   server-request method as a generic non-fatal `error` (never a crash), per
   `docs/PROTOCOL.md`'s "malformed/unknown input" rule — cheap insurance against a
   future Codex version reverting or adding a third scheme.

## Capabilities announcement

```json
{
  "permissionModes": ["default", "bypassPermissions"],
  "thinking": false, "effort": true, "planMode": false, "resume": true,
  "skills": true, "agents": false, "mcp": true, "interrupt": true,
  "fallbackModel": false, "updatedInput": false, "modelSwitch": true
}
```

- `effort: true` — Codex's `ReasoningEffort` (a plain string, model-defined values;
  `turn/start.effort`) is a real analog of our `effort` control. `thinking: false` —
  Codex has no separate on/off/adaptive/budget axis distinct from effort the way
  Claude does; don't invent one.
- `acceptEdits`/`plan` are omitted, not mapped — see the permission-mode table below
  for why forcing them onto Codex's model would be misleading. **Investigated again in
  the 2026-08-30 follow-up** (below) — still not honestly mappable; staying at 2 modes
  is a confirmed decision, not a placeholder.
- `updatedInput: false` — Codex's `ReviewDecision`/approval-decision enums don't carry
  an edited-command payload the way Claude's `updatedInput` does (closest is
  `approved_execpolicy_amendment`, a policy-rule change, not a same-turn command edit);
  don't fake it.
- `skills: true`, `mcp: true` — flipped in the 2026-08-30 follow-up once both were
  confirmed live-feasible (see below); were `false` in the original MVP pass.
- `agents: false` — **confirmed permanent, not deferred.** Investigated in the
  2026-08-30 follow-up: Codex has no equivalent to Claude's static subagent files at
  all (its only adjacent concept is a live multi-agent collaboration/spawning
  feature — a different, much bigger thing). Nothing to revisit here without a
  fundamentally different feature.

## Permission-mode mapping

Codex's model is **sandbox** (`read-only`/`workspace-write`/`danger-full-access`) ×
**approval policy** (`untrusted`/`on-request`/`never`/granular), not a single enum —
it doesn't decompose cleanly onto Claude's four modes. Rather than force a false
4-way equivalence, only the two ends that map honestly are offered:

| Our mode | Codex `sandbox` | Codex `approvalPolicy` | Notes |
|---|---|---|---|
| `default` | `workspace-write` | `on-request` | model decides when to ask; matches Claude's "ask for edits & commands" closely enough — this is the one genuinely shared concept |
| `bypassPermissions` | `danger-full-access` | `never` | mirrors the Claude adapter's existing posture: only ever set when the session itself was created with this mode (same non-switchable-into-it rule as `sidecar/src/session.ts`'s `bypassPermissions` handling) |
| `acceptEdits` | *(not offered)* | — | Codex has no "auto-apply file edits but still ask for shell" split — `workspace-write` sandbox already lets edits through without a prompt in some approval configs, but that's a sandbox property, not a switchable mode, so presenting it as equivalent to Claude's `acceptEdits` would be a lie. **Re-investigated 2026-08-30**: Codex's one approval-policy knob applies uniformly to commands and file changes together (no independent axis), and the granular `AskForApproval` variant's axes (`sandbox_approval`/`rules`/`skill_approval`/`request_permissions`/`mcp_elicitations`) split by *risk category*, not by *edit-vs-command* — confirmed still not mappable, staying omitted. |
| `plan` | *(not offered)* | — | No Codex equivalent. `planMode: false`. Re-investigated 2026-08-30 alongside `acceptEdits` — no read-only/plan concept found anywhere in the protocol; confirmed still omitted. |

**Confirmed live:** `default` (`workspace-write` + `on-request`) does not mean "prompt
for everything" — a harmless shell command and an in-workspace file write both ran
with zero `permission_request`s in Task 1's spike (a), the model/sandbox judged them
safe. Only an action that needs something the sandbox denies by default (network
access, writing outside the workspace) triggered an approval. This is the same shape
as the Claude adapter's own documented "not every Bash call prompts" note — expected,
not a bug — but worth calling out so nobody "fixes" an apparent lack of prompts during
manual testing of a simple in-workspace edit.

## Event/command mapping (adapter protocol v1 ↔ Codex app-server) — confirmed live (Task 1, codex-cli 0.151.0)

| Protocol v1 | Codex app-server | Notes |
|---|---|---|
| launch → `ready` | `initialize` request/response | `ready.provider = "codex"`, `pid` = the spawned process's pid. Confirmed: plain newline-delimited JSON-RPC 2.0 on stdio, no `Content-Length` framing — fits our NDJSON stdio helpers unchanged. |
| first turn → `system_init` | `thread/start` response (`ThreadStartResponse`) | `providerSessionId` = `thread.id`; `model` comes back concrete even when unspecified (observed `gpt-5.6-terra` with no `model` param sent — Codex picks a default and resolves it, same alias-resolution UX as Claude); `cwd` straight across; `tools` empty array (Codex has no static tool-name enumeration analogous to Claude's); `mcpServers` — see the MCP follow-up section below for what's reported now that `mcp: true` |
| `user_message` | `turn/start` (`TurnStartParams.input = [{type:"text", text, text_elements:[]}]`) | queued the same way `sidecar/src/session.ts`'s `AsyncQueue` does today — Codex has its own `thread/queue/*` methods but reusing our existing single-turn-in-flight queueing keeps both adapters behaviorally identical from the backend's point of view |
| `permission_response` | JSON-RPC response to the pending `item/commandExecution/requestApproval` / `item/fileChange/requestApproval` request, `result: { decision }` | **`decision` must be chosen from that specific request's `params.availableDecisions`** (confirmed non-constant across requests — see "Deny semantics differ by item kind" below), not a fixed enum value. Allow → `"accept"`. Deny → prefer `"decline"` if listed, else `"cancel"`; if `availableDecisions` is absent (observed for a `fileChange` request — the field is optional), any non-`accept` string is treated as a decline server-side, but the adapter should still only ever send `"decline"` in that case, not rely on that leniency. |
| `interrupt` | `turn/interrupt` | not exercised live yet — low risk, standard JSON-RPC request |
| `set_permission_mode` | `turn/start`-time `sandboxPolicy`/`approvalPolicy` override (no live "change mid-turn" concept — applies starting the next turn) | acknowledge with `permission_mode_changed` once accepted locally, same as Claude adapter's optimistic pattern |
| `set_model` | carried on the next `turn/start.model` (no separate "change model now" RPC) | acknowledge with `model_changed` |
| `shutdown` | close stdin / `process.kill` on the app-server child | **not yet confirmed** whether a clean turn-boundary exit exists — the spikes always hard-killed the child. Low risk either way (Claude adapter's own shutdown grace period already tolerates a hard kill); confirm during Task 2, don't block on it. |
| Codex `item/agentMessage/delta` | `stream_delta {deltaType:"text"}` | confirmed word/token-fragment-sized deltas |
| Codex `item/started`/`item/completed` (`commandExecution`) | `tool_started`/`tool_result` | map `command` into a Claude-shaped `Bash`-like tool name/input; `aggregatedOutput`/`exitCode` → `tool_result.output`/`isError`. `item/started` fires *before* any approval request, `item/completed` after resolution — fits our existing `tool_started → permission_request → tool_result` sequencing with no changes. `reason` (a model-generated justification string, e.g. `"May I run the exact curl command..."`) has no home in protocol v1's `PermissionRequestEvent` — fold it into `input.reason` so the widget's generic tool-input renderer surfaces it for free, rather than extending the shared schema for one provider. |
| Codex `item/started`/`item/completed` (`fileChange`) | `tool_started`/`tool_result` | same idea, mapped to an `Edit`/`Write`-shaped input from `FileUpdateChange`'s `path`/`diff` |
| approval `ServerRequest` (`item/commandExecution/requestApproval` / `item/fileChange/requestApproval`) | `permission_request` | `requestId` = the JSON-RPC request `id` (Codex's server-request ids are a **separate counter starting at 0**, independent of and overlapping with our own outbound client-request ids — never a collision risk in practice because responses-to-us never carry a `method` field while server-originated requests always do; that's the correlation rule, not numeric disambiguation). `suggestions` = `availableDecisions` verbatim (opaque passthrough, same "forward for future UI" treatment `docs/PROTOCOL.md` already documents for Claude's suggestions) — needed anyway so `permission_response` can be validated against the right set. |
| `turn/completed` (`TurnCompletedNotification`) | `turn_complete` | `stopReason` from `Turn.status` (`completed`/`interrupted`/`failed`) + `Turn.error`; `usage` = the latest `ThreadTokenUsage.total` seen via `thread/tokenUsage/updated` notifications during the turn (usage is **not** on `Turn` itself — the adapter must track the last-seen value, mirroring how `sidecar/src/session.ts` already tracks `currentModel` across `system_init`); `costUsd` computed per Decision 2; `numTurns` — Codex has no multi-step-within-a-turn counter the way Claude's SDK does, so this is `1` per completed turn (documented quirk, not a bug) |
| Codex `error`/`warning`/`configWarning` notifications | `error {fatal:false}` | |
| Other Codex notifications (`mcpServer/startupStatus/updated` for the always-on internal `codex_apps` server, `remoteControl/status/changed`, `account/rateLimits/updated`, `thread/status/changed`, `serverRequest/resolved`, `turn/diff/updated`, `item/started`/`item/completed` for `userMessage`/`reasoning` items, …) | dropped silently (stderr `log()` at most) | confirmed noise volume is real — a single two-tool-call turn emits ~15 notification types beyond the ones mapped above; the adapter must tolerate all of them via an exhaustive `switch` with a silent default, not a `console.error`-per-unknown that would flood `logs/sidecar/<sessionId>.log` |
| app-server process exits unexpectedly | `error {fatal:true}` + `exiting {reason:"fatal"}` | |

### Deny semantics differ by item kind — a real, user-facing behavior gap

Live-tested both deny paths and they are **not equivalent**:

- **File-change deny** (`item/fileChange/requestApproval`, decision `"decline"`): the
  item completes with `status:"declined"`, and **the turn continues** — the model saw
  the denial and produced a normal final message ("The attempt was declined, so the
  file was not created."), `turn/completed.status = "completed"`. This matches
  Claude's `permission_response{behavior:'deny'}` UX exactly.
- **Command-exec deny**: the tested request's `availableDecisions` was `["accept",
  {acceptWithExecpolicyAmendment}, "cancel"]` — **no `"decline"` option was offered**.
  Sending `"cancel"` (the only legal non-accept choice) didn't just skip that one
  command — it **aborted the entire turn**: `item/completed.status:"declined"` but
  `turn/completed.status:"interrupted"`, empty `items`, no closing assistant message,
  and a stderr line (`exec_command failed ... "TurnAborted"`). Whether `"decline"` is
  ever offered for *some* command approvals (as opposed to always only `"cancel"`)
  wasn't fully characterized — plausibly kind/risk-dependent.
- **Net effect**: denying a shell command in a Codex session may currently look to the
  user like an interrupt (the whole turn ends, no model response) rather than a
  graceful single-tool denial. This flows through our mapping correctly (`stopReason:
  "interrupted"` is a real, already-handled value — the widget's existing interrupt UI
  applies), so it isn't a protocol gap needing new plumbing. It **is** a UX difference
  worth surfacing in the DoD's manual test (confirm what the widget shows when a
  command is denied) and possibly a one-line note in the widget when
  `provider === 'codex'`... except capabilities-gating, not provider-name checks, is
  this doc's own rule — so if this needs a UI affordance at all, it should be a
  generic "this denial ended the turn" indicator driven by seeing `turn_complete`
  arrive with no intervening `assistant_message` after a deny, not a Codex-specific
  branch. Revisit once Task 2/3 are wired and this can be felt end-to-end rather than
  reasoned about from four spike runs.

## Follow-up: skills, MCP, agents, permission modes (2026-08-30)

The original MVP shipped with `skills: false`, `agents: false`, `mcp: false`, and 2
permission modes, each flagged "out of scope for this pass" rather than "impossible."
This follow-up re-investigated each one empirically (five more live spikes against
codex-cli 0.151.0) before deciding what to actually build.

### Skills — feasible, cheap, now in scope

**Confirmed live:** Codex reads the exact same `SKILL.md` format Claude does
(`SkillMetadata`'s doc comment literally says "Legacy `short_description` from
`SKILL.md`"). It does **not** auto-discover `.claude/skills/` from `cwd` — a
`skills/list` call with `cwds: [workspace]` before any extra roots were set returned
only Codex's own 6 built-in system skills (`imagegen`, `openai-docs`,
`plugin-creator`, `review-agent`, `skill-creator`, `skill-installer`), missing a test
skill placed at `<workspace>/.claude/skills/hello-skill/SKILL.md`. But calling
**`skills/extraRoots/set`** with `[<worktree>/.claude/skills]` immediately made it
discoverable (`scope: "user"`) on the next `skills/list`. This means our *existing*
skill materialization (`AssetProvisioningService` symlinking `skillSources` into
`<worktree>/.claude/skills/`, already provider-agnostic — it doesn't check
`session.provider()`) is directly reusable: no new materialization path, no backend
changes, just one RPC call from `sidecar-codex` itself using the `--cwd` it already
has. `skills: true`.

### MCP — feasible, needs an adapter-side auth translation

**Confirmed live:** `thread/start`'s generic `config: {[key]: JsonValue}` field
accepts a `mcp_servers` override that spins up a **thread-scoped** MCP server — not
just the host-wide registration `codex mcp add` writes to `~/.codex/config.toml`. A
fake stdio server (`config: {mcp_servers: {spiketest: {command: "cat"}}}`) produced a
real `mcpServer/startupStatus/updated {name:"spiketest", status:"starting"}`
notification for that thread, then `"failed"` after Codex's 30s handshake timeout
(expected — `cat` doesn't speak MCP; the point was proving the spawn attempt, not a
working server). This is the per-session scoping our multi-tenant backend needs —
confirmed it's not a global, cross-session side effect.

**The auth shape differs from Claude's, though.** Inspected what `codex mcp add`
actually writes to `config.toml`:
- stdio: `{command, args, env: {K:V}}` — identical shape to Claude's, direct passthrough.
- HTTP: `{url, bearer_token_env_var: "SOME_ENV_VAR_NAME"}` — Codex reads the bearer
  token from a **named environment variable** on the `codex app-server` process, not
  from an inline header value. Claude's mcp config (and our existing
  `SessionService.linearMcpServer()`) embeds the token directly:
  `{"linear": {"type":"http", "url":"...", "headers": {"Authorization": "Bearer <key>"}}}`.

So wiring this is real adapter work, not a pure pass-through: `sidecar-codex` needs to
parse the same Claude-shaped `mcpConfig` file the backend already writes
(`SessionEntity.mcpConfig()` → `mcpConfigPath(id)`, currently gated to `!codex` in
`SidecarManager.buildArgs`), and for each entry with a `url` + `headers.Authorization:
Bearer <token>`, generate an env var name, set it in the `codex app-server` child's
own spawn environment (`child_process.spawn(..., {env: {...process.env, [varName]:
token}})`), and emit `{url, bearer_token_env_var: varName}` in `thread/start.config`.
Stdio entries (`command` present) pass through unchanged.

**Known gap, accepted per Decision 11 below:** the Linear MCP OAuth fallback (no
explicit `CLAUDE_UI_LINEAR_API_KEY`, relying on the `claude` CLI's own cached OAuth
credential) has no Codex analog — Codex's own OAuth (`mcpServer/oauth/login`) is tied
to a *globally-registered-by-name* server, not an inline per-thread one. A Codex
session only gets the default Linear MCP layering when an explicit API key is
configured; the OAuth-only case silently gets no Linear MCP for Codex sessions
specifically (Claude sessions are unaffected). `mcp: true`.

### Agents — confirmed infeasible, not a "later"

Searched the full non-experimental method list and every `Agent`/`Collab`-named type:
no `agents/list`, no static-definition-file concept anywhere. The only "agent" surface
Codex has is live multi-agent collaboration (`CollabAgentTool`: `spawnAgent`,
`sendInput`, `resumeAgent`, `sendMessage`, …) — spawning and messaging *other live
Codex conversations* at runtime, not loading a markdown persona file the way Claude's
`.claude/agents/*.md` works. There's no artifact to materialize `agentSources` into.
`agents: false` stays permanent for this feature as it exists today; the only way to
"add" agents for Codex would be building an entirely different feature around its
collaboration tools, which is out of scope here.

### Permission modes — re-investigated, still 2 modes

Looked specifically for a way to approximate `acceptEdits` via the granular
`AskForApproval` variant (`{granular: {sandbox_approval, rules, skill_approval,
request_permissions, mcp_elicitations}}`). All five axes are risk-category splits
(sandbox escape / policy-rule change / skill approval / permission request / MCP
elicitation) — none separate "file edit" from "shell command" the way `acceptEdits`
needs, and approval policy is one setting per thread applied uniformly to both
`item/commandExecution/requestApproval` and `item/fileChange/requestApproval`. No
read-only "plan" concept exists anywhere in the protocol either. Decision: **stay at 2
modes** (`default`, `bypassPermissions`) — confirmed, not deferred.

### New decisions (11–13)

11. **MCP auth translation lives in `sidecar-codex`, not the backend.** The backend
    keeps writing the same Claude-shaped mcp config file it always has (no format
    change, no new Java code needed for the token itself — same trust boundary as
    today); `sidecar-codex` does the Codex-specific translation, matching this doc's
    existing "provider-specific behavior stays inside the adapter" principle. The
    OAuth-only gap (above) is accepted, not solved.
12. **Skills need zero backend changes.** `AssetProvisioningService`'s materialization
    is already provider-agnostic; the only new code is `sidecar-codex` calling
    `skills/extraRoots/set` once per process, after `initialize` and before
    `thread/start`/`thread/resume`, pointing at `<cwd>/.claude/skills` if it exists.
13. **Permission-mode chip UI unified across providers.** The create dialog's
    Permissions control changes from a `<select>` to a row of clickable chips (same
    visual language as the running widget's mode chip), rendered from
    `activeCapabilities.permissionModes` — 2 chips for Codex, 4 for Claude, same
    component, no provider-name branching. (Implemented ahead of the rest of this
    follow-up, on direct request — see Task 14.)

## Tasks

### 1. Empirical protocol spike — done (2026-08-30, codex-cli 0.151.0)
Hand-drove `codex app-server` (default/non-experimental) against a scratch git
worktree with four throwaway scripts (not shipped): (a) `on-request` +
`workspace-write`, a harmless `ls` + in-workspace file write — **zero approval
prompts fired**, both actions auto-allowed by the sandbox/model, mirroring Claude's
"not every Bash call prompts" behavior; (b) `untrusted` policy + a curl command
needing network access and a write outside the workspace — one
`item/commandExecution/requestApproval`, accepted, turn completed normally; (c) same
setup, denied — turn **aborted** (see "Deny semantics differ by item kind" below);
(d) a file write outside the workspace, denied via `item/fileChange/requestApproval`
— turn **continued** normally with the model acknowledging the denial. Findings are
folded into the mapping tables and capability/permission sections throughout this
doc; the main surprises were the deny-semantics asymmetry and that the legacy
approval scheme never appeared at all (scope simplification — see Decision 6).
Remaining unconfirmed detail: clean-exit-on-`shutdown` behavior (Task 2 will find out
by trying it, not a blocker).

### 2. `sidecar-codex/` package — done
`package.json`/`tsconfig.json` (same shape as `sidecar/`), `src/index.ts` (CLI arg
parsing: `--cwd`, `--resume`, `--model`, `--permission-mode default|bypassPermissions`,
`--effort`, `--append-system-prompt` — added during implementation, see Task 3 note;
**`--max-turns` was dropped from the plan**, not kept: Codex has no per-turn step-limit
knob to map it to, and any other Claude-only flag is a hard usage error, matching
`sidecar/`'s own `usage()` exit-2 pattern), `src/rpc.ts` (JSON-RPC 2.0 client:
outbound `call()` with its own id counter, inbound server-request dispatch —
disambiguated from responses by presence of a `method` field, not by id range, since
Codex's own server-request ids independently start at 0 and can numerically overlap
ours), `src/session.ts` (orchestration), `src/approvals.ts` (allow/deny → Codex
decision string mapping). `protocol.ts`/`stdio.ts` are **plain copies** of
`sidecar/`'s shared types (Decision 4 resolved: a cross-package import was judged not
worth the build-order coupling for ~240 stable lines — a header comment in each points
at its pair). Builds clean (`npm run build`); smoke-tested standalone against the real
`codex` CLI end to end (ready → turns with tool calls → a real approval round trip →
clean shutdown) before any backend wiring existed.

### 3. Event/command mapping implementation — done
Implemented per the confirmed tables above. One addition beyond the original plan:
Codex's `ThreadStartParams`/`ThreadResumeParams.developerInstructions` maps our
`--append-system-prompt`/`instructions` field — noticed during implementation that
this was cheaply supportable (unlike the other Claude-only fields) and skipping it
would have meant either silently dropping a commonly-set session field or hard-
rejecting it for no real reason, so it's wired through.

### 4. Cost estimation — done
`SettingsService.codexPricing()`/`setCodexPricing(json)` (`codex.pricing` key,
JSON-validated on write — `IllegalArgumentException` → 400 — with a built-in default
rate table). `CodexCostEstimator` (new class, `session` package) computes
`inputPer1M`/`cachedInputPer1M`/`outputPer1M` × token counts from the raw `usage`
node. `SessionService.onSidecarEvent` intercepts `turn_complete` for `provider ==
"codex"` sessions and rewrites the event's `costUsd` **in place** (the parsed
`JsonNode` is mutable — `ObjectNode` — so this happens before the single `record()`
call that journals it) — `SessionService`'s cost-budget guard and `GET /api/usage`
needed zero further changes, confirmed by an end-to-end REST+WS test (see below).

### 5. Backend wiring — done
`application.yaml` got the `codex` provider entry. No Flyway migration needed, as
predicted. `SessionService.create()` additionally gained explicit validation for
`provider: "codex"`, broader than the original sketch: hard-rejects (400) an
unsupported `permissionMode`, an explicit `mcpConfig`, non-empty
`allowedTools`/`disallowedTools`, `thinking`, `maxTurns`, or `fallbackModel`; softly
drops `ecosystemPath`/`contextDirs` with a visible `warning` journal event (these
commonly come from a global Settings default, not explicit per-session intent, so a
hard reject would have been poor UX). `SidecarManager.buildArgs` gates the same set of
Claude-only flags behind `provider != "codex"` as defense-in-depth.

### 6. `GET /api/providers` endpoint — done
`ProviderController`, static `Map<String, Capabilities>` (`claude`/`codex`), sourced
from `AppProperties.providers().keySet()` so it only lists configured providers.

### 7. Frontend — done
- `CreateSessionDialog.tsx`: Provider `<select>` from `GET /api/providers`, defaulting
  to `settings.defaultProvider`. Model becomes a free-text "provider default" input for
  non-Claude providers (no hardcoded Codex model list). Permission-mode options and
  the Thinking control are filtered/hidden by `activeCapabilities`, confirmed live in
  a browser: selecting `codex` reduces Permissions to exactly "ask for edits &
  commands" / "bypass all approval…" and removes the Thinking row entirely.
- `TemplateManager.tsx`: same Provider select + capability-gated Permissions, `model`
  becomes free text for non-Claude providers; `provider` added to `PROMOTED_KEYS`.
- `SettingsDialog.tsx`: "Default provider" select in the Sessions section; a new
  "Codex" section with a Pricing JSON textarea (draft + save-on-blur, matching the
  dialog's existing pattern) that surfaces the backend's 400 validation message inline
  — confirmed live: invalid JSON shows the error and doesn't save; valid JSON saves
  and clears it.

### 8. Docs — done
`CLAUDE.md` (prerequisites row, repo layout, a "Codex provider adapter" section),
`docs/PROTOCOL.md` ("Behavioral notes (Codex adapter)", parallel to the Claude one),
`docs/ARCHITECTURE.md` §5.13 (points here), `docs/plan/README.md` (decision-log row).

### 9. Skills wiring — done
`sidecar-codex/src/session.ts`: after `initialize`, before `thread/start`/
`thread/resume`, checks whether `<cwd>/.claude/skills` exists and if so calls
`skills/extraRoots/set({extraRoots: [that path]})`. `skills: true` in both
`sidecar-codex/src/protocol.ts`'s `CODEX_CAPABILITIES` and
`ProviderController.java`'s Java-side constant. No backend changes needed, as
predicted. **Verified live end-to-end**: a session with `skillSources` pointing at a
custom `pun-generator` skill correctly discovered and autonomously invoked it (the
model read the `SKILL.md` via a shell command entirely on its own — no explicit
mention of the skill was needed in the prompt beyond "tell me a pun" matching the
skill's `description`) and returned the skill's exact scripted text, both standalone
against `sidecar-codex` and through the full backend (create → provision → WS turn).

### 10. MCP wiring — done
- `sidecar-codex`: new `--mcp-config <path>` flag (mirrors Claude's), `src/mcp.ts`
  parses the same tolerant shape `sidecar/src/session.ts`'s `loadMcpServers` does
  (bare server map or `{mcpServers: {...}}`). `command` entries pass through as
  `{command, args, env}`; `url` entries extract `headers.Authorization: Bearer <token>`
  if present, generate an env var name (`CODEX_MCP_TOKEN_<NAME>`), pass it to
  `CodexRpc`'s new `extraEnv` constructor param (merged onto the spawned `codex
  app-server` child's environment — `rpc.ts`), and emit `{url,
  bearer_token_env_var}`; no-auth `url` entries emit `{url}` alone (still untested —
  the ambient-OAuth gap noted above). Assembled map goes into
  `thread/start`/`thread/resume`'s `config.mcp_servers`. `system_init.mcpServers`
  reports configured names with a static `"configuring"` status, as planned. Also
  fixed a real gap found during verification: `item/started`/`item/completed` for
  `mcpToolCall` items weren't mapped to `tool_started`/`tool_result` at all (MCP calls
  were invisible in the transcript, only the model's final text showed) — added,
  named `mcp__<server>__<tool>` to match the Claude Agent SDK's own MCP tool naming
  convention so the widget needs no branching.
- Backend: `SessionService.create()` no longer hard-rejects an explicit `mcpConfig`
  for `provider: "codex"` — it gets the same `withDefaultLinearMcp()` treatment every
  other session does. `SidecarManager.buildArgs` un-gated `--mcp-config` for codex.
  `mcp: true` in both capability constants.
- **Verified live end-to-end** (stdio path — the HTTP+bearer-token path's config
  shape was directly confirmed against `codex mcp add`'s own output, per the earlier
  research, but not live-tested with a real remote server; no Linear API key is
  configured in this dev environment to test that specific path for real): a real
  `@modelcontextprotocol/server-everything` stdio server, both driven directly against
  `sidecar-codex` and through the full backend (an explicit `mcpConfig` override on a
  `codex`-provider session) — the model genuinely called its `echo` tool (confirmed via
  raw protocol inspection: `item/started`/`item/completed` with `type:"mcpToolCall"`,
  `server:"everything"`, real arguments and result, not a hallucinated response) and
  the journal correctly showed `tool_started`/`tool_result` for it after the fix above.
  Also confirmed a single turn can use an MCP tool and a skill together correctly.

### 11. Permission-mode chip UI — done
`CreateSessionDialog.tsx`'s Permissions control changed from a `<select>` to a row of
clickable `.chip` buttons (reusing `MODE_LABEL`/`MODE_CYCLE`, now exported from
`SessionWidget.tsx`), filtered by `activeCapabilities.permissionModes` — same
component and markup regardless of provider, 2 chips for Codex / 4 for Claude. New
`.chip.selected` / `.chip-row` styles in `styles.css`. Confirmed live in a browser:
4 chips for `claude`, exactly 2 for `codex`, click-to-select updates the highlighted
chip correctly. `TemplateManager.tsx`'s Permissions control was deliberately left as a
`<select>` — it has an extra "provider default" (null) option that doesn't fit a chip
row as naturally, and this wasn't asked for there.

### 12. `agentSources` rejection for codex — done (not in the original follow-up plan)
Noticed while implementing Task 10 that `agentSources` was never actually rejected for
`provider: "codex"` in the original MVP pass (only `allowedTools`/`disallowedTools`/
`thinking`/`maxTurns`/`fallbackModel`/`mcpConfig` were) — it would have silently
materialized into `.claude/agents/` and then been silently ignored by `sidecar-codex`
forever, since Codex has no mechanism to read it (confirmed permanently infeasible,
see the follow-up section above). That's exactly the "never silently ignored"
violation this doc's own principle exists to prevent, so `SessionService.create()`
now hard-rejects a non-empty `agentSources` for codex the same way as the other
unsupported fields. `CreateSessionDialog.tsx`'s Agents section (attached assets +
extra-agent input) is now hidden when `!activeCapabilities.agents`, and switching to
a provider without agent support clears any already-selected agent assets so a
template-inherited selection can't cause a surprise 400 at creation time. Verified via
REST: `agentSources` on a `codex` session → 400 `"provider 'codex' does not support
agentSources"`.

### 13. Updated DoD / manual test script additions — done
Added to this doc's DoD (below): a Codex session with an attached skill actually
invokes it; a Codex session with an explicit `mcpConfig` (stdio path) can call an MCP
tool end-to-end; `agentSources` on a Codex session is rejected at creation, not
silently dropped. All three verified live as part of implementing Tasks 9–10 (see
their "Verified live end-to-end" notes above) — not just written down.

### 14. Docs — done
This doc's own capabilities/permission-mode sections (updated above), `docs/
PROTOCOL.md`'s "Behavioral notes (Codex adapter)" section (skills/MCP notes added,
including the `mcpToolCall` mapping gap found and fixed during verification).

## Also out of scope (unchanged, or newly confirmed)

- **Agents for Codex sessions** — confirmed infeasible this pass, not deferred; see
  the follow-up section above. Would need an entirely different feature built around
  Codex's live multi-agent collaboration tools, not a materialization fix.
- **`acceptEdits`/`plan` permission modes** — re-investigated 2026-08-30, confirmed
  still not honestly mappable onto Codex's single global approval-policy knob.
- **Streaming per-server MCP status into the journal** — `mcpServer/startupStatus/
  updated` notifications exist and could drive a live "MCP connecting/connected/failed"
  indicator, but `system_init.mcpServers` reports a static snapshot for this pass
  (Task 10); wiring live updates is additional scope, not required for MCP tool calls
  to actually work.
- **Prompt fan-out (5.11) across providers** — i.e. "try this in both Claude and
  Codex." Not precluded by this design (provider is just another per-session field the
  fan-out create-batch could vary) but not built here — reaffirmed out of scope
  2026-08-30.
- **Reconciling estimated Codex cost against real billing** — Decision 2's table is a
  manually-maintained estimate, not tied to any Codex/OpenAI billing API — reaffirmed
  out of scope 2026-08-30.

## Verification status (2026-08-30)

Confirmed end-to-end via direct REST+WS calls against the built backend (not just unit
compilation): create with `provider: codex` → real `ready`/`system_init` with correct
capabilities → a full turn with a shell command and a file edit, both auto-allowed
(matching the "no prompt for in-workspace actions" finding) → `turn_complete` with a
non-zero, backend-estimated `costUsd` (`0.033364`) → that same figure showing up in
`costToDate` and `GET /api/usage` unchanged → close. Creation-time rejection of `plan`
mode and an explicit `mcpConfig` confirmed via direct REST calls (400 + clear
`detail`). Confirmed live in a real browser (Playwright): the create dialog's Provider
select, the capability-driven narrowing of Permissions/Model/Thinking when `codex` is
selected, and the Settings dialog's Default-provider select + Codex pricing editor
(including its invalid-JSON error path and valid-JSON save path).

**Not yet clicked through in the actual `SessionWidget`** (only validated at the
REST/WS protocol level, via a driver script rather than the dashboard UI): the
mid-session permission-mode toggle chip, the interrupt button, resume-from-closed via
the UI, and the widget's own cost-chip rendering. These should work — they're the same
widget code path a Claude session already exercises, gated only by
`SessionEntity.capabilities` — but "should work" isn't "confirmed clicked," so treat
those specific DoD lines below as design-verified, not UI-verified, until someone
drives them by hand once.

**2026-08-30 follow-up (skills, MCP, agentSources rejection, permission-mode chips)**:
confirmed live at every layer, not just this doc. Skills: a custom skill was
autonomously discovered and invoked (exact scripted output came back), both standalone
against `sidecar-codex` and through the full backend. MCP: a real
`@modelcontextprotocol/server-everything` stdio server was genuinely called (verified
via raw protocol inspection that it wasn't a hallucinated response —
`item/started`/`item/completed` with `type:"mcpToolCall"` and real arguments/result),
both standalone and through the full backend with an explicit `mcpConfig` override; a
single turn using both a skill and an MCP tool together was confirmed. `agentSources`
rejection confirmed via direct REST (400). The permission-mode chip UI was clicked
through in a real browser: 4 chips for `claude`, exactly 2 for `codex`, click-to-select
updates correctly. The HTTP+bearer-token MCP auth path remains **un-tested with a real
remote server** (no Linear API key in this dev environment) — its config shape was
directly confirmed against `codex mcp add`'s own output, which is strong but not the
same as a live call.

## Definition of Done

- A `provider: codex` session created from the dashboard runs a prompt end-to-end：
  assistant text streams into the widget, a shell command triggers a real
  `permission_request` → approve/deny round trip through the same UI Claude sessions
  use, a file edit does the same, and the turn completes with a token-derived
  `costUsd` shown in the widget's cost chip.
- `set_permission_mode` (`default` ↔ `bypassPermissions`) and `set_model` work
  mid-session from the widget.
- Interrupt mid-turn works and leaves the session usable for the next turn.
- Closing/resuming a Codex session round-trips via `providerSessionId` (`thread/
  resume`).
- Creating a Codex session with `plan` permission mode or a non-empty `agentSources`
  is rejected at creation time with a clear error, not silently downgraded. An
  explicit `mcpConfig` is **not** rejected — it works the same as a Claude session's.
- The create dialog and template editor, with `codex` selected, don't render "plan
  mode" or "acceptEdits" as permission-mode chips, and hide the Agents section —
  driven by `GET /api/providers`, not a `provider === 'codex'` check anywhere in the
  frontend.
- A Claude session created before/after/alongside a Codex session is unaffected —
  same widget behavior, same capabilities, run concurrently without interference.
- Settings dialog: changing "Codex pricing" changes `costUsd` on the *next* completed
  Codex turn without a backend restart; changing "Default provider" changes what a
  fresh create-dialog open pre-selects.
- A skill attached to a Codex session (`skillSources`) is autonomously discovered and
  invoked by the model, same UX as a Claude session, with no explicit mention needed
  beyond matching the skill's own description.
- An MCP server attached to a Codex session (`mcpConfig`) can be called by the model
  as a real tool, with `tool_started`/`tool_result` visible in the transcript
  (`mcp__<server>__<tool>` naming) exactly like a Claude session's MCP tool calls.

## Manual test script

1. `codex login` on the backend host (once, interactively) if not already done;
   confirm with `codex doctor`.
2. Add a `codex` entry under `claude-ui.providers` in `application.yaml` (or
   `application-local.yaml`), pointing at the built `sidecar-codex/dist/index.js`.
3. Build `sidecar-codex` (`cd sidecar-codex && npm install && npm run build`) and the
   backend (`./mvnw package -DskipTests -Dskip.installnodenpm -Dskip.npm`, rebuilding
   `frontend/dist` first if the create dialog changed).
4. Start the backend per CLAUDE.md's "Run the project", open the dashboard.
5. Settings → confirm "Default provider" and "Codex pricing" sections render; leave
   pricing at its built-in defaults.
6. Create a session, select provider `codex`, base branch `main`, a prompt asking it
   to run `ls` and then create a small file **inside the worktree**. Confirm: the
   permission-mode/thinking selects reduce to what `GET /api/providers` says Codex
   supports. **Expect zero approval prompts for this one** (confirmed in Task 1 — an
   in-workspace `ls` + file write is auto-allowed under `default`); this step is
   really just confirming plain turns complete cleanly, not exercising approval.
7. Send a second prompt that needs something outside the sandbox by default — e.g.
   "curl example.com and save the output outside this directory, at /tmp/…" — to
   actually exercise `permission_request`. Approve the command approval that appears;
   confirm the turn completes with a closing assistant message and a non-zero,
   plausible `costUsd` in the cost chip.
8. Repeat step 7's prompt in a fresh Codex session but **deny** the approval this
   time. Per Task 1's findings this may end the turn with no closing assistant
   message (`stopReason: interrupted`) rather than a graceful per-tool denial —
   confirm the widget shows *something* sensible either way (not a stuck spinner or
   an unhandled error), since this is a known real behavior difference from Claude,
   not a bug to chase.
9. Toggle permission mode to `bypassPermissions` mid-session, send a prompt
   that runs a command needing network/outside-workspace access — confirm zero
   approval prompts this time.
10. Send another prompt, interrupt it mid-flight from the widget, confirm the session
    stays usable and a follow-up prompt completes normally.
11. Close the session, reopen/resume it, confirm the transcript and
    `providerSessionId` round-trip (Codex picks the conversation back up).
12. Edit "Codex pricing" in Settings (e.g. double the output rate), run one more
    prompt, confirm the new `costUsd` reflects the change.
13. Open a second, Claude-provider session concurrently; confirm both run
    side by side with no cross-talk (each in `logs/sidecar/<sessionId>.log`).
14. Create a Codex session with a skill attached (Advanced options → Skills → Add
    skills…, or an `extraSkill` path to any `SKILL.md`-containing directory). Send a
    prompt matching the skill's description without naming it explicitly — confirm the
    model discovers and uses it (visible as a `tool_started`/`tool_result` reading the
    `SKILL.md`, then a response reflecting the skill's content).
15. Create a Codex session with an explicit `mcpConfig` (a real stdio MCP server is
    easiest to test with no extra setup — e.g. `npx -y
    @modelcontextprotocol/server-everything` via the raw-overrides JSON field: `{"mcp
    Config": {"everything": {"command": "npx", "args": ["-y",
    "@modelcontextprotocol/server-everything"]}}}`). Ask it to call one of the
    server's tools — confirm a `tool_started`/`tool_result` named `mcp__everything__
    <tool>` appears with a real result, not just narrated text.
16. Try to create a Codex session with a non-empty `agentSources` (raw overrides:
    `{"agentSources": [{"type":"file","ref":"/any/path.md"}]}`) — confirm it's
    rejected with a clear 400, and that the Agents section doesn't even render in the
    create dialog once `codex` is selected as the provider.
