# Phase 12 — Linear ticket cache, Serena MCP, context-usage indicator

Status: **Tracks A and C landed (2026-09-13); B planned** — three independent tracks, each
shippable on its own; the order below is by value-per-effort (A is a half-day, B a day, C the
biggest). Decisions 1–4 were confirmed with the user before this doc was written; the rest are
proposals to confirm before the corresponding step starts. Verified against the code as of
Phase 11 (commit `07b4de2`); every edit site named below was checked to exist as described.

## Background

### A. Linear ticket picking is slow because every step is an LLM turn

`TicketImportService` (`src/main/java/de/pamir/claude/ui/integration/TicketImportService.java`)
implements both `POST /api/tickets/recent` (`listMyTickets`) and `POST /api/tickets/import`
(`importTicket`) as **one Haiku turn each on the singleton system session**, with the Linear
MCP server doing the actual fetching inside that turn (`SystemTurnClient.json(prompt,
INTERACTIVE, 45 s)`). Nothing is cached anywhere: each "Browse" in the create/quick dialog is a
fresh 10–45 s turn producing the same 20 rows, and each pick is another turn that re-reads the
ticket + its comments over MCP and then writes `branchName`/`prompt`/`recommendedModel`.

Where the time goes, measured shape (not a benchmark): the system session may be PARKED
(30 min idle default) or not yet created → `getOrCreateSystemSession()` revives it inside the
turn's lock (`REVIVAL_BUDGET` 30 s); then the turn itself ≈ MCP tool round trips + Haiku
generation, ~8–15 s warm. The dialog's "can take up to 45s on the first call (spinning up the
system session)" copy is honest about the first cost and silent about the second.

The only credential the backend holds for Linear is `CLAUDE_UI_LINEAR_API_KEY` — and the
real deployment uses the **OAuth toggle** instead (the `claude` CLI's own cached credential
for `mcp.linear.app`, no key on the backend). So an LLM-free direct path (GraphQL, or a
backend MCP client against `mcp.linear.app`) would need a backend-owned OAuth flow; **decided
out of scope for this phase** (decision 1). Everything in track A is therefore about not
*repeating* work and moving fixed costs off the critical path.

### B. Serena is a stdio MCP server that needs per-session arguments

Serena (`oraios/serena`, local checkout at `/mnt/d/projects/serena`; macOS: wherever the
user clones it) runs as a stdio MCP server from a checkout via
`uv run --directory <serena-root> serena start-mcp-server …`. Confirmed from its docs/CLI
(`docs/02-usage/020_running.md`, `030_clients.md`, `src/serena/cli.py`):

- `--project <path>` activates a project (walk-up alternative `--project-from-cwd` nearest
  `.serena/project.yml` or `.git` — a nested git worktree resolves to itself, but an explicit
  path is what we want since the backend knows the session's cwd).
- `--context claude-code` / `--context codex` — built-in contexts tuned per client
  (`src/serena/resources/config/contexts/{claude-code,codex}.yml`).
- `--open-web-dashboard false` — the dashboard is on by default and **opens a browser** on
  the host running the server; several sessions = several dashboards, so keep the dashboard
  enabled (it's the log UI) but never auto-open it.
- It writes `.serena/` into the project dir and creates `.serena/.gitignore` itself
  (`src/serena/project.py:64-69`), so worktrees stay clean for the dirty-check/commit flow.
- Language servers are downloaded on first use per language (Java → JDT LS, minutes on a cold
  cache) — the client must be told to wait (`MCP_TIMEOUT` for Claude Code).
- Their Claude Code guide says the agent otherwise "will often fail to make proper use of
  Serena's tools" and ships a counter-bias system prompt:
  `serena prompts print-cc-system-prompt-override` (`cli.py:1503-1508`).
- Serena's hooks (`serena-hooks remind/reset/activate/cleanup`) are alpha and would require
  writing a `.claude/settings.json` into every worktree — out of scope.

Our side: default MCP servers are layered at *creation* time in `SessionConfigFactory`
(`withDefaultLinearMcp` / `withDefaultMemoryMcp` → `withDefaultServer`, the session's own key
always wins, lines 293–350), both sidecars pass a stdio `{command, args, env}` entry through
unchanged (`sidecar-codex/src/mcp.ts:41` confirms Codex), `SidecarManager.spawn` already
takes an `extraSystemPrompt` (`SessionConfigFactory.extraSystemPrompt`, `session.ts:254`
appends it to the `claude_code` preset), and the session's cwd is `SessionEntity.cwdPath()`
(monorepo-aware since phase 11). **`uv` is not on PATH on the WSL dev box** — a prerequisite,
not code.

### C. Nothing shows how full a session's context is

`turn_complete.usage` (both sidecars) is journaled and used for Codex cost estimation, but
it's the turn's *summed* usage — it doesn't say how many tokens the next request will carry.
The signals that do exist:

- Claude Agent SDK 0.3.241 (`sidecar/node_modules/@anthropic-ai/claude-agent-sdk/sdk.d.ts`):
  `query.getContextUsage()` → `{ totalTokens, maxTokens, rawMaxTokens, percentage,
  autoCompactThreshold?, isAutoCompactEnabled, … per-category breakdown }` (line 2547 /
  3370+), and a `system` message `subtype: 'compact_boundary'` with
  `compact_metadata: { trigger: 'manual' | 'auto', pre_tokens }` (line 3161+) when the CLI
  compacts. `/compact` typed as a user prompt is a *local slash command* the CLI executes
  (line 4282 "Output from a local slash command"; 8033 "the loop was bypassed (local slash
  command)") — Step C0 confirms it works through `query()` the way the sidecar sends prompts.
- Codex app-server: `thread/tokenUsage/updated` carries `tokenUsage.last` (per-request
  usage, i.e. the current context) and `modelContextWindow` — `sidecar-codex/src/session.ts:253`
  currently keeps only `total`. Compaction: `thread/compact/start` — to be confirmed live in
  Step C0 (name/shape from the app-server's generated schema; if absent, the capability is
  simply `false`).

There is no widget-level chip, no persisted value, and no nudge. The user's actual decision
loop today is "cost keeps rising, is it time to `/compact` or start over?" with no number.

## Target behavior

**A. Ticket picker**
- Opening the create or quick dialog with Linear enabled *warms the system session* in the
  background and *prefetches* the assigned-tickets list; by the time the user clicks Browse
  the picker is usually already populated.
- The list is cached backend-side for **15 minutes**; the picker shows "as of N min ago" and
  a `Refresh` icon button that forces a fresh fetch (button `.pulse`s while in flight, list
  stays visible until replaced).
- A ticket's import result (`branchName`/`prompt`/`recommendedModel`/`ticketRef`) is cached
  per ref for 15 minutes, so picking the same ticket again (second dialog, quick-session
  after full, a cancelled create) is instant.
- Copy in both dialogs says what's happening ("fetching from Linear…" vs. "warming up…") and
  the cold-start warning only appears when it's actually cold.

**B. Serena**
- Settings → new "MCP servers" section: **Serena root** (path to the checkout; empty = Serena
  unavailable) and **uv path** (default `uv`, for hosts where it isn't on the backend's
  PATH). Save validates: root exists, `pyproject.toml` there names `serena`, `uv --version`
  runs; failures are 400s with the reason, not silent.
- Create dialog + template: a **"Serena (symbolic code tools)" checkbox**, default **off**,
  only rendered when the root is configured. Session detail/summary carry `serenaEnabled`;
  the widget shows a `serena` chip.
- A Serena-enabled session gets a `serena` stdio MCP entry layered into its `mcpConfig`
  (unless it already declares one — same rule as Linear/memory), pointing at its own
  `cwdPath` (monorepo: the package folder), with the provider-matched `--context`, and for
  **Claude** sessions Serena's system-prompt override appended via the existing
  `extraSystemPrompt` seam. `MCP_TIMEOUT` is set on the sidecar env so a cold language server
  doesn't count as a dead MCP server.
- Codex sessions get the same entry with `--context codex` (no prompt override — that text is
  Claude-Code-specific).

**C. Context indicator + compact suggestion**
- Every session carries `contextTokens` / `contextWindow` (latest known), refreshed after each
  completed turn and after a compaction; shown on the widget as a **`ctx 38%`** chip beside
  the cost chip — `--muted` normally, `--amber` at ≥ the warn threshold (Settings, default
  70 %), `--red` at ≥ 90 %. Hover: exact tokens / window, and "auto-compact at N%" when the
  provider reports it.
- Crossing the warn threshold once per session (re-armed after a compaction) raises a
  dismissable **suggestion card** in the transcript ("Context is 72% full — compact now to
  keep costs down") with one action: **Compact** (in place, provider-side). Dismiss just
  hides it until the next crossing. No hand-off action (decision 4 — 7.3's continuation flow
  remains available from the close dialog as today).
- Compacting journals a `context_compacted` event (`preTokens`, `postTokens`, `trigger`) so
  the transcript shows where the boundary is, and the chip drops accordingly. Codex sessions
  get the chip; the Compact button only if Step C0 confirms `thread/compact` (otherwise the
  card is indicator-only for that provider, via a capability flag — no provider name in UI
  code).

## Decisions (1–4 confirmed 2026-09-13; 5–10 to confirm before the step that needs them)

1. **Linear: cache + pre-warm + prefetch only, no LLM-free data path.** A direct GraphQL path
   only helps API-key installs (not the real one), and a backend-owned OAuth flow against
   `mcp.linear.app` is a separate feature (dynamic client registration, token storage,
   refresh) — noted under "Out of scope" as the next step if 15-min caching isn't enough.
   Reading the `claude` CLI's cached OAuth token (`~/.claude/.credentials.json`, macOS:
   Keychain) was rejected as relying on an undocumented store.
2. **Serena opt-in is per session, default off**, via `serenaEnabled` on session + template
   (create dialog checkbox). Not always-on: each enabled session runs its own Python process
   plus a language server, and 4 parallel Java sessions = 4 JDT LS instances.
3. **Claude sessions get Serena's system-prompt override** appended (not replacing) when
   `serenaEnabled`, captured once per backend start by running `uv run --directory <root>
   serena prompts print-cc-system-prompt-override` (cached in `SerenaService`; re-captured
   when the root setting changes). Codex gets no override.
4. **Compact suggestion offers Compact only** (no hand-off button). The card is a nudge, not
   a workflow — one primary action keeps it readable at a glance.
5. **Cache lives in `TicketImportService`, in memory, keyed on auth mode** (proposal): a
   restart clears it (fine — the list is cheap to refetch relative to everything else, and
   persisting LLM output that's stale by definition buys nothing). Invalidate on
   `linearOAuthEnabled` flips and when `enabled()` turns false.
6. **Refresh is a query flag, not a new endpoint** (proposal): `POST /api/tickets/recent?refresh=true`.
   Response becomes `{ tickets, fetchedAt, cached }` — `rest.ts`'s `listRecentTickets` return
   type changes accordingly (both dialogs go through `useTicketImport`, one edit site).
7. **Serena project = the session's `cwdPath`** (proposal): for a monorepo session that's the
   package folder, matching what the agent sees as "the project"; Serena's own walk-up would
   otherwise pick the worktree root. Serena's `.serena/` therefore lands under the package.
8. **`serenaEnabled` on a Codex session is allowed** (proposal): Serena supports Codex
   natively (`--context codex`), and phase 5.13's MCP follow-up confirmed stdio passthrough.
   Nothing to reject in `SessionConfigFactory`'s capability checks.
9. **Context usage is provider-neutral in the protocol** (proposal): a new NDJSON event
   `context_usage { tokens, window, autoCompactAt? }` emitted by the sidecar after every
   `turn_complete` (and after a compaction), plus `context_compacted { preTokens, postTokens,
   trigger }`; a new command `compact`; capability `compact: boolean`. Claude implements
   `context_usage` from `getContextUsage()`, Codex from `tokenUsage.last` +
   `modelContextWindow`. Both `protocol.ts` copies change → `scripts/check-protocol-sync.mjs`
   keeps them honest. Persisted on `session` (`context_tokens`, `context_window`, V15) so
   the chip is right after a reload without waiting for a turn.
10. **Warn threshold is a persisted setting** (`session.context-warn-percent`, default 70,
    floor 30, ceiling 95) in Settings → "Sessions", not per session (proposal): one number,
    same meaning everywhere; per-session tuning would be the first per-session numeric knob
    with no obvious reason to differ.

## Steps

### Step A1 — Backend cache + refresh flag

Edit sites: `integration/TicketImportService.java` (`listMyTickets`, `importTicket`),
`web/TicketImportController.java` (`recentTickets`), a new `integration/TicketCache` (or a
private static class — it's two `AtomicReference`s and a map; don't over-build it).

- `listMyTickets(boolean refresh)` returns `TicketList(List<TicketSummary> tickets, Instant
  fetchedAt, boolean cached)`; serves from cache when `!refresh && age < 15 min`. Concurrent
  first callers share one in-flight fetch (a `CompletableFuture` in the reference, not a
  second system turn — the system-session lock would serialize them anyway, but the second
  would still *run*).
- `importTicket(ref)` caches `TicketImportResult` by upper-cased canonical ref for 15 min
  (only successful results; a URL and its identifier map to the same entry via the result's
  `ticketRef`).
- Invalidation: `SettingsService` change of `linearOAuthEnabled` → `clear()`; `enabled()`
  false → `clear()` and the existing `IllegalStateException`.
- Tests: `TicketImportServiceTest` (exists — extend): cache hit within TTL, miss after TTL
  (inject a `Clock`), `refresh=true` bypasses, single-flight under two concurrent callers,
  import-result cache keyed case-insensitively.

DoD: two `POST /api/tickets/recent` within 15 min produce **one** system turn (log shows one
`system task` line); `?refresh=true` produces another; `fetchedAt` is stable across cache
hits.

### Step A2 — Pre-warm + prefetch on dialog open

Edit sites: `session/SystemSessionService.java` (new `warmUp()`: acquire nothing, submit
`getOrCreateSystemSession()` on `turnExecutor` under `tryAcquire` of the lock — never blocks a
caller, never double-spawns), `web/TicketImportController.java` (`GET /api/tickets/import/enabled`
triggers `warmUp()` when enabled — the dialogs already call it on open; plus a follow-up
`listMyTickets(false)` on the executor so the *list* is warm too), frontend
`hooks/useTicketImport.ts` (`browseRecentTickets` shows cached rows instantly).

- Guard: pre-warm is a no-op when a system turn is already running or the session is
  `RUNNING`; it must not steal the lock from an interactive turn (`tryAcquire`, not
  `acquire`).
- The prefetch must not leave a *failed* fetch cached — failures aren't stored (A1).
- Session-limit interaction: the system session counts against `CLAUDE_UI_MAX_SESSIONS` as
  before; warming early doesn't change that, only *when* it's spent.

DoD: open the create dialog cold → within ~10 s the log shows the system session spawning
and a `system task` for the list; click Browse → rows appear with no spinner (or a spinner
that resolves without a second turn if the prefetch is still running — the single-flight
future from A1 is what makes that true).

### Step A3 — Picker UI: age + Refresh, honest copy

Edit sites: `components/TicketPickerDialog.tsx`, `hooks/useTicketImport.ts`,
`components/CreateSessionDialog.tsx` / `QuickSessionDialog.tsx` (the two "up to 45s" copy
sites), `icons.ts` (reuse `Refresh` — it exists), `api/rest.ts`, `protocol.ts`.

- Header row: "Your tickets · as of 3 min ago" + `button.icon-btn` `Refresh`
  (`title="Refresh (r)"`), `.pulse` + disabled while a refresh is in flight; the current
  rows stay visible until replaced (no flash to empty).
- Busy copy: "fetching from Linear…" once warm; the 45 s cold-start warning only when the
  backend says the system session is actually cold (`GET /api/tickets/import/enabled`
  gains `warm: boolean` from `SystemSessionService`, and the dialog picks the copy).
- `vitest` for `useTicketImport`: refresh keeps old rows during flight; error on refresh
  keeps old rows and shows the error line.

DoD: manual script items 1–4 below.

### Step B0 — Prerequisite + spike (30 min, no code)

- Install `uv` on the dev box (`curl -LsSf https://astral.sh/uv/install.sh | sh`) and confirm
  `uv run --directory /mnt/d/projects/serena serena start-mcp-server --help` works.
- Run a Serena-enabled session by hand: put a `serena` stdio entry into a template's
  `mcpConfig` (the raw-JSON field in `TemplateManager`) with `--project <some worktree>
  --context claude-code --open-web-dashboard false`, create a session from it, ask the agent
  to `find_symbol` something. Note: startup time on this repo (Java → JDT LS download),
  whether the CLI's default MCP timeout trips, whether `.serena/` shows up in the Git panel
  (it must not — its own `.gitignore`), and the port the dashboard picked.
- Same with a Codex session (`--context codex`).

DoD: a short "confirmed live" note at the top of Step B1 with the measured cold-start time
and the `MCP_TIMEOUT` value to ship.

### Step B1 — Settings + `SerenaService`

Edit sites: `config/Settings.java` / `SettingsPatch.java` / `SettingsService.java`
(`mcp.serena-root`, `mcp.uv-path`), `web/SettingsController.java` (validation → 400),
new `integration/SerenaService.java` (`configured()`, `root()`, `uvCommand()`,
`ccSystemPromptOverride()` cached per root value), `components/SettingsDialog.tsx` (new
"MCP servers" section, two text inputs, inline validation error).

- Validation on save (backend): `Files.isDirectory(root)`, `root/pyproject.toml` contains
  `name = "serena"`, `<uv> --version` exits 0 (60 s git-command-style timeout, same helper
  as `ProcessRunner`/`GitWorktreeService` uses — check what exists before adding one).
- `ccSystemPromptOverride()` runs `<uv> run --directory <root> serena prompts
  print-cc-system-prompt-override` once, memoized; on failure logs a WARN and returns null
  (the session still gets the MCP entry — the prompt is an optimization, not a dependency).
- Tests: `SettingsServiceTest` round-trip of the two keys; `SerenaServiceTest` with a temp
  dir for the pyproject check (the `uv` probe is mocked behind a small `Runner` interface —
  see how `GitWorktreeService` tests fake git).

DoD: Settings shows the section; a wrong path is rejected with a readable message; a right
one saves; `GET /api/settings` round-trips both values.

### Step B2 — `serenaEnabled` on session + template, MCP layering, prompt, env

Edit sites: `db/migration/V15__serena_context_usage.sql` (`session.serena_enabled boolean not
null default false` — shared with C's columns, one migration for the phase),
`session/SessionEntity.java` (+builder, +`SessionRepository` column mapping — follow
`reflectionEnabled`'s trail exactly: entity, repository insert/select, `copyTunables`),
`session/SessionConfigFactory.java` (`withDefaultSerenaMcp` after the memory layer;
`extraSystemPrompt` gains a third block; `serenaEnabled` read from config like
`reflectionEnabled`), `process/SidecarManager.java` (env `MCP_TIMEOUT` when the session is
Serena-enabled — value from B0), `web/SessionController` DTOs, `session/SessionView`/
summary (so the widget can show the chip).

- `serenaMcpServer(SessionEntity s)`:
  ```json
  {"serena": {"command": "<uv>", "args": ["run", "--directory", "<root>", "serena",
    "start-mcp-server", "--context", "<claude-code|codex>", "--project", "<cwdPath>",
    "--open-web-dashboard", "false"]}}
  ```
  `null` when `!s.serenaEnabled() || !serena.configured()`. Context name comes from the
  provider's own capabilities file (`sidecar/capabilities.json` / `sidecar-codex/
  capabilities.json`, read by `ProviderCatalog`): a new optional `serenaContext` string —
  no provider name in Java, same seam phase 10 R1 established.
- A session created with `serenaEnabled` while the root is unset → 400 "Serena is not
  configured (Settings → MCP servers)"; a template with it set still loads, the dialog
  shows the checkbox disabled with the same hint.
- Tests: `SessionConfigFactoryTest` (exists) — layered entry present iff enabled+configured,
  session's own `serena` key wins, Codex context, `extraSystemPrompt` contains the override
  for Claude and not for Codex.

DoD: create a Serena session → the "sidecar pid … spawned" log line's `--mcp-config` file
contains the `serena` entry with the session's cwd; the agent's tool list includes
`mcp__serena__*`; a non-Serena session's mcpConfig is byte-identical to before this phase.

### Step B3 — Frontend: checkbox, template field, chip

Edit sites: `components/CreateSessionDialog.tsx` (checkbox next to the reflection toggle,
hidden when `settings.mcpSerenaRoot` is empty), `components/TemplateManager.tsx`
(`PROMOTED_KEYS` + a checkbox — not the raw JSON), `components/QuickSessionDialog.tsx`
(inherits from template/default; no control — quick means quick), `components/SessionWidget.tsx`
(`.chip` "serena", `title="Serena MCP enabled"`), `components/DuplicateDialog.tsx` (copies the
flag with the other tunables), `protocol.ts`, `api/rest.ts`.

DoD: checkbox round-trips through create → detail → duplicate; chip visible only on
Serena sessions in both themes.

### Step C0 — Spike: compaction through both sidecars (half a day)

- Claude: in `npm run drive`, send `{"type":"user_message","text":"/compact"}` — confirm
  the CLI runs the local command (a `compact_boundary` system message follows) and that
  `getContextUsage()` before/after reflects it. Confirm `getContextUsage()` is callable
  between turns (it's a control request on the live `query`).
- Codex: `codex app-server` — confirm the compaction request name and its notification
  (`thread/compact/start` per the app-server schema; run `codex app-server
  generate-json-schema` or grep the installed CLI). Confirm `thread/tokenUsage/updated`'s
  `last` + `modelContextWindow` fields on this Codex version.
- Outcome written into the top of Step C1: exact SDK calls, whether Codex compaction is in
  (capability `compact: true/false` per provider).

### Step C0 outcome (confirmed live, 2026-09-13)

**Claude** (`@anthropic-ai/claude-agent-sdk` 0.3.241, live `query()` run, not just `npm run
drive`): `query.getContextUsage()` is callable between turns (called it right after a
`result` message) and returns the documented shape — `totalTokens`, `maxTokens`,
`rawMaxTokens`, `percentage`, `isAutoCompactEnabled`, `autoCompactThreshold`, `model`, etc.
Sending `{"type":"user","message":{"content":[{"type":"text","text":"/compact"}]}}` (exactly
what `user_message` already produces — no new SDK entry point needed) *does* run the local
`/compact` command, but the live message sequence is richer than the doc assumed:
1. `system {subtype:'status', status:'compacting'}`
2. `system {subtype:'status', status:null, compact_result:'success'|'failed', compact_error?}`
3. **only on success**: a second `system {subtype:'init'}` (same `session_id`, re-announces
   model/tools/cwd — harmless to re-translate, just updates `modelState.currentModel` again),
   then `system {subtype:'compact_boundary', compact_metadata:{trigger, pre_tokens,
   post_tokens, cumulative_dropped_tokens, duration_ms, preserved_segment,
   preserved_messages}}`, then the CLI auto-continues the turn (synthetic `user` message(s)
   summarizing what was kept) and a normal `result` for the `/compact` prompt itself (so it
   *does* count as a turn/cost — expected, it's a real summarization call).
4. **on failure** (`compact_result: 'failed'`) there is no re-init and no `compact_boundary`
   — just the two status messages, then the turn's own `result`. Observed failures: "Not
   enough messages to compact" (trivial single-turn conversation) and, more importantly,
   compaction failing outright on a model the account can't use for it (`claude-3-5-haiku-
   20241022` → "may not exist or you may not have access to it, run --model to pick a
   different model") even though normal turns on that same model worked fine. **A failed
   compact must surface as a non-fatal `error` event** (from `compact_error`), not be
   silently swallowed — the `compact` command can fail per-model in ways a turn doesn't.

   `pre_tokens`/`post_tokens` on `compact_metadata` are the right source for
   `context_compacted`'s `preTokens`/`postTokens` — confirmed against a real run (17707 →
   1973). Do **not** try to derive them by diffing two `getContextUsage()` snapshots: a
   `getContextUsage()` call made after the enclosing `result` already reflects further
   token growth from the auto-continuation turn (observed 16157, well above `post_tokens`
   1973) — it's the right value for the *next* `context_usage` chip update, just not for
   the compaction event itself.

**Codex** (schema generated live from the installed `codex-cli` 0.151.0 via `codex
app-server generate-json-schema`, both its default and `v2` bundles — identical surface on
this point, so no `--experimental`/version flag is needed): `thread/compact/start` is a
real request (`ThreadCompactStartParams {threadId}` → empty `{}` response). Its companion
notification is `thread/compacted` (`ContextCompactedNotification {threadId, turnId}`,
marked deprecated in favor of a `context_compaction` *item* type in the newer item-stream
API we don't otherwise use) — **it carries no pre/post token counts**, unlike Claude's
`compact_boundary`. `thread/tokenUsage/updated` (already partially consumed by
`sidecar-codex/src/session.ts:253`) carries `tokenUsage: {last: TokenUsageBreakdown, total:
TokenUsageBreakdown, modelContextWindow: number|null}`, breakdown =
`{inputTokens, cachedInputTokens, cacheWriteInputTokens, outputTokens,
reasoningOutputTokens, totalTokens}`. Consequence for C1: **Codex must snapshot
`tokenUsage.last.totalTokens` itself** right before sending `thread/compact/start` and
again from the next `thread/tokenUsage/updated` after `thread/compacted` arrives, then
compute `preTokens`/`postTokens` from that pair — there's no live end-to-end turn run here
(schema-only confirmation), so this snapshot-diff approach should be treated as
provisional and double-checked against a real Codex compaction the first time C1's Codex
tests are written against a live app-server.

**Capability verdict**: `compact: true` for **both** providers.

### Step C1 — Protocol + sidecars

Edit sites: `sidecar/src/protocol.ts` and `sidecar-codex/src/protocol.ts` (**both**, in sync —
`scripts/check-protocol-sync.mjs`), `sidecar/src/session.ts` (`context_usage` after
`turn_complete`, `context_compacted` from `compact_boundary`, `compact` command), `sidecar/
src/index.ts` (command dispatch), `sidecar-codex/src/session.ts` (keep `tokenUsage.last` and
`modelContextWindow`; `compact` → the confirmed request; `context_compacted` from its
notification), `sidecar-codex/src/index.ts`, both `capabilities` handshakes (`compact`),
`docs/PROTOCOL.md` (three new message shapes, one capability).

- `context_usage` is emitted **after** `turn_complete` (so the journaled turn cost is
  already final) and after `context_compacted`; never mid-turn (no need — the chip is a
  between-turns signal).
- `compact` while a turn is running → `error { fatal: false, message: "compact: turn in
  progress" }`; the backend gates it anyway (C2), this is the belt.
- Tests: `sidecar/src/session.test.ts` translation cases for the two events; `sidecar-codex`
  rpc test for the compaction request/notification mapping.

DoD: `npm test` + `tsc` green in both packages; `check-protocol-sync.mjs` passes; `npm run
drive` shows `context_usage` after each turn.

### Step C2 — Backend: persist, journal, expose, `POST …/compact`

Edit sites: `V15` (`session.context_tokens int`, `session.context_window int`, nullable),
`session/SessionEntity` + `SessionRepository` (+ `updateContextUsage`), `session/SessionService`
(handle `context_usage` → persist + journal as a non-transcript event, `context_compacted` →
journal as a transcript boundary event; `compact(id)` → refuses unless IDLE/parked-wakeable,
sends the command, same shape as `setModel`), `web/SessionController` (`POST
/api/sessions/{id}/compact`), `config/Settings` (`contextWarnPercent`), `SessionView`/summary
(`contextTokens`, `contextWindow`), `docs/PROTOCOL.md` (WS event shapes), `ARCHITECTURE.md`.

- Journal payload for `context_compacted` includes `preTokens`/`postTokens`/`trigger` so the
  transcript can render "— compacted: 148k → 21k —".
- `ProviderCapabilities` gains `compact` (read from the handshake like `interrupt`).
- Tests: `SessionServiceTest`-level (whatever the existing pure-logic split allows — see
  phase 9 T1) for the state gate; `*DbTest` for the column round trip.

DoD: `GET /api/sessions/{id}` carries the numbers after one turn and after a backend
restart; `POST /compact` on a RUNNING session → 409; on an IDLE Claude session → a
`context_compacted` event within the turn timeout and the numbers drop.

### Step C3 — Frontend: chip, suggestion card, transcript boundary

Edit sites: `components/SessionWidget.tsx` (chip beside the cost chip — `.chip` with
`.ctx-warn`/`.ctx-danger` modifiers mapped to `--amber`/`--red`; hover title with tokens/
window/auto-compact), new `components/ContextSuggestionCard.tsx` (styled like
`PermissionCard` — same padding/border, `--warn-bg` background; "Compact" is `.primary`,
disabled + `.pulse` while the request is in flight, hidden when the capability is false;
"Dismiss" plain), `components/Transcript.tsx` (`context_compacted` → a centered muted
divider line), `store/store.ts` (`contextTokens`/`contextWindow`/`ctxSuggestionDismissedAt`
per session; re-arm on `context_compacted`), `components/SettingsDialog.tsx` ("Sessions" →
"Context warning at N %"), `protocol.ts`, `api/rest.ts`, `icons.ts` (pick a role name for the
compact glyph — check for collisions with the existing minimize/collapse icons first),
`HotkeyCheatsheet`/hotkeys (no new hotkey unless one falls out naturally; the card's
buttons are focusable like the permission card's).

- The card appears at most once per crossing per session; dismissed state is in the store
  (per tab), not persisted — a reload re-evaluates the threshold and may show it again,
  which is the honest behaviour.
- `vitest` for the store: threshold crossing sets the flag once, compaction re-arms, a
  lower reading doesn't re-trigger.

DoD: manual script items 9–13.

### Step D — Docs + decision log

`CLAUDE.md` (Limits: new settings; Persisted settings: "MCP servers" + context warn; a
"Serena" paragraph under the MCP/skills area; `MCP_TIMEOUT` mention), `docs/PROTOCOL.md`,
`docs/ARCHITECTURE.md` (§ for each track), `docs/DEPLOY.md` (macOS: `uv` + a Serena checkout
are optional prerequisites), `docs/plan/README.md` (phase table row exists; decision-log
entries per landed step, as phase 11 did).

## Out of scope for this phase

- **Backend-owned Linear OAuth** (a "Connect Linear" flow against `mcp.linear.app` with
  tokens in the DB, enabling direct MCP/GraphQL fetches and a tool-less Haiku turn for the
  branch/prompt only). The clean next step if 15-minute caching + pre-warm still feels slow.
- Direct GraphQL for API-key installs (cheap, but nobody here uses that mode).
- Serena hooks (`serena-hooks …` in `.claude/settings.json`), Serena modes (`--mode`),
  Serena's JetBrains language backend, and a Serena dashboard link in the widget (the port is
  chosen at start; surfacing it means parsing Serena's stderr — maybe later).
- A generic "add any stdio MCP server" UI. Serena is the one with per-session arguments the
  user can't type into a template; anything static already works through the template's raw
  `mcpConfig`.
- Hand-off-to-new-session from the context card (decision 4), and automatic compaction
  policy (the CLI's own auto-compact already runs; we show where it will trigger).
- Per-category context breakdown (the SDK provides it; the chip's hover can grow into it
  later without protocol changes if `context_usage` carries an optional `breakdown`).

## Definition of Done (whole phase)

- Every step's DoD above holds.
- **A**: with Linear enabled, opening the create dialog and clicking Browse twice within
  15 min costs one system turn; Refresh costs one more; picking a ticket twice costs one
  `import` turn; the picker never shows an empty list while a refresh is in flight.
- **B**: with `mcp.serena-root` set and `uv` resolvable, a session created with
  `serenaEnabled` (Claude and Codex) lists `mcp__serena__*` tools and can `find_symbol` in
  its worktree; its Git panel shows no `.serena/` noise; a session without the flag has an
  `mcpConfig` byte-identical to phase 11's; an unset root makes the checkbox disappear and a
  template with the flag set fails creation with a readable 400.
- **C**: every session summary carries `contextTokens`/`contextWindow` after its first turn
  and after a backend restart; the chip colours follow the Settings threshold in both
  themes; the suggestion card appears once on crossing, Compact on a Claude session
  produces a `context_compacted` divider and a lower chip, and the card re-arms after it;
  a Codex session shows the chip and (per C0's verdict) either a working Compact or no
  button.
- All existing suites green: `./mvnw test` (incl. `integration`), `npm test` + `npm run
  build` in `frontend` / `sidecar` / `sidecar-codex`, `check-protocol-sync.mjs`.

## Manual test script

1. Linear enabled (OAuth toggle on). Restart the backend (cold system session). Open **New
   Session** → within ~10 s `tail -f /tmp/claude-ui.log` shows the system session spawning
   and one `system task` line (the prefetch). Click **Browse** → rows appear without the
   45 s warning.
2. Close and reopen the dialog, Browse again → no new `system task` line; header reads
   "as of N min ago".
3. Click **Refresh** → button pulses, rows stay visible, one new `system task` line, "as of
   just now".
4. Pick a ticket → one `import` turn; cancel the create; **Quick Session** → same ticket →
   branch/prompt fill instantly, no turn.
5. Settings → MCP servers → Serena root `/nonexistent` → inline error; root
   `/mnt/d/projects/serena` (macOS: your checkout) → saves. (If `uv` isn't on the backend's
   PATH, set the uv path too.)
6. New Session on this repo, tick **Serena**, Claude provider → the spawn log line's
   `--mcp-config` file has a `serena` entry with `--project <worktree>`; ask the agent
   "use serena to find the class SessionConfigFactory and list its methods" → it calls
   `mcp__serena__find_symbol`; Git panel shows no `.serena/` entry.
7. Same with Codex → `--context codex` in the entry; a Serena tool call goes through the
   normal approval flow.
8. Duplicate the session → Serena stays ticked. Untick in the template → new sessions from
   it have no `serena` entry. Clear the root in Settings → the checkbox disappears from the
   dialog; a template with it on fails with the 400 text.
9. Any Claude session: after the first turn the widget shows `ctx N%`; hover shows tokens /
   window / "auto-compact at M%". Reload the page → the chip is there before any turn.
10. Settings → Sessions → context warning at 5 % (test value) → the chip turns amber on the
    next turn and the suggestion card appears once; **Dismiss** hides it; another turn
    doesn't bring it back.
11. Set it back to 70, drive a session past the threshold (or leave the 5 % test value) →
    click **Compact** → the button pulses, the transcript gets a "— compacted: X → Y —"
    divider, the chip drops, and one more crossing shows the card again.
12. While a turn is running, `curl -X POST …/api/sessions/{id}/compact` → 409 problem+json.
13. Codex session → chip present; Compact button present iff C0 confirmed it, and if
    present behaves as in 11.
14. Light theme pass over the picker header, the Serena chip, the ctx chip states, and the
    suggestion card.
