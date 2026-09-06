# Phase 9 — Architectural review & production hardening

Status: **Run A done (2026-09-06)**; the rest of the backlog is unpicked. This phase is different from earlier ones:
it is a curated backlog produced by a full architectural review (2026-09-06), not one
feature plan. Each item below is self-contained with enough context to be picked up as
its own run; pick order suggestions are at the bottom. Security is explicitly out of
scope (LAN/single-user posture is a documented decision).

Review inputs: every backend class, both sidecars, the frontend component tree, all
docs, pom/package manifests, and the journal-event inventory (grep of `record(`/
sidecar `type:` vs PROTOCOL.md).

---

## 9.1 Test coverage (currently: none)

The entire system has **one** automated test — `ApplicationTests.contextLoads` (which
itself needs live Postgres). `sidecar/`, `sidecar-codex/`, and `frontend/` have no test
runner installed at all (no `test` script in any package.json). Every phase's DoD was a
manual test script; that was the right call to move fast, but it means any refactor
from this phase onward is unguarded — so test items come first, and the dedup items in
9.3 should each land *with* the tests that lock in current behavior.

- **T1 — DONE (2026-09-06).** Pure-logic unit tests, plain JUnit (+ AssertJ; no
  Mockito needed — `SessionServiceTest` fakes `SettingsService` with a tiny anonymous
  subclass instead), no Spring/no DB. 54 tests, all under `src/test/java/de/pamir/
  claude/ui/**` alongside their target classes:
  - `CodexCostEstimator.estimate` (pricing-table math, missing-model fallbacks) — `session/CodexCostEstimatorTest`
  - `memory/Frontmatter` (round-trip, no-fence/unterminated-fence input, CRLF) — `memory/FrontmatterTest`
  - `memory/Wikilinks` — `memory/WikilinksTest`; `memory/MemoryPaths` (slug/collision/marker-file logic) — `memory/MemoryPathsTest`
  - `journal/TranscriptDigest.render`/`renderMarkdown` (caps, event-type formatting) — `journal/TranscriptDigestTest`
  - `discovery/ServiceDigest.render` — `discovery/ServiceDigestTest`
  - `TicketImportService`: `sanitizeBranch`, `parse`, `parseTickets` — `integration/TicketImportServiceTest`
  - `GitAssistService.resolveTicketRef` — `session/GitAssistServiceTest`
  - `SessionService` helpers: `fillPlaceholders`, `extractText`,
    `withDefaultLinearMcp`/`withDefaultMemoryMcp` merge rules, `combineTemplateSources`
    (archived-asset skip + warning) — `session/SessionServiceTest`

  Each target method's visibility moved from `private` to package-private (some also
  made `static` where they touched no instance field) — logic untouched, just enough to
  test without mocking dependencies the method doesn't use. Still true: full extraction
  into a helper class is G2/S1's job, not done here.
- **T2 — Session state-machine tests** (mocked `SidecarManager`/repositories, or a
  thin fake handle). This is the riskiest logic in the codebase and it changes often:
  `sendUserMessage` routing per state (IDLE dispatch / PARKED wake+enqueue / RUNNING
  enqueue / CLOSED reject), `becomeIdleAndDrainQueue` (peek-not-pop retry semantics,
  budget-exhausted hold), `close` dirty-mode branches, `resume` guard, park/wake,
  `onSidecarExit` crash vs shutdown-requested, `runSystemTurn` timeout/model-restore/
  crash completion. Requires modest constructor-injection refactoring or Mockito.
- **T3 — Repository/SQL tests against real Postgres.** `mvn verify` already requires
  the compose DB, so `@JdbcTest`-style slices cost nothing new. Priorities:
  `MemoryRepository.search` (the 3-arm RRF SQL — dense off/on, scope visibility
  filters), `SessionRepository.findAwaitingPrCheck` cutoff logic, journal
  `deleteDeltasBefore`/`costToDate`, proposal unique-pending index. Optional stretch:
  Testcontainers so tests stop depending on the ambient compose stack (decide
  deliberately — it changes the documented "Postgres must be up" workflow).
- **T4 — Sidecar tests (vitest, both packages).** `permissions.ts` `readOnlyDenial`
  (worktree containment, symlink edge), Claude-message→NDJSON translation in
  `session.ts` (feed canned SDK messages, assert emitted events), sidecar-codex
  `rpc.ts` request/response framing, `mcp.ts` Claude-shaped→Codex config translation
  (bearer-token→env-var rule), `approvals.ts` mapping.
- **T5 — DONE (2026-09-06).** `sidecar-codex/src/protocol.ts`/`stdio.ts` turned out
  *not* to be byte-identical to `sidecar/`'s — they intentionally differ in exactly
  three documented spots (the header comment, each package's own `*_CAPABILITIES`
  const block, and the `console.error('[sidecar...]', ...)` log prefix), so a plain
  `diff -q` would have failed immediately on a clean checkout. `scripts/
  check-protocol-sync.mjs` (plain Node, no new dep) instead strips comments and those
  two known-different spots from both files before comparing, so it catches real drift
  (a field added to `Capabilities` in only one copy, an interface changed in only one)
  while staying green on the legitimate differences. Verified against both: passes on
  the current files, fails with a line-numbered diff when a synthetic field was added
  to only one copy. Wired into CI (T7) as its own fast first step.
- **T6 — Frontend store tests** (vitest): event application in `store/store.ts`
  (state_changed / queue_updated / pr_status_changed / reflection events → view
  updates), `protocol.ts` guards. Lower priority than backend. Not done.
- **T7 — DONE (2026-09-06).** `.github/workflows/ci.yml`, three parallel jobs: `backend`
  (`./mvnw -Dskip.installnodenpm -Dskip.npm -DexcludedGroups=integration test` —
  compiles everything and runs T1's unit tests; `ApplicationTests` is now `@Tag(
  "integration")`-excluded since CI has no Postgres service), `sidecars` (T5's guard,
  then `npm run build`/`tsc` for both `sidecar/` and `sidecar-codex/`), `frontend`
  (`npm run build`, which is already `tsc --noEmit && vite build`). No ESLint added —
  out of scope for this pass; ESLint config for the sidecars is still open.

## 9.2 Claude-only coupling (Codex-only install breaks LLM features)

The system supports `provider: codex` for *user* sessions, but every backend-initiated
LLM feature assumes a working `claude` CLI login:

- **P1 — System session hardcodes provider `"claude"` + model `"haiku"`**
  (`SessionService.createSystemSession`). Everything funnels through it: ticket
  import + "my tickets" list, library AI-fill, reflection, service discovery,
  commit/PR drafting (`GitAssistService`), handoff briefs. On a Codex-only machine all
  of these fail at spawn. Fix direction: system session provider follows the persisted
  `session.default-provider` (or its own `session.system-provider` setting), and the
  model comes from a per-provider tier map (see P3) instead of the literal `"haiku"`.
  Note `runSystemTurn(modelOverride)` also passes Claude aliases
  (`memory.reflection-model` / `service-discovery.model` settings default `"haiku"`)
  straight into `set_model` — those settings need to become tier names or per-provider.
- **P2 — Auto-titling bypasses the provider abstraction entirely**:
  `SessionService.maybeAutoTitle` spawns raw `claude -p --model haiku`. Breaks without
  the CLI, and its cost is invisible to the usage dashboard. Route it through
  `runSystemTurn` — but note the serialization trade-off: `runSystemTurn` holds one
  global lock for up to the caller's timeout (3 min for reflection), so a naive move
  makes titles wait behind reflections. Either accept that (titles are best-effort
  anyway) or give system turns a small queue (see S2).
- **P3 — Model vocabulary is hardcoded Claude aliases everywhere.** `sonnet|opus|haiku`
  appears in: `TicketImportService.VALID_MODELS` + its prompt text,
  `SettingsService` defaults, and the frontend in five places
  (`CreateSessionDialog`, `QuickSessionDialog`, `TemplateManager`, `SettingsDialog`
  ×2, `SessionWidget.MODEL_CYCLE`). The adapter `Capabilities` record has
  `modelSwitch: boolean` but no model *list*. Fix direction: extend the capabilities
  handshake (and `GET /api/providers`) with `models: [{id, label, tier: cheap|standard|premium}]`;
  frontend dropdowns and the ticket-import "recommendedModel" mapping derive from it;
  backend maps tier→id per provider for system turns.
- **P4 — Bug: `SessionWidget.cycleModel` ignores provider.** `cycleMode` filters by
  `capabilities.permissionModes`, but `cycleModel` cycles the hardcoded Claude list
  unconditionally — clicking the model chip on a Codex session sends `set_model:
  "sonnet"`. Falls out of P3 for free; worth a point-fix earlier if Codex sessions are
  in real use.
- **P5 — Pin the Agent SDK.** `sidecar/package.json` declares
  `"@anthropic-ai/claude-agent-sdk": "latest"` — any upstream breaking release bricks
  every session at the next `npm install` with nothing in git to bisect. Pin an exact
  version (renovate/manual bumps thereafter). Check `sidecar-codex`'s deps too.

## 9.3 Genericity & duplication cleanup

- **G1 — One `SystemTurnClient` for the "LLM turn → JSON" pipeline.** The exact
  `stripFences` + `truncate` helpers are copy-pasted **five** times
  (`ReflectionService`, `ServiceDiscoveryService`, `TicketImportService`,
  `LibraryAiService`, `GitAssistService`), and the surrounding
  runSystemTurn→stripFences→`mapper.readTree`→validate-fields flow is hand-rolled in
  all six consumers (those five + `HandoffService`, which is text-not-JSON). Extract
  e.g. `SystemTurnClient.json(prompt, model, timeout)` / `.text(...)` with unified
  fence-stripping, error truncation, and per-field accessors; the lowercase-tags array
  parse is also duplicated 3×. This is the single best dedup in the codebase and makes
  P1/P2 changes one-place edits.
- **G2 — Merge `withDefaultLinearMcp`/`withDefaultMemoryMcp`** into one
  `withDefaultServer(configured, key, serverBlock)` (identical logic, different key).
- **G3 — Shared pgvector helper.** `toVectorLiteral` is copy-pasted in **four**
  repositories (`LibraryRepository`, `MemoryRepository`, `MemoryEpisodeRepository`,
  `ServiceProfileRepository`), and the "embed best-effort, log-and-continue" wrapper
  is re-implemented in `ReflectionService.maybeEmbedEpisode`,
  `ServiceDiscoveryService.embedBestEffort`, and the library path. One
  `PgVector.literal(float[])` util + one `EmbeddingClient.tryEmbed` default method (or
  small `EmbeddingSupport` component) removes all of it.
- **G4 — `record(id, type, payload)` journal-publish idiom** (`bus.publish(journal.append(...))`)
  is re-implemented in `SessionService`, `ReflectionService`, `PrCheckPollingService`.
  A tiny `JournalPublisher` facade keeps the "journal assigns seq; subscribers see
  exactly what replay will" invariant in one place.
- **G5 — Frontend: extract the ticket-import flow.** `QuickSessionDialog` and
  `CreateSessionDialog` duplicate the whole importTicket/browseRecentTickets/
  recommendedModel/abort-controller dance (~80 lines each). Extract a
  `useTicketImport()` hook + a shared `<ModelSelect>` fed by provider capabilities
  (lands naturally with P3).
- **G6 — (optional) Event-listener + virtual-thread + `inFlight` guard pattern** is
  duplicated between `ReflectionService` and `ServiceDiscoveryService`; the
  scheduled-tick-with-settings-cutoff pattern across the four background services is
  similar-but-fine. Only worth touching if a third copy appears — noted so it isn't
  "discovered" again.

## 9.4 Simplification

- **S1 — Split `SessionService` (1,164 lines, ~8 responsibilities).** It currently
  owns: create-config parsing/merging, provisioning orchestration, message routing +
  queue drain, budget guard, the system-session singleton + pending-turn machinery,
  Codex cost rewriting, auto-titling, idle parking, and the startup orphan sweep.
  Natural seams, each independently extractable:
  - `SystemSessionService` — `runSystemTurn`, `getOrCreate/createSystemSession`, the
    `pendingSystemTurn`/`pendingSystemText` trio (subtle volatile handshake; isolating
    it makes T2 testable), plus a hook SessionService calls from
    `onSidecarEvent`/`onSidecarExit`.
  - `SessionConfigFactory` — template merge, codex validation, MCP layering,
    `configOverridesFrom`/`lastSessionConfig`. The codex `if`-cascade should read
    from a per-provider capability/`unsupportedFields` declaration rather than
    hand-written checks.
  - `AutoTitleService` (merges with P2).
  - Parking + orphan sweep into a small `SessionHousekeeping`.
- **S2 — Revisit `runSystemTurn`'s single global lock.** Every system-turn consumer
  serializes behind one `synchronized` block held for up to the caller's timeout —a
  close-triggered 3-minute reflection blocks an interactive ticket import (user sits
  watching a spinner). Options: (a) leave it, but split timeouts into
  interactive (45 s) vs background lanes and document; (b) a fair queue with priority
  for interactive callers; (c) allow N system sessions. (a) or (b) recommended;
  (c) fights the session cap.
- **S3 — `SessionEntity`'s ~40-arg positional constructor.** Two call sites
  (`create`, `createSystemSession`) already differ by long `null, null, …` runs that
  are easy to misalign silently — a builder (or grouping into small records:
  identity/provider/config/limits/lifecycle) turns the next added column from a
  40-arg diff into a one-liner. `configOverridesFrom`'s 20-field hand copy could
  likewise become entity→JSON with an exclusion list so new fields are copied by
  default instead of forgotten.
- **S4 — Collapse the three stacked `create(...)` overloads** into one signature with
  a small `CreateOptions` record (name/branch/base/repo/template/overrides/kickoff/
  sync/continuedFrom/parent) — the overload ladder exists only to avoid touching call
  sites, and it's grown twice already (7.3, 7.4).

## 9.5 Other observations (reviewer's discretion)

- **O1 — `enforceSessionLimit` is check-then-insert with no lock** — two concurrent
  creates can both pass the count. Single-user makes it near-impossible today, but
  7.4's `spawn_child_session` makes concurrent creates real (a parent fanning out).
  Cheap fix: synchronize the count+insert, or re-check after insert and fail the loser.
- **O2 — Auto-title cost is unaccounted** (raw CLI call — folded into P2).
- **O3 — `library.vectorize` vs memory search asymmetry**: library search is
  dense-only when vectorized, memory search is a 3-arm hybrid. Fine functionally, but
  once G3 lands, promoting library search to the same hybrid helper is ~free and makes
  the two search boxes behave consistently.
- **O4 — Frontend `notify()` strings and event-type dispatch live inline in
  `SessionWidget`/`Dashboard`** — fine at this size; consider a single
  event→notification map only if more event types are added.
- **O5 — No graceful backend handling if the Voyage/Linear key is present but
  invalid** — errors surface per-call as warnings. Acceptable; noted for awareness.

## 9.6 Documentation drift

- **D1 — DEPLOY.md is five phases stale** (last real update 2026-08-25) and is the doc
  a fresh-machine deploy actually follows:
  - No `codex` CLI prerequisite row and **no `sidecar-codex` build step** in sections
    5 and 9 — a `provider: codex` session on a fresh Mac deploy fails to spawn.
  - Missing since then: memory vault (`CLAUDE_UI_MEMORY_ROOT` + Settings → Memory),
    `CLAUDE_UI_VOYAGE_API_KEY`, skill library settings, PR-check poller, service
    discovery, reflection approval flow, quick-session hotkey.
  - Section 7 refers to buttons by emoji glyphs ("🔔", "⚙️", "⎇") that the icon
    unification (commit 44d50b7) removed from the DOM — describe by name/title text.
  - Troubleshooting still names `CLAUDE_UI_LINEAR_OAUTH`, an env var replaced by the
    persisted OAuth setting on 2026-08-25 (phase-5-extensions.md addendum).
- **D2 — ARCHITECTURE.md has no Phase 8 section**: service discovery (profiles table,
  digest→description generation, the three MCP tools, staleness/SHA gating) and the
  quick-session flow are absent; its last touch only added the visual style section.
- **D3 — PROTOCOL.md is missing three journaled event types**: `budget_updated`,
  `budget_exhausted`, and `session_renamed` (verified by diffing the emitted-event
  inventory against the doc). Everything else checked out.
- **D4 — CLAUDE.md is current** (verified against phase 8 / quick-session); only note
  is that once Phase 9 items land (e.g. tests, system-provider setting), its
  "Build & test" and settings sections need the corresponding one-liners.

---

## Suggested pick order

1. **Run A (safety net first) — DONE 2026-09-06.** T1 + T5 + T7. Everything after this
   lands on green CI.
2. **Run B (docs):** D1–D3 — pure writing, no risk, immediately useful for the next
   Mac deploy.
3. **Run C (the big dedup):** G1 + G2 + G3 + G4 with tests locking each behavior
   in as it's extracted; P5 (SDK pin) rides along as a one-liner.
4. **Run D (provider decoupling):** P3 (model catalog in capabilities) → P1 (system
   session provider/tier) → P2+O2 (auto-title via system turn) → P4/G5 (frontend).
5. **Run E (structural):** S1–S4 + T2, then T3/T4/T6 to taste.

Items not picked stay valid backlog; re-review after Run D whether S2's lock trade-off
still matters in practice.
