# Phase 9 — Architectural review & production hardening

Status: **Runs A, B, C, D, and E done (2026-09-06)**; the rest of the backlog is unpicked. This phase is different from earlier ones:
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
- **T2 — DONE (2026-09-06, Run E).** Session state-machine tests, fake `SidecarManager`/
  `SessionRepository`/`EventJournal`/`GitWorktreeService` doubles — no Mockito, no DB, no
  real OS process (`FakeSidecar` builds a real `SidecarHandle` over an in-memory
  `Process` subclass instead). `SessionStateMachineTest` (25 tests): `sendUserMessage`
  routing per state (IDLE dispatch / IDLE-but-dead-handle reject / PARKED wake+enqueue /
  CREATING..WAITING_INPUT enqueue / terminal reject / budget-exhausted reject on both
  IDLE and PARKED), `becomeIdleAndDrainQueue` (peek-not-pop retry semantics on a broken
  handle, budget-exhausted hold), `close` (already-closed no-op, dirty-mode branches
  incl. unknown-mode rejection, system-session skips the dirty check, reflection/
  service-discovery event publishing), `resume`'s CRASHED-only guard, `onSidecarExit`
  (terminal-state no-op, shutdownRequested no-op, unexpected-exit → CRASHED, unknown
  session no-op). `SystemSessionServiceRunTurnTest` (4 tests, a fully-wired real
  SessionService/SystemSessionService pair — the actual two-way collaboration
  production uses, not mocks): successful completion via the onAssistantMessage/
  completeTurn hooks, timeout, a fatal-error crash surfaced as failure, and the
  model-override/restore dance (asserted via the raw `set_model` lines sent, since the
  fake sidecar never echoes back a `model_changed` event). The SystemSessionService↔
  SessionService constructor cycle (real in production via `@Lazy`) is seeded in these
  tests via one reflection call setting the private field directly — documented as
  standing in for what Spring's lazy proxy resolves automatically, not a pattern to
  reuse elsewhere. Not covered: `SystemSessionService`'s fail-fast lock-contention
  behavior itself (S2) — proving it would need controllable time, not just fakes;
  `SessionHousekeeping`'s scheduled tick (never had a dedicated test, split or not).
  Unit test count 88 → 116.
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

- **P3 — DONE (2026-09-06).** New `ModelCatalog` (`session/ModelCatalog.java`,
  synced-in-spirit with `sidecar/src/protocol.ts`'s `CLAUDE_CAPABILITIES.models` and
  `sidecar-codex/src/protocol.ts`'s `CODEX_CAPABILITIES.models`, both added to the
  `Capabilities` interface the protocol-sync guard checks) is the single source of
  truth for `{id, label, tier: cheap|standard|premium}` per provider — Claude gets all
  three tiers, Codex an empty list (deliberately: "no hardcoded Codex model list" is a
  standing decision from phase-5.13, so the frontend falls back to free text exactly
  as before). `GET /api/providers` and every adapter's `ready.capabilities` now carry
  `models`; `ModelCatalog.byTier(provider, tier)` lets backend code pick "the cheap
  model for whatever provider is active" instead of a literal alias — the mechanism
  P1/P2 build on.
- **P1 — DONE (2026-09-06).** `SessionService.createSystemSession` now reads provider
  from the new `session.system-provider` setting (`SettingsService.systemProvider()`,
  empty = follow `session.default-provider`) and model from
  `ModelCatalog.byTier(provider, "cheap")` (null on a provider with no catalog, same as
  "unspecified" already meant for non-Claude sessions). `memory.reflection-model` and
  `service-discovery.model` changed from a raw Claude alias to a tier name
  (`SettingsService.normalizeTier` maps a pre-existing "haiku"/"sonnet"/"opus" value to
  its tier so an existing install's choice survives); `ReflectionService`/
  `ServiceDiscoveryService` resolve the tier via `ModelCatalog.byTier(settings.
  systemProvider(), tier)` right before the `systemTurnClient.json(...)` call.
  `TicketImportService`'s prompt (recommendedModel field + guidance text) is now built
  from `ModelCatalog.models(settings.defaultProvider())` instead of a hardcoded
  `sonnet|opus|haiku` enum, and `parse()` takes the valid-id set as a parameter instead
  of a `private static final Set` so it stays a dependency-free pure function (test
  updated to pass the set explicitly). Caveat documented on `systemProvider()`'s
  javadoc and in the Settings dialog's tooltip: Codex rejects `allowedTools` outright
  (existing decision 10, phase-5.13), so a Codex system session gets no MCP
  pre-approval and a Linear/memory-tool turn will simply time out — not fixed here,
  just made visible instead of silently inherited.
- **P2 + O2 — DONE (2026-09-06).** `SessionService.maybeAutoTitle` now calls
  `runSystemTurn(prompt, ModelCatalog.byTier(settings.systemProvider(), "cheap")
  .orElse(null), Duration.ofSeconds(60))` from its existing virtual thread instead of
  spawning `claude -p --model haiku` directly — works on a Codex-only install, and the
  turn's cost now lands in the usage dashboard like every other system turn (O2)
  instead of being invisible. The 60s timeout and ≤80-char sanity check are unchanged;
  accepted the documented serialization trade-off as-is (titles queue behind an
  in-flight reflection on `runSystemTurn`'s lock) since auto-titling was already
  best-effort and fire-and-forget from the caller's perspective — S2 (Run E) is where
  a fair queue would go if this turns out to matter in practice.
- **P4 — DONE (2026-09-06).** `SessionWidget.cycleModel` now cycles
  `view.capabilities.models` (the session's own live, provider-correct catalog) instead
  of a hardcoded `['sonnet','opus','haiku']`, and no-ops when that list is empty
  (Codex) rather than sending a Claude alias into `set_model`; the model chip's
  `clickable`/tooltip state now also requires a non-empty catalog, not just
  `modelSwitch`. Fell out of P3 as intended, landed alongside it.
- **P5 — DONE (2026-09-06).** `sidecar/package.json` pinned
  `"@anthropic-ai/claude-agent-sdk"` to the exact version already resolved in
  `package-lock.json` (`0.3.241`) instead of `"latest"`, so a bare `npm install` can
  no longer silently pick up a breaking upstream release; bump deliberately
  thereafter. `sidecar-codex/package.json` has no such dependency (it drives the
  `codex` CLI via subprocess, not an SDK package) — nothing to pin there.

## 9.3 Genericity & duplication cleanup

- **G1 — DONE (2026-09-06).** `SystemTurnClient` (`session/SystemTurnClient.java`)
  centralizes the "LLM turn → JSON" pipeline: `.text(prompt, [model,] timeout)` for
  plain-text replies (`HandoffService`), `.json(prompt, [model,] timeout)` for
  fence-stripped-then-parsed replies, throwing `IllegalStateException` with a
  truncated raw preview on unparsable JSON — one implementation instead of five
  copies of `stripFences`/`truncate` (`ReflectionService`, `ServiceDiscoveryService`,
  `TicketImportService`, `LibraryAiService`, `GitAssistService`). The five JSON
  consumers now call `.json(...)` and validate fields on the returned `JsonNode`
  directly, rather than hand-rolling `runSystemTurn→stripFences→mapper.readTree`;
  `TicketImportService.parse`/`.parseTickets` and `LibraryAiService.parse` changed
  signature from raw `String` to `JsonNode` accordingly (tests updated to match —
  parsing-from-fenced-text cases moved to `SystemTurnClientTest`). Static
  `SystemTurnClient.lowercaseTags(JsonNode)` also replaces the "read `tags`, strip,
  lowercase, skip blanks" parse duplicated in `ServiceDiscoveryService`,
  `LibraryAiService`, and `ReflectionService.applyOp` (the last of those previously
  skipped the lowercase/blank-filter step — now consistent with the other two, a
  deliberate small behavior tightening). `ReflectionService`/`ServiceDiscoveryService`
  collapsed their two-stage try/catch (turn failure vs. bad JSON, separately logged)
  into one catch around `.json(...)`, since its exception message already names the
  failure. Not done: P1/P2 (still separate backlog items) — this just makes them
  one-place edits when picked up.
- **G2 — DONE (2026-09-06).** `withDefaultLinearMcp`/`withDefaultMemoryMcp`
  (`SessionService`) now both delegate to a private `withDefaultServer(configured,
  key, serverBlock)` carrying the shared merge rule; the two public methods stay as
  one-line wrappers (kept public/package-private for the existing `SessionServiceTest`
  coverage, unchanged).
- **G3 — DONE (2026-09-06).** `PgVector.literal(float[])` (`library/PgVector.java`)
  replaces the byte-identical `toVectorLiteral` copy-pasted in `LibraryRepository`,
  `MemoryRepository`, `MemoryEpisodeRepository`, and `ServiceProfileRepository`.
  `EmbeddingClient.tryEmbed(text, query)` (default method) replaces the "not
  configured → skip; try embed, log-and-continue on failure" wrapper re-implemented
  in `ReflectionService.maybeEmbedEpisode`, `ServiceDiscoveryService.embedBestEffort`,
  and (found during this pass, not in the original review) `MemoryDocService.maybeEmbed`
  — three void/log-only copies, now one. `LibraryService.maybeEmbed` was deliberately
  left with its own try/catch: unlike the other three, it returns the failure message
  as a user-visible import warning, which `tryEmbed`'s null-on-failure contract can't
  carry — dedup there would have meant losing that detail from the API response.
- **G4 — DONE (2026-09-06).** `JournalPublisher.record(sessionId, type, payload)`
  (`journal/JournalPublisher.java`, wraps `EventJournal.append` +
  `SessionEventBus.publish`) replaces the `bus.publish(journal.append(...))` idiom
  hand-rolled in `SessionService`, `ReflectionService`, `PrCheckPollingService`, and
  (a fourth copy the original review missed) `OrchestrationMcpTools`. Each consumer's
  local `record(...)` helper now delegates to it in one line; `PrCheckPollingService`
  had no such wrapper and now calls `journalPublisher.record(...)` directly.
  `SessionEventBus` remains a live bean in its own right (`SessionWebSocketHandler`
  still subscribes to it for the WS fan-out) — only the append+publish call sites
  changed, not the bus itself.
- **G5 — DONE (2026-09-06).** `frontend/src/hooks/useTicketImport.ts` centralizes the
  importTicket/browseRecentTickets/pickTicket/abort-controller dance (state +
  recommendedModel filtering against a caller-supplied `validModelIds`); both dialogs
  now supply an `onResult` callback that just maps the outcome onto their own local
  fields, rather than owning the fetch logic themselves. `CreateSessionDialog`'s console
  timing logs moved into the hook, so `QuickSessionDialog` picks up the same
  diagnostics for free (it had none before). `frontend/src/components/ModelSelect.tsx`
  is the shared `<select>`-or-free-text control from P3, used by `CreateSessionDialog`
  and `TemplateManager` (whose "session default" provider choice now correctly shows
  free text instead of assuming Claude's model list, since there's no capabilities
  object to key off an empty provider id).
- **G6 — (optional) Event-listener + virtual-thread + `inFlight` guard pattern** is
  duplicated between `ReflectionService` and `ServiceDiscoveryService`; the
  scheduled-tick-with-settings-cutoff pattern across the four background services is
  similar-but-fine. Only worth touching if a third copy appears — noted so it isn't
  "discovered" again.

## 9.4 Simplification

- **S1 — DONE (2026-09-06, Run E).** Split `SessionService` (1,166 lines → 561) into:
  - `SystemSessionService` — `runSystemTurn` (now lane-aware, see S2),
    `getOrCreate/createSystemSession`, the `pendingSystemTurn`/`pendingSystemText` trio,
    and the `onAssistantMessage`/`completeTurn`/`failTurn` hooks SessionService's
    `onSidecarEvent`/`onSidecarExit` call into. A genuine two-way dependency (this class
    calls back into SessionService's package-private session-mechanics — `wake`/`spawn`/
    `transition`/`writeMcpConfig`/`enforceSessionLimit`, plus the already-public
    `sendUserMessage`/`setModel`/`resume`) — SessionService's own dependency on this
    class is a plain constructor edge, while this class's dependency back on
    SessionService is `@Lazy` (only one side of a cycle needs to be, and this is the
    side Spring can construct lazily without anything ever calling a method on it
    mid-construction).
  - `SessionConfigFactory` — template merge, codex validation, default MCP-server
    layering (`withDefaultLinearMcp`/`withDefaultMemoryMcp`, `linearMcpServer`/
    `memoryMcpServer`), the memory/orchestration system-prompt blocks (now
    `extraSystemPrompt(session)`, one method SessionService's `spawn` calls instead of
    two), and `configOverridesFrom` (still surfaced via `SessionService.
    lastSessionConfig()`/`duplicate()`, which stay put as the public API). The codex
    `if`-cascade was left as hand-written checks, not turned into a capability
    declaration — out of scope for a pure extraction, still valid backlog.
  - `AutoTitleService` (merges with P2/O2) — routes its title-generation turn through
    `SystemTurnClient` on the `BACKGROUND` lane (S2) instead of calling
    `runSystemTurn` directly.
  - `SessionHousekeeping` — parking tick + startup orphan sweep; one-way dependency on
    SessionService (no `@Lazy` needed, since SessionService never calls into it).
  Each new class's methods needed for cross-class access are package-private (same
  `session` package), matching T1's established "widen from private, don't leak public
  API" convention — no new public surface beyond what each class already exposed.
- **S2 — DONE (2026-09-06, Run E).** Went with (a): the single system session / single
  lock stays (still exactly one system-session sidecar, so true parallelism would need
  (c) anyway), but the lock is now a fair `ReentrantLock` with a lane-aware **wait-for-
  the-lock** budget — not a lane-aware turn timeout, which every caller already set for
  itself. A new `SystemTurnLane` (`INTERACTIVE`/`BACKGROUND`) is a required param on
  `SystemSessionService.runSystemTurn`/`SystemTurnClient.text|json`: `INTERACTIVE`
  callers (`GitAssistService`, `HandoffService`, `LibraryAiService`,
  `TicketImportService`) give up after 10s with a clear "system session is busy with
  another task" `IllegalStateException` if a background turn is holding the lock,
  instead of silently blocking for however long that turn's own timeout is and then
  running out of their own budget too; `BACKGROUND` callers (`ReflectionService`,
  `ServiceDiscoveryService`, `AutoTitleService`) wait out their full turn timeout, same
  as before, since they're not blocking a human.
- **S3 — DONE (2026-09-06, Run E).** `SessionEntity.Builder` (named setters, sensible
  empty/null defaults) replaces the ~35-arg positional constructor at its two call
  sites (`SessionConfigFactory.prepare`, `SystemSessionService.createSystemSession`);
  `SessionRepository.mapRow` keeps the positional constructor since it's already an
  unambiguous 1:1 ResultSet-column-to-field mapping, not a `null, null, …` run. Also
  added `toBuilder()` (seeds a Builder from an existing entity, for a "copy with one
  field changed" update) — not needed by any production call site yet, but used
  throughout T2's `FakeSessionRepository` and general enough to be worth keeping.
  `configOverridesFrom`'s 20-field hand copy was left as-is (moved to
  `SessionConfigFactory`, not restructured) — the entity→JSON exclusion-list idea is
  still valid backlog.
- **S4 — DONE (2026-09-06, Run E).** The three stacked `create(...)` overloads
  collapsed into one `SessionService.create(CreateOptions)`, where `CreateOptions` is a
  record (name/branch/baseBranch/repoPath/templateId/overrides/kickoffValues/
  syncBaseBranch/continuedFromId/parentSessionId) with an 8-arg secondary constructor
  for the common case plus `withContinuedFrom`/`withParent` fluent methods for the two
  optional links — so a call site that needs one doesn't have to spell out the other as
  `null`. All three call sites updated: `SessionController.create` (`.
  withContinuedFrom(...)`), `OrchestrationMcpTools.spawnChild` (`.withParent(...)`),
  `SessionService.duplicate` (plain constructor, no link).

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

- **D1 — DONE (2026-09-06).** DEPLOY.md: added a Codex CLI prereq row (§1, optional)
  and a `sidecar-codex` build step alongside `sidecar/`'s (§5 and §9); added
  `CLAUDE_UI_MEMORY_ROOT` to §3's path block, with a note that it and
  `CLAUDE_UI_SKILLS_ROOT` are only *defaults* for persisted settings; added a new §8a
  covering `CLAUDE_UI_VOYAGE_API_KEY` and exactly what it unlocks in each of the three
  features that use it (library search is dense-*only* when vectorized — not hybrid
  like memory/service-discovery, corrected in the writing); expanded §7's checklist
  into a real tour of the Settings dialog's tabs (Sessions/Linear/PR checks/Skill
  library/Memory) instead of three bullets, and added the quick-session hotkey; fixed
  the two remaining emoji glyphs (🔔/⚙️) to name/title text (⎇ had already been fixed
  by 44d50b7, contrary to the original finding); troubleshooting's stale
  `CLAUDE_UI_LINEAR_OAUTH` reference corrected to the persisted setting, plus a new row
  for a Codex session crashing when `sidecar-codex` was never built.
- **D2 — DONE (2026-09-06), wider than scoped.** ARCHITECTURE.md gained §3d (ecosystem
  service discovery: table, SHA-gated regeneration, the bounded non-agentic digest, the
  three MCP tools, human-facing controller/dialog) and its quick-session addendum.
  While fixing the top status line for internal consistency, found and fixed three
  more stale "backlog sketch" sections in §4 that were actually already shipped:
  **5.5** (model switching mid-session — deleted, fully covered by PROTOCOL.md's
  command table already), **5.9** (transcript export) and **5.12** (usage dashboard,
  plus an undocumented bonus `/api/usage/stale-sessions` endpoint) — both moved into a
  new §3f "as-built" note. **5.13** (Codex adapter) got the same treatment as its own
  §3e, since the status line already claimed it as complete while §4 still described
  it as a future sketch. §4's heading and the top status line now name exactly what's
  still actually backlog (5.4 partial, 5.6–5.8, 5.10–5.11) instead of a stale range.
- **D3 — DONE (2026-09-06).** PROTOCOL.md's WebSocket "Outbound" event list now
  includes `budget_updated {costBudgetUsd}`, `budget_exhausted {costBudgetUsd,
  costToDate}`, and `session_renamed {name, auto}`, with their exact payload shapes
  taken from `SessionService.java` (`record(...)` call sites) rather than guessed.
- **D4 — CLAUDE.md is current.** Run A already added the fast-unit-test command + CI
  description to "Build & test" (2026-09-06). Still true: a future run landing P1/P3
  (system-provider/model-catalog settings) needs the corresponding one-liners in the
  settings tables.

---

## Suggested pick order

1. **Run A (safety net first) — DONE 2026-09-06.** T1 + T5 + T7. Everything after this
   lands on green CI.
2. **Run B (docs) — DONE 2026-09-06.** D1–D4 — pure writing, no risk, immediately
   useful for the next Mac deploy. Turned up more ARCHITECTURE.md drift than D2
   originally scoped (three more stale "backlog" sections that were actually already
   shipped); fixed those too rather than leave a fresh inconsistency next to the edit.
3. **Run C (the big dedup) — DONE 2026-09-06.** G1 + G2 + G3 + G4, each landed with
   tests locking the extracted behavior in (`SystemTurnClientTest`, `PgVectorTest`,
   `EmbeddingClientTest`, plus a new `LibraryAiServiceTest` and updates to
   `TicketImportServiceTest`/`GitAssistServiceTest` for the `String`→`JsonNode`
   signature changes); P5 (SDK pin) rode along as a one-liner. Turned up two more
   duplicate copies the original review missed (`MemoryDocService.maybeEmbed` for G3,
   `OrchestrationMcpTools`'s journal-publish idiom for G4) — fixed those too. Existing
   files: +149/-344 lines, plus three new shared classes (`SystemTurnClient`/
   `PgVector`/`JournalPublisher`) and their tests; unit test count 54 → 74. Full
   `mvn`/`npm run build` (both sidecars) and `check-protocol-sync.mjs` all still green.
4. **Run D (provider decoupling) — DONE 2026-09-06.** P3 (`ModelCatalog` + capabilities
   `models`) → P1 (system session provider/tier, `session.system-provider` setting) →
   P2+O2 (auto-title via `runSystemTurn`) → P4 (cycleModel bug) + G5 (`useTicketImport`
   hook, `ModelSelect` component). New tests: `ModelCatalogTest`, `SettingsServiceTest`
   (tier-normalization/system-provider backward compat); `TicketImportServiceTest`
   updated for `parse`'s new `validModels` parameter. Unit test count 74 → 88. Full
   `mvn`/`npm run build` (both sidecars + frontend) and `check-protocol-sync.mjs` all
   green. Re-review S2's lock trade-off (auto-titling now shares it) once this sees
   real use.
5. **Run E (structural) — DONE 2026-09-06.** S1 (four new classes: `SystemSessionService`/
   `SessionConfigFactory`/`AutoTitleService`/`SessionHousekeeping`) → S2 (fair lock +
   `SystemTurnLane` wait budget, folded into the S1 extraction since it's the same
   class) → S3 (`SessionEntity.Builder`/`toBuilder()`) → S4 (`CreateOptions` record) →
   T2 (25 new `SessionStateMachineTest` cases + 4 `SystemSessionServiceRunTurnTest`
   cases, against hand-rolled in-memory fakes — including `FakeSidecar`, a real
   `SidecarHandle` over an in-memory `Process` subclass, so dispatch/queue-drain/
   terminate are exercised without Mockito or a spawned sidecar). T3/T4/T6 not picked
   (still valid backlog, `to taste`). `SessionService` 1,166 → 561 lines; unit test
   count 88 → 116. Full `mvn`/`npm run build` (both sidecars + frontend) and
   `check-protocol-sync.mjs` all green.

Items not picked stay valid backlog.
