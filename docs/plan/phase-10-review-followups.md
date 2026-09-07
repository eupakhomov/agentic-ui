# Phase 10 — Post-review follow-ups

Status: **R1 done (2026-09-07, Run D)**, rest not yet picked. Same shape as Phase 9: a curated
backlog from a fresh full-system read-through after Phase 9 Run F landed, not one feature
plan. Each item is self-contained with enough context to be picked up as its own run.
Security remains out of scope (LAN/single-user posture, decision 2026-08-23).

Review inputs: `SessionService`/`SystemSessionService`/`SessionConfigFactory`/
`SessionHousekeeping`/`AutoTitleService`, `SidecarManager`/`SidecarHandle`, `EventJournal`/
`SessionEventBus`/`SessionWebSocketHandler`, `SettingsService`, all controllers, both
sidecars' `session.ts`, the frontend `Dashboard`/`SessionWidget`/`store`/`api` layer,
`ARCHITECTURE.md` and the decision log.

Overall verdict from that review, for the record: the core design (journal as source of
truth, provider-neutral adapter protocol, one worktree per session, disposable sidecars)
holds up under scrutiny and is unusually well-documented; Phase 9 left it in good shape.
Everything below is second-order — one genuine remaining design seam (R1), a handful of
small races/leaks that are harmless for a single user, and consistency nits. Nothing here
is a structural problem, and none of it blocks normal use; that's why this is a follow-up
phase rather than a blocker on anything else.

Deliberately **not** included (decided 2026-09-06): splitting the three 500–650-line
frontend dialogs (`LibraryDialog`, `CreateSessionDialog`, `SettingsDialog`) and
`SessionWidget`'s 28 hooks. Not wrong for a dashboard, and `useTicketImport`/`ModelSelect`
already show the extraction pattern works — do it opportunistically when next touching
those files, not as a run of its own.

---

## 10.1 The remaining provider seam

- **R1 — DONE (2026-09-07, Run D).** Went with option (a) from the sketch below:
  `Capabilities` (both `protocol.ts` copies) gained `unsupportedSessionFields: string[]`,
  `contextDirs: boolean`, `reportsCostUsd: boolean`; a new `scripts/gen-provider-
  capabilities.mjs` runs as each package's `npm run build` postbuild step and writes a
  committed `<package>/capabilities.json` (package root, not the gitignored `dist/`) from
  the built `*_CAPABILITIES` const — CI verifies freshness with a plain `git diff
  --exit-code` after the build step, not an extension to `check-protocol-sync.mjs` as
  originally sketched (that script deliberately runs *before* `npm ci`/build for speed, so
  it has no `dist/` to regenerate from at that point — a `git diff` after the real build
  step is simpler and gives the same guarantee). Backend: new `ProviderCapabilities`
  record + `ProviderCatalog` (`session/ProviderCatalog.java`) resolve each provider's
  `capabilities.json` **lazily per id**, not eagerly at startup as sketched — a backend
  whose sidecar packages aren't all built yet still boots and serves the providers that
  are (`ProviderController.list()` logs + omits one that fails to load, rather than 500ing
  the whole endpoint). `SidecarManager.buildArgs`, `SessionConfigFactory.prepare`, and
  `SessionService.applyEstimatedCost` (renamed from `applyCodexCostEstimate`) all branch on
  `ProviderCapabilities` now, with zero provider-name checks. `SettingsService.codexPricing`
  became `pricingFor(provider)`/`setPricingFor(provider, json)` (key `<provider>.pricing`;
  `codex` is byte-identical to the old key, no migration) — the `DEFAULT_CODEX_PRICING` seed
  stays codex-specific real-world data (any other `reportsCostUsd: false` provider starts
  from `{}`, safe since `CodexCostEstimator.estimate` already treats missing rates as zero
  cost), and `SettingsController`'s DTO/Settings-dialog UI deliberately keeps its flat
  `codexPricing` field — generalizing the *UI* was out of scope (a backend plumbing fix, not
  a UI rework). Net effect: the DoD's grep is clean for the three original branch sites, but
  not literally zero `"codex"` in `src/main/java` — `SettingsService`/`SettingsController`
  keep a few, all in the pricing-seed/DTO-naming corner just described, none of it gating
  session creation or spawn behavior. New tests: `ProviderCatalogTest`,
  `SessionConfigFactoryTest`'s fabricated-`"widget"`-provider cases, `SidecarManagerTest`
  (new file — `buildArgs` widened from `private` to package-private for direct testing, same
  convention as `SessionConfigFactory`'s helpers). `docs/PROTOCOL.md`'s capabilities example
  and `docs/ARCHITECTURE.md` §3e updated; decision recorded in `docs/plan/README.md`.
  Original sketch, kept for context:

  `SidecarManager.buildArgs` (`process/SidecarManager.java:141`, gates seven CLI flags),
  `SessionConfigFactory.prepare` (`session/SessionConfigFactory.java:85`, the
  unsupported-field rejection cascade), and `SessionService.applyCodexCostEstimate`
  (`session/SessionService.java:423`, "this provider reports no USD, estimate it"). The
  docs already call the first two "defense-in-depth" and Phase 9's S1 flagged "capability
  declaration instead of an if-cascade" as valid backlog — this is that item. Today a
  third provider means editing three Java files plus writing its sidecar; the backend
  should need zero edits.

  **Sketch.** The adapter's `Capabilities` (already in both `protocol.ts` copies, already
  flows through the `ready` handshake into `session.capabilities`, already carries
  `models` since P3) gains the declarations the backend currently hardcodes per name:
  - `unsupportedSessionFields: string[]` — e.g. Codex declares
    `["allowedTools","disallowedTools","thinking","maxTurns","fallbackModel","agentSources"]`
    plus the two permission modes it lacks (already expressed as `permissionModes`), and
    a separate `contextDirs: false` flag for the "warn-and-drop" (not reject) case.
  - `reportsCostUsd: boolean` — Claude `true`, Codex `false`; when `false` the backend
    applies the price-table estimate (rename `applyCodexCostEstimate` →
    `applyEstimatedCost`, keyed on this flag, with `codex.pricing` becoming a per-provider
    setting key `<provider>.pricing` — Codex is the only entry today, so the migration is
    a rename).
  - `SidecarManager.buildArgs` passes every flag unconditionally and the *adapter* ignores
    what it doesn't support (sidecar-codex's `index.ts` arg parser already tolerates
    unknown flags? — verify; if not, that's a one-line `default:` case).

  Catch: `SessionConfigFactory.prepare` runs at *creation*, before any sidecar exists to
  announce capabilities. Two options — (a) a static per-provider capabilities file the
  backend reads at startup (`sidecar*/capabilities.json`, generated by each package's
  build from its `*_CAPABILITIES` const, so it can't drift — extend
  `scripts/check-protocol-sync.mjs` to assert it matches), or (b) `ModelCatalog`'s
  existing pattern: a backend-side `ProviderCatalog` mirrored "in spirit" with the TS
  consts. (a) is the honest fix (one source of truth); (b) is what P3 already chose for
  models and is cheaper. **Decide before starting; recommend (a)** since the whole point
  is removing backend knowledge of specific providers, and (b) just moves the string
  from three places to one. Land with a test that boots `SessionConfigFactory` against a
  fake provider declaring an arbitrary unsupported field and asserts rejection — proving
  no provider name appears in the path.

## 10.2 Correctness / robustness

- **R2–R6 — DONE (2026-09-07).** All five implemented as sketched below, plus one
  incidental fix found along the way while getting `mvn verify` green to confirm nothing
  regressed: `ProviderCatalog` (added in R1) has a public production constructor and a
  package-private test-only one, and with neither `@Autowired`, Spring couldn't apply its
  "exactly one constructor" auto-detection and silently fell back to a no-arg constructor
  that doesn't exist — `ApplicationTests.contextLoads` (and therefore the app itself) was
  broken since R1 landed, uncaught because CI never runs `@Tag("integration")` tests.
  Fixed with an explicit `@Autowired` on the production constructor
  (`session/ProviderCatalog.java`). New/changed tests: `AutoTitleServiceTest` (new),
  `MemoryMcpToolsTest` (new), `SidecarHandleTest` (new, R5's dead-on-arrival ordering
  proof), `EventJournalDbTest` (R3's `countEventType`/`firstEventOfType` + R4's
  `release`), `SessionStateMachineTest`'s new `closeReleasesTheSessionLock...` case (R4);
  `FakeEventJournal`/`FakeSidecar` updated to match (see each item below for specifics).
  `./mvnw test` (full, DB up, all 163 tests incl. `integration`) is green.

- **R2 — Search paths bypass `tryEmbed` and fail hard when Voyage is down.**
  `MemoryMcpTools.memorySearch` (`memory/MemoryMcpTools.java:73`),
  `MemoryController.search` (`web/MemoryController.java:82`), and
  `LibraryService.search` (`library/LibraryService.java`, O3's own code) all do
  `embeddings.configured() ? embeddings.embed(q, true) : null`. A present-but-failing key
  (rate limit, outage, the O5 401 case) therefore takes the *whole* search down — the
  agent's `memory_search` tool call errors, the dialog shows an error — instead of
  degrading to the sparse+trigram arms, which is the entire reason the hybrid search has
  them. `EmbeddingClient.tryEmbed` (G3) exists for exactly this. Fix: all three become
  `embeddings.tryEmbed(q, true)` (null → dense arm skipped; the O5 warning is the only
  symptom). Keep `LibraryService.maybeEmbed`'s direct `embed()` as-is (G3 deliberately
  left it — it surfaces the failure as a user-visible import warning). Test: a fake
  `EmbeddingClient` that throws from `embed()` → `MemoryMcpTools.memorySearch` still
  returns sparse hits.

- **R3 — `AutoTitleService` is the third copy of the G6 pattern, and reads the full
  journal every turn.** `session/AutoTitleService.java:58` hand-rolls
  `Thread.ofVirtual().start(() -> { try {...} catch (RuntimeException e) { log.debug } })`
  exactly what `FireAndForget.run` now centralizes — G6's own text said "only worth
  touching if a third copy appears", and this is it (it was missed because it logs at
  `debug`, not `warn`; decide whether auto-title failures should be `warn` like the other
  two — recommend yes, they're silent today). Separately, `:48` does
  `journal.readAfter(id, 0)` on **every** `turn_complete` of any session still carrying
  its branch name (i.e. forever, if the title turn failed once), only to count
  `turn_complete` rows and fetch the first `user_message`. That's a full-transcript read
  and JSON parse per turn. Fix: two targeted queries on `EventJournal` — `countEventType(
  sessionId, "turn_complete")` (sibling of the existing `hasEventType`) and
  `firstEventOfType(sessionId, "user_message")`; bail on the count before touching the
  journal further. Test: the count query with 3 turns; the auto-title path with a fake
  journal asserting `readAfter` is never called.

- **R4 — Two unbounded per-session maps.** `SessionService.locks` (`:46`, one `Object`
  per session id ever touched) and `EventJournal.sessions` (`:39`, one `PerSession` —
  a `long` + an empty `ArrayList` — per session ever journaled, removed only by
  `pruneAll`). ~100 bytes each, so a single user would need ~10k sessions to notice —
  but they're the kind of thing that's either bounded or documented, and today they're
  neither. Fix: `SessionService.close()` (both the system-session and user-session
  branches, after the final `CLOSED` transition) calls `locks.remove(id)` and a new
  `journal.release(id)` that drops the `PerSession` (flushing first — it's empty at
  that point anyway, but `release` should be safe to call at any time). `readAfter`/
  `lastSeq` after release just lazily `load()` again from `max(seq)`, which is what
  they do for a never-seen session already — no behavior change. Test: `close` → the
  journal's map no longer contains the id; a subsequent `readAfter` still returns the
  history.

- **R5 — `SidecarHandle` leaks `this` from its constructor.** `process/SidecarHandle.
  java:100` registers `process.onExit().thenAccept(p -> onExit.accept(this, ...))` inside
  the constructor, i.e. before `SidecarManager.spawn` has done `handles.put(id, handle)`
  (`SidecarManager.java:77`). If the process dies instantly (a syntax error in
  `dist/index.js`, a missing `node_modules`), the exit callback runs first:
  `handles.remove(id, h)` is a no-op, then `handles.put` inserts an already-dead handle.
  Functionally masked today — `hasLiveHandle`/`handle()` both check `isAlive()`, and
  `onSidecarExit` still correctly transitions to CRASHED — but it's an ordering that
  will bite the first time someone adds state to the handle. Fix: constructor only wires
  the reader threads; a package-private `start()` (or `watchExit()`) registers the exit
  hook, and `SidecarManager.spawn` calls it *after* `handles.put`. `FakeSidecar` in
  `SessionStateMachineTest` builds a real `SidecarHandle`, so it needs the same call —
  which is also the test: a `Process` fake that reports `isAlive() == false` immediately
  → `SidecarManager.handles` must not contain a dead entry after `spawn` returns.

- **R6 — `SystemSessionService.pendingSystemText` is an unsynchronized `StringBuilder`
  shared across threads.** Written by the sidecar stdout reader virtual thread
  (`onAssistantMessage`, `:194-196`) and reset by the caller thread (`runSystemTurn`,
  `:103`), read back on the reader thread (`completeTurn`, `:201`). It works because the
  fair lock serializes turns and the stdin-write → stdout-read pipe orders the two in
  practice, but the JMM doesn't formally promise visibility of the `setLength(0)`, and
  the two `volatile` neighbors (`pendingSystemTurn`, `pendingSystemTurnSessionId`) make it
  look deliberate when it's just an oversight. `onAssistantMessage` always *replaces*
  the whole text (never appends across events — "text of the **last** assistant_message
  in a turn"), so the `StringBuilder` buys nothing: make it a `private volatile String
  pendingSystemText = ""`. Simpler and obviously correct. No new test needed beyond
  `SystemSessionServiceRunTurnTest` still passing.

## 10.3 Efficiency

- **R7 — `SessionController.list()` is N+1.** `web/SessionController.java:80-84` runs
  `journal.costToDate(id)` and `journal.lastSeq(id)` per session — two round trips each,
  and `costToDate` also `flush()`es. Every dashboard load and every "Refresh" button
  press. Fine at 10 sessions, visible at a few hundred (sessions are never deleted, only
  CLOSED, so the list only grows — `findAll` has no state filter). Fix: one aggregate
  query in `EventJournal` — `Map<UUID, SessionStats> statsForAll()` doing
  `SELECT session_id, max(seq), coalesce(sum(costUsd) FILTER (WHERE type='turn_complete'),0)
  ... GROUP BY session_id` after a `flushAll()`, joined onto `findAll()` in the
  controller. Optional stretch: a `?includeClosed=false` param (the dashboard already
  filters CLOSED client-side in `Dashboard.tsx:60`). Test: a `*DbTest` with two sessions,
  three turns between them, asserting the aggregate matches the per-session calls.

## 10.4 Nits (one small cleanup run)

- **R8a — Inline fully-qualified class names.** ~11 spots use
  `org.springframework.context.ApplicationEventPublisher` (`SessionService.java:42,54`),
  `de.pamir.claude.ui.memory.ReflectionRequested`/`...discovery.ServiceDiscoveryRequested`
  (`:269,272`), `java.math.BigDecimal` (`:183,428`), `java.time.Duration`
  (`SessionHousekeeping.java:56`), `de.pamir.claude.ui.process.SidecarManager`
  (`SessionHousekeeping.java:35,41`), `java.util.stream.*` (`SidecarManager.java:165`,
  `SessionConfigFactory.java:336-339`), `java.util.HashMap` (`SessionController.java:90`),
  `org.springframework.web.bind.annotation.ResponseStatus/PatchMapping`
  (`SessionController.java:60,138,149`) instead of imports — leftovers from the S1 split
  and earlier quick edits. Pure mechanical: `grep -rn "de\.pamir\.claude\.ui\.[a-z]*\.[A-Z]\|java\.[a-z.]*\.[A-Z]\|org\.springframework\.[a-z.]*\.[A-Z]" src/main/java | grep -v import`
  should come back empty (minus javadoc `{@link}` targets, which are fine either way).
- **R8b — `console.debug` on every API call ships to production.** `frontend/src/api/
  rest.ts:36,58` logs every request/response; `useTicketImport` has timing logs. Gate
  behind `import.meta.env.DEV` (Vite inlines it, so `vite build` drops the calls) — keeps
  the diagnostics for `npm run dev`, silences the built jar.
- **R8c — `SettingsService` is a 25-pair flat getter/setter bag mirrored in
  `SettingsController`.** Every new setting is four edits (constant, getter, setter,
  controller view + patch). Works, but a typed `Settings` record (`SettingsService.
  current()` / `apply(Settings patch)`) with a single key→field table would halve it and
  make the frontend's `Settings` type derivable from one place. This is the largest item
  in the nits bucket and the only one with design room — do it last, and only if
  another setting is about to be added anyway; on its own it's churn.

## 10.5 Monorepo support (ecosystem folder == service repo)

- **M1 — The current model assumes one git repo per service, and breaks on a monorepo.**
  Today "ecosystem" = a folder whose *direct children are git repos* (`GitWorktreeService.
  findRepos`: `Files.exists(child/.git)`), and "service" = one of those repos. A session's
  `repoPath` is then both the thing `git worktree add` runs against **and** the service's
  identity: `memory_doc.service_path`/`memory_episode.service_path`/`MemoryPaths.serviceDir`
  are keyed on it, `service_profile.repo_path` is UNIQUE on it, `ServiceDigest.render` and
  the SHA-gated regeneration read it, `report_result` derives the service name from it,
  `list_services`/`find_service`/`list_discovered_services` scope on `findRepos(
  ecosystemPath)`, and the create dialog's picker is `GET /api/repo/services` = the same
  `findRepos`. A monorepo — one git repo whose `packages/*`/`services/*`/`apps/*` folders
  are the services, so the ecosystem folder *is* the service repo — currently yields:
  `findRepos(root)` returns nothing (no child has `.git`), so the picker only offers the
  configured default repo as one monolithic "service"; a session on it gets cwd = the whole
  monorepo; memory/discovery/orchestration all see exactly one service; and `--context-dir
  <ecosystemPath>` attaches the *original* checkout of the very repo the worktree was cut
  from as read-only context (a stale duplicate, not siblings).

  **Target behavior.** A service is a folder that identifies a unit of work; its git repo
  is wherever the nearest enclosing `.git` is. Polyrepo (today) is the special case where
  the two coincide. Concretely, in a monorepo: the picker lists `packages/foo`,
  `packages/bar`, …; a session on `packages/foo` gets a worktree of the *monorepo* on its
  own branch with **cwd = `<worktree>/packages/foo`** (so Claude Code's own CLAUDE.md
  walk-up picks up both the monorepo root's and the service's, and relative paths mean the
  service), the **whole worktree writable** (the git checkout is the natural write
  boundary — cross-package edits to a shared lib are the normal case in a monorepo, and
  the dirty-check/commit/PR flow already captures anything under the worktree), and the
  worktree root as its ecosystem context (not the original checkout). Memory, discovery
  and orchestration key on the service folder, so `packages/foo` and `packages/bar`
  accumulate separate memory, separate `service_profile` rows, and `spawn_child_session`
  on a sibling package works (another worktree of the same repo, different branch, cwd =
  that sibling's subfolder). Concurrent sessions on different packages of one monorepo =
  several worktrees of one repo, which git already allows and the session limit already
  counts.

  **Decisions to record before starting** (proposed answers):
  1. *Service identity* — introduce `servicePath` as the identity and demote `repoPath`
     to "git root for worktree ops". For polyrepo `servicePath == repoPath`, so every
     existing row is already correct under the new meaning: `memory_*.service_path` and
     `service_profile.repo_path` keep their columns (the latter renamed to `service_path`
     for honesty, plus a new `repo_path` for the git ops), `session` gains `service_path`
     (NULL = same as `repo_path`, so no data migration).
  2. *Write boundary* — the worktree root, not the service subfolder (rationale above).
     `readOnlyDenial` in `sidecar/src/permissions.ts` currently uses `cwd` as the root;
     it needs a separate `writableRoot` (new `--writable-root` flag, defaulting to cwd so
     nothing changes for polyrepo). Codex's sandbox already has exactly this knob —
     `sandboxPolicyFor` sets `writableRoots: []` (`sidecar-codex/src/session.ts:75`);
     it becomes `[writableRoot]`.
  3. *Ecosystem context in monorepo mode* — the session's own worktree root (`--context-dir
     <worktree>`), never the original checkout. Sibling *polyrepos* alongside a monorepo
     under the same ecosystem root (mixed layout) are still attached from their original
     checkouts as today.
  4. *Service detection inside a git repo* — manifest-driven first, glob fallback: npm/yarn
     `package.json#workspaces`, `pnpm-workspace.yaml`, Maven `<modules>`, Cargo
     `[workspace].members`, `go.work` `use` lines; else a persisted setting
     `ecosystem.monorepo-service-globs` (default `packages/*,services/*,apps/*,libs/*`)
     filtered to folders that contain a manifest (`package.json`/`pom.xml`/`pyproject.toml`/
     `go.mod`/`Cargo.toml`/`Dockerfile`). A repo with none of these is one service (= today).
  5. *Layouts supported* — (a) ecosystem root **is** the monorepo; (b) ecosystem root is a
     folder of repos, one or more of which is a monorepo (depth 2: `root/<repo>/packages/*`).
     Deeper nesting is out of scope.
  6. *Staleness gate for discovery* — `git log -1 --format=%H -- <servicePath>` (last commit
     touching that subtree) instead of `rev-parse HEAD`, otherwise every commit anywhere in
     the monorepo invalidates every service's profile. For polyrepo (`servicePath ==
     repoPath`) the two are equivalent, so this is safe to switch unconditionally.

  **Sketch, by layer** (each bullet is the actual edit site):
  - `git/GitWorktreeService`: `findRepos(root)` → `findServices(root)` returning
    `ServiceInfo(name, servicePath, repoPath)`; keep `findRepos` for the plain-repo
    listing internally. New `ServiceDetector` (own class, pure — `Path` in, list out; unit
    test with fixture trees for each manifest type + the glob fallback) does decision 4.
    New `lastCommitTouching(repo, subpath)` for decision 6.
  - `session/SessionEntity` + `SessionRepository` + V14 migration: `service_path` column
    (nullable), `Builder`/`mapRow`/`insert`; `SessionEntity.servicePath()` accessor returns
    `repoPath` when null so callers never branch. `SessionConfigFactory.prepare`: accept
    `servicePath` in `CreateOptions`/overrides, resolve `repoPath` by walking up to `.git`
    if the caller only gave the service folder (so `spawn_child_session` and the quick
    dialog can pass the service alone), validate `servicePath` is inside `repoPath`.
    `configOverridesFrom`/`lastSessionConfig`/`duplicate` carry it.
  - `SessionService.create`: worktree still `worktreeRoot/<id>` of `repoPath`; compute
    `cwd = worktree.resolve(repoPath.relativize(servicePath))` and persist it — either a
    `cwd_path` column or derive on every spawn (derive; it's two `Path` ops). Provisioning
    (`AssetProvisioningService`) materializes `.claude/skills|agents` at the **cwd**, not
    the worktree root, since that's the project root Claude Code's `settingSources:
    ['project']` resolves from (**verify live** that skills under `<worktree>/packages/foo/
    .claude/skills` are discovered with cwd there — if Claude Code only reads the git-root
    `.claude/`, materialize at the worktree root instead and this bullet becomes a one-line
    note). `excludeProvisionedAssets` writes the matching relative path into `info/exclude`
    either way. PID file stays at the worktree root (`SidecarManager.pidFile`) — the orphan
    sweep reads it from `session.worktreePath()`.
  - `process/SidecarManager.buildArgs`: `--cwd <cwd>`, new `--writable-root <worktree>`,
    `--context-dir` = worktree root in monorepo mode (decision 3) instead of
    `session.ecosystemPath()`; polyrepo unchanged. (If R1 lands first, `writableRoot` is
    just another flag the adapter ignores or honors — it doesn't need a capability, both
    adapters support it.)
  - `sidecar/src/permissions.ts`: `readOnlyDenial(toolName, input, cwd, writableRoot =
    cwd)`; `session.ts`/`index.ts` plumb the flag. `sidecar-codex/src/session.ts`:
    `writableRoots: [writableRoot]`, `thread/start.cwd` = the service cwd. Both
    `protocol.ts` copies unchanged (no event change) — `check-protocol-sync.mjs` stays
    green.
  - `memory/*`: every `session.repoPath()` used as a scope becomes `session.servicePath()`
    — `SessionConfigFactory.memorySystemPromptBlock` (`episodes.recentByService`),
    `ReflectionService` (`docs.findIndex`, `episodes.insert`, `proposals.insert`, `applyOp`'s
    `servicePath`), `MemoryMcpTools.repoPathOf` → `servicePathOf`, `MemoryController`'s
    `servicePath` param is already the right name. `MemoryPaths.serviceDir`'s slug is the
    folder's base name with the existing hash-suffix collision handling — `packages/foo` in
    two different monorepos collide on `foo` and get suffixed, which is the designed
    behavior, not a new case.
  - `discovery/*`: `service_profile` V14: rename `repo_path` → `service_path` (keep
    UNIQUE), add `repo_path`; `ServiceDiscoveryService.discover/rediscover/
    updateDescription` take `servicePath`, resolve `repoPath` for `currentCommitSha` →
    `lastCommitTouching`; `ServiceDigest.render(servicePath)` already works on any folder
    (README/manifest/listing relative to what it's given) — no change. `ServiceDiscoveryRequested`
    carries `servicePath`. `ServiceDiscoveryController.services()` and
    `ServiceDiscoveryMcpTools.visibleServicePaths` use `findServices`.
  - `session/OrchestrationMcpTools`: `list_services` → `findServices(ecosystemPath)`;
    `spawn_child_session`'s "not a git repository" check becomes "not a known service under
    this session's ecosystem" (walk-up resolves the repo; `defaultBranch` on the resolved
    repo); child inherits monorepo-mode context. `report_result`'s service name =
    `servicePath` base name — unchanged code, correct result.
  - `web/MetaController.services()`: `findServices`; `ServicesResponse` gains `repoPath`
    per service and a `monorepo` flag so the dialog can show it. `branches(repo)` is
    called with the *service's* `repoPath`, not `servicePath` — `CreateSessionDialog` line
    ~185 currently passes `repoPath` (the picker value), which becomes the service entry's
    `repoPath` field.
  - `frontend`: picker shows `name` (e.g. `packages/foo`) with a small "monorepo" chip;
    `overrides.servicePath` sent on create; `QuickSessionDialog` + `useTicketImport`
    pass it through; `SessionWidget`'s header/Exposé card show the service name (base
    name of `servicePath`) — today they show `repoPath`'s base name, which for a monorepo
    would be the repo, not the package. `GitPanel` unchanged (operates on the worktree).
  - `docs`: `ARCHITECTURE.md` §2's "Worktrees isolate work" invariant gains the monorepo
    sentence; `CLAUDE.md`'s ecosystem-root setting bullet + `DEPLOY.md` §7's Settings tour
    mention the globs setting; decision log entry.

  **Out of scope for this pass**: per-service default templates/branches (still 5.4),
  sparse checkout of just the service's subtree (worktrees of a large monorepo are full
  checkouts — acceptable, `git worktree add` shares objects; note it in DEPLOY.md),
  nesting deeper than depth 2, and Codex `skills/extraRoots/set` for a subfolder cwd
  (follows whatever the provisioning "verify live" bullet decides).

---

## Suggested pick order

1. **Run A (small correctness fixes) — DONE 2026-09-07.** R2 + R3 + R6 + R5, plus R4
   (originally slated for Run B, folded in since it touched the same files). See the R2–R6
   summary at the top of §10.2.
2. **Run B (bounded state + list query)** — R4 done above; R7 (the list-query
   optimization) is still open, its own run.
3. **Run C (nits)** — R8a + R8b, then R8c only if a new setting is on the table.
4. **Run D (provider seam) — DONE 2026-09-07.** R1, on its own, decision (a) recorded in
   `docs/plan/README.md`'s decision log first. Touched both sidecars' build (a new
   `capabilities.json` per package) and a documented contract (`Capabilities` gains three
   fields — `PROTOCOL.md` updated); the freshness guard ended up as a `git diff` CI step
   rather than a `check-protocol-sync.mjs` extension (see the R1 bullet above for why).
5. **Run E (monorepo)** — M1, on its own, after its six decisions are confirmed and
   written into the decision log. Independent of R1 (the new `--writable-root` flag needs
   no capability — both adapters honor it), but the "verify live" provisioning bullet
   should be settled first with a five-minute manual check on a real monorepo before any
   code is written, since it decides where `.claude/skills` gets materialized. Suggested
   internal order: `ServiceDetector` + tests → V14 + entity/repo → `SessionConfigFactory`/
   `SessionService` cwd → sidecar flags → memory/discovery/orchestration scoping →
   frontend → docs.

Items not picked stay valid backlog.

## Definition of Done

- **R1 — done.** `grep -rn '"codex"' src/main/java --include=*.java | grep -v test` is clean
  for the three original branch sites (`SidecarManager.buildArgs`,
  `SessionConfigFactory.prepare`, `SessionService`'s renamed `applyEstimatedCost`) — the
  remaining hits are `ModelCatalog` (excluded, as before) plus `SettingsService`/
  `SettingsController`'s pricing-seed/DTO-naming corner (deliberately out of scope — see
  the R1 bullet above). `SessionConfigFactoryTest` has a fabricated provider id (not
  `claude`/`codex`) that declares `maxTurns` unsupported and rejects a session setting it
  (`prepareRejectsAFieldTheFabricatedProviderDeclaresUnsupported`), plus the accepting
  counterpart. `PROTOCOL.md`'s capabilities table lists the new fields; both
  `*_CAPABILITIES` consts declare them; `check-protocol-sync.mjs` passes (unaffected by
  R1 — the new freshness guard is a separate CI step, not part of that script).
- **R2**: With `CLAUDE_UI_VOYAGE_API_KEY` set to garbage, memory search in the dialog,
  `memory_search` from inside a session, and library search all still return sparse/
  trigram hits (and one `Voyage API key rejected (401)` warning per call in the log)
  instead of an error.
- **R3**: `AutoTitleService` uses `FireAndForget`; `grep -n "readAfter" AutoTitleService.
  java` is empty; a session whose title turn fails logs at `warn`, not `debug`.
- **R4**: After `close`, neither `SessionService.locks` nor `EventJournal.sessions`
  contains the id (asserted via a package-private accessor in the state-machine test);
  `GET /api/sessions/{id}/events` on a closed session still returns its history.
- **R5**: `SidecarHandle` has no `process.onExit()` call in its constructor;
  `SidecarManager.spawn` of a process that's dead on arrival leaves `handles` empty.
- **R6**: `pendingSystemText` is a `volatile String`; `SystemSessionServiceRunTurnTest`
  passes unchanged.
- **R7**: `GET /api/sessions` issues one journal query regardless of session count
  (verify with `logging.level.org.springframework.jdbc.core=DEBUG` — one `SELECT ...
  GROUP BY session_id`, not 2N); the new `*DbTest` passes.
- **R8a/b**: the FQN grep above is empty; `npm run build` output contains no
  `console.debug` string; `npm run dev` still logs API calls.
- **M1**: with `ecosystem.root` pointed at a monorepo, `GET /api/repo/services` lists
  each workspace package (name = its relative path, `repoPath` = the monorepo root,
  `monorepo: true`); creating a session on `packages/foo` yields a worktree of the monorepo
  on the new branch with the sidecar's cwd at `<worktree>/packages/foo` (visible in the
  "sidecar pid … spawned (… --cwd …)" log line); an `Edit` on `<worktree>/packages/bar/
  x.ts` from that session is **allowed** (write boundary = worktree), an `Edit` on the
  original checkout's path is auto-denied; the session's memory episodes land under
  `memory.root/services/foo/`, not `<monorepo-name>/`; `find_service` from a session on
  `packages/bar` returns `packages/foo`'s profile; `spawn_child_session` on `packages/bar`
  from a `packages/foo` session works and the child's `report_result` names the service
  `bar`. A polyrepo ecosystem behaves byte-for-byte as before (`servicePath == repoPath`,
  `--writable-root == --cwd`, context dir = the original ecosystem folder), and every
  pre-existing `session`/`memory_*`/`service_profile` row is valid without a data
  migration. `ServiceDetectorTest` covers each manifest type + the glob fallback + "a repo
  with no workspace markers is one service".
- All existing suites green: `./mvnw test` (incl. `integration`), `npm test` +
  `npm run build` in `frontend`/`sidecar`/`sidecar-codex`, `check-protocol-sync.mjs`.

## Manual test script

1. **R2** — export `CLAUDE_UI_VOYAGE_API_KEY=invalid`, start the backend. Open the memory
   dialog, search for a term you know is in a memory doc's title → results appear; log
   shows one `Voyage API key rejected (401)` warning. Repeat in the library dialog with
   the semantic toggle on. In a live session, ask the agent to `memory_search` for the
   same term → tool returns hits, no error card.
2. **R3** — create a session, send one message, let the turn finish → session is
   auto-titled as before. Temporarily set `session.system-provider` to a provider with no
   sidecar built (so the title turn fails) → `logs/claude-ui.log` shows a `WARN`
   `auto-title failed`, and sending three more messages doesn't produce three more
   full-journal reads (SQL debug logging shows no `SELECT ... FROM session_event WHERE
   session_id = ? AND seq > 0` per turn — only the count query).
3. **R5** — `mv sidecar/dist/index.js sidecar/dist/index.js.bak`, create a session →
   it goes CRASHED with the stderr tail in the error card (same as today); restore the
   file, Resume → works. Nothing new user-visible; the assertion is the unit test.
4. **R7** — with 20+ sessions in the DB (CLOSED ones count), open the dashboard with
   JDBC debug logging on → one aggregate query for the list, not 40.
5. **R8b** — `npm run build`, serve the jar, open devtools console on the dashboard →
   no `[claude-ui] api request` lines. `npm run dev` → they're back.
6. **R1** — create a `codex` session with `maxTurns` set → still rejected at creation
   with the same message as today; create a `claude` session with every field set →
   still spawns with the same CLI args (compare `logs/claude-ui.log`'s "sidecar pid …
   spawned (…)" line before/after). Codex turn costs still show estimated USD in the
   usage dashboard.
7. **M1** — set up a throwaway monorepo (`git init mono && cd mono && npm init -y &&
   npm pkg set 'workspaces[]=packages/*' && mkdir -p packages/foo packages/bar && (cd
   packages/foo && npm init -y) && (cd packages/bar && npm init -y) && git add -A && git
   commit -m init`) and point Settings → Sessions → ecosystem root at it. Open New
   Session → the service picker shows `packages/foo` and `packages/bar` with a monorepo
   chip; pick `foo`, branch `feat-x`. In the session: ask the agent to `pwd` → ends in
   `packages/foo`; ask it to create `packages/bar/hello.txt` → allowed, appears in the Git
   panel as dirty; ask it to edit `<original mono path>/packages/foo/package.json` →
   auto-denied error card. Close with "commit" → the commit lands on `feat-x` in the
   monorepo. Enable reflection, close a second session → `memory.root/services/foo/`
   exists (not `services/mono/`). Rediscover from the service dialog → two profiles,
   one per package, each description mentioning only its own package. Then edit only
   `packages/bar/README.md`, commit, Rediscover → only `bar`'s profile regenerates (log
   shows one system turn). Finally repoint the ecosystem root at a polyrepo folder →
   everything behaves exactly as before this phase.
