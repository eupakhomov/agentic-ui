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

## Capabilities announcement (predicted — confirm in Task 1)

```json
{
  "permissionModes": ["default", "bypassPermissions"],
  "thinking": false, "effort": true, "planMode": false, "resume": true,
  "skills": false, "agents": false, "mcp": false, "interrupt": true,
  "fallbackModel": false, "updatedInput": false, "modelSwitch": true
}
```

- `effort: true` — Codex's `ReasoningEffort` (a plain string, model-defined values;
  `turn/start.effort`) is a real analog of our `effort` control. `thinking: false` —
  Codex has no separate on/off/adaptive/budget axis distinct from effort the way
  Claude does; don't invent one.
- `acceptEdits`/`plan` are omitted, not mapped — see the permission-mode table below
  for why forcing them onto Codex's model would be misleading.
- `updatedInput: false` — Codex's `ReviewDecision`/approval-decision enums don't carry
  an edited-command payload the way Claude's `updatedInput` does (closest is
  `approved_execpolicy_amendment`, a policy-rule change, not a same-turn command edit);
  don't fake it.
- `mcp: false`, `skills: false`, `agents: false` for this pass per Decision 1, even
  though the protocol has real hooks for the first two — revisit once the MVP is
  proven. The UI must already do the right thing here with zero changes, since this is
  exactly the capabilities-gating the create dialog needs to exercise for Decision 3's
  provider select (Task 6).

## Permission-mode mapping

Codex's model is **sandbox** (`read-only`/`workspace-write`/`danger-full-access`) ×
**approval policy** (`untrusted`/`on-request`/`never`/granular), not a single enum —
it doesn't decompose cleanly onto Claude's four modes. Rather than force a false
4-way equivalence, only the two ends that map honestly are offered:

| Our mode | Codex `sandbox` | Codex `approvalPolicy` | Notes |
|---|---|---|---|
| `default` | `workspace-write` | `on-request` | model decides when to ask; matches Claude's "ask for edits & commands" closely enough — this is the one genuinely shared concept |
| `bypassPermissions` | `danger-full-access` | `never` | mirrors the Claude adapter's existing posture: only ever set when the session itself was created with this mode (same non-switchable-into-it rule as `sidecar/src/session.ts`'s `bypassPermissions` handling) |
| `acceptEdits` | *(not offered)* | — | Codex has no "auto-apply file edits but still ask for shell" split — `workspace-write` sandbox already lets edits through without a prompt in some approval configs, but that's a sandbox property, not a switchable mode, so presenting it as equivalent to Claude's `acceptEdits` would be a lie. Capabilities list omits it; if this turns out to feel like a real gap once the MVP is in use, revisit as a follow-up rather than fake it now. |
| `plan` | *(not offered)* | — | No Codex equivalent. `planMode: false`. |

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
| first turn → `system_init` | `thread/start` response (`ThreadStartResponse`) | `providerSessionId` = `thread.id`; `model` comes back concrete even when unspecified (observed `gpt-5.6-terra` with no `model` param sent — Codex picks a default and resolves it, same alias-resolution UX as Claude); `cwd` straight across; `tools`/`mcpServers` empty arrays (mcp out of scope) |
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

## Out of scope (this pass)

- Skills/agents for Codex sessions (`skills: false`, `agents: false`) — Codex's
  `skills/list` surface exists but its discovery convention/format hasn't been
  checked against our `.claude/skills`-style materialization; a follow-up once the
  MVP is proven.
- MCP config passthrough (`mcp: false`) — means Codex sessions don't get the default
  Linear MCP layering (`SessionService.withDefaultLinearMcp()`) other sessions get.
  Acceptable capability gap for this pass; the create dialog must not silently drop an
  explicit `mcpConfig` the user set for a Codex-provider session — it should surface as
  a validation error at creation time instead of being ignored.
- `acceptEdits`/`plan` permission modes (see mapping table above).
- Prompt fan-out (5.11) across providers, i.e. "try this in both Claude and Codex" —
  not precluded by this design (provider is just another per-session field the fan-out
  create-batch could vary) but not built here.
- Reconciling estimated Codex cost against real billing — Decision 2's table is a
  manually-maintained estimate, not tied to any Codex/OpenAI billing API.

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
- Creating a Codex session with `plan` permission mode or an explicit `mcpConfig` is
  rejected at creation time with a clear error, not silently downgraded.
- The create dialog and template editor, with `codex` selected, don't render "plan
  mode" or "acceptEdits" as options — driven by `GET /api/providers`, not a
  `provider === 'codex'` check anywhere in the frontend.
- A Claude session created before/after/alongside a Codex session is unaffected —
  same widget behavior, same capabilities, run concurrently without interference.
- Settings dialog: changing "Codex pricing" changes `costUsd` on the *next* completed
  Codex turn without a backend restart; changing "Default provider" changes what a
  fresh create-dialog open pre-selects.

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
