# Phase 11 — Monorepo support (ecosystem folder == service repo)

Status: **landed (2026-09-12) — Steps 0-7 all done, DoD green (`./mvnw test` incl. integration, both sidecars' `npm test`/`tsc`, frontend `npm test`/`npm run build`, `check-protocol-sync.mjs`, `grep -rn "findRepos" src/main/java` clean), full manual test pass green** (extracted from `phase-10-review-followups.md`
§10.5 on 2026-09-07 — it was the one item there big enough to be its own phase; the phase-10
doc now only points here). Verified against the code as of Phase 10 R8c; every edit site named
below was checked to exist as described (re-verified 2026-09-11, no drift found beyond a
handful of stale line-number citations — see the implementation plan's "corrections" section).

Today "ecosystem" = a folder whose *direct children are git repos*
(`GitWorktreeService.findRepos`: `Files.exists(child/.git)`), and "service" = one of those
repos. A session's `repoPath` is then both the thing `git worktree add` runs against **and**
the service's identity: `memory_doc.service_path` / `memory_episode.service_path` /
`MemoryPaths.serviceDir` are keyed on it, `service_profile.repo_path` is UNIQUE on it,
`ServiceDigest.render` and the SHA-gated regeneration read it, `report_result` derives the
service name from it, `list_services` / `find_service` / `list_discovered_services` scope on
`findRepos(ecosystemPath)`, and the create dialog's picker is `GET /api/repo/services` = the
same `findRepos`.

A monorepo — one git repo whose `packages/*` / `services/*` / `apps/*` folders are the
services, so the ecosystem folder *is* the service repo — currently yields: `findRepos(root)`
returns nothing (no child has `.git`), so the picker only offers the configured default repo
as one monolithic "service" (`MetaController.services()`'s fallback); a session on it gets
cwd = the whole monorepo; memory/discovery/orchestration all see exactly one service; and
`--context-dir <ecosystemPath>` attaches the *original* checkout of the very repo the
worktree was cut from as read-only context (a stale duplicate, not siblings).

## Target behavior

A **service** is a folder that identifies a unit of work; its **git repo** is wherever the
nearest enclosing `.git` is. Polyrepo (today) is the special case where the two coincide.
Concretely, in a monorepo:

- The picker lists `packages/foo`, `packages/bar`, ….
- A session on `packages/foo` gets a worktree of the *monorepo* on its own branch with
  **cwd = `<worktree>/packages/foo`** — so Claude Code's own CLAUDE.md walk-up picks up both
  the monorepo root's and the service's, and relative paths mean the service.
- The **whole worktree is writable**. The git checkout is the natural write boundary:
  cross-package edits to a shared lib are the normal case in a monorepo, and the
  dirty-check / commit / PR flow already captures anything under the worktree.
- The worktree root is the session's ecosystem context (not the original checkout).
- Memory, discovery and orchestration key on the service folder, so `packages/foo` and
  `packages/bar` accumulate separate memory, separate `service_profile` rows, and
  `spawn_child_session` on a sibling package works (another worktree of the same repo,
  different branch, cwd = that sibling's subfolder).
- Concurrent sessions on different packages of one monorepo = several worktrees of one
  repo, which git already allows and the session limit already counts.
- A **polyrepo ecosystem behaves byte-for-byte as before**, and every pre-existing
  `session` / `memory_*` / `service_profile` row is valid without a data migration.

## Decisions (confirm before starting; write into `docs/plan/README.md`'s log)

1. **Service identity** — introduce `servicePath` as the identity and demote `repoPath` to
   "git root for worktree ops". For polyrepo `servicePath == repoPath`, so every existing row
   is already correct under the new meaning: `memory_*.service_path` keeps its column (it was
   always called that — only the *value* it's fed changes), `service_profile.repo_path` is
   renamed to `service_path` for honesty plus a new nullable `repo_path` for the git ops,
   `session` gains a nullable `service_path` (NULL = same as `repo_path`, so no data
   migration). `SessionEntity.servicePath()` resolves the NULL so callers never branch.
2. **Write boundary** — the worktree root, not the service subfolder (rationale above).
   `readOnlyDenial` in `sidecar/src/permissions.ts` currently uses `cwd` as the root; it
   gets a separate `writableRoot` (new `--writable-root` flag, defaulting to cwd so nothing
   changes for polyrepo). Codex's sandbox already has exactly this knob — `sandboxPolicyFor`
   sets `writableRoots: []` (`sidecar-codex/src/session.ts:75`); it becomes `[writableRoot]`.
   **Both** arg parsers must learn the flag: `sidecar-codex/src/index.ts` treats an unknown
   flag as a usage error (`default: usage()`), it does not ignore it.
3. **Ecosystem context in monorepo mode** — the session's own worktree root is passed as a
   `--context-dir`, never the original checkout. This is not only for reading siblings: the
   Claude sidecar maps `--context-dir` to the SDK's `additionalDirectories`
   (`sidecar/src/session.ts:264`), which is what lets `acceptEdits` mode apply an edit to
   `<worktree>/packages/bar/x.ts` from a cwd of `<worktree>/packages/foo` without a prompt —
   without it, decision 2's "whole worktree writable" would be policed correctly by
   `readOnlyDenial` but still prompt on every cross-package edit. Layout rules:
   - layout (a), ecosystem root **is** the monorepo: `--context-dir <worktree>` replaces
     `--context-dir <ecosystemPath>` entirely (the latter would be the stale duplicate).
   - layout (b), ecosystem root is a folder of repos and this session's repo is a monorepo
     inside it: `--context-dir <worktree>` **plus** `--context-dir <ecosystemPath>` as today,
     so sibling polyrepos stay readable from their original checkouts. The ecosystem folder
     then also contains this repo's own original checkout — that stale duplicate already
     exists for every polyrepo session today (`--context-dir <ecosystemRoot>` includes the
     session's own repo) and is accepted; not worth per-sibling enumeration to avoid.
   - Codex (`contextDirs: false` in its capabilities) gets neither, as today — its
     `workspaceWrite` sandbox with `writableRoots: [worktree]` covers the write side, and
     reads were never restricted.
4. **Service detection inside a git repo** — manifest-driven first, glob fallback (full spec
   in Step 1): npm/yarn `package.json#workspaces`, `pnpm-workspace.yaml`, Maven `<modules>`,
   Gradle `settings.gradle(.kts)` `include(...)`, Cargo `[workspace].members`, `go.work`
   `use` lines; else a persisted setting `ecosystem.monorepo-service-globs` (default
   `packages/*,services/*,apps/*,libs/*`) filtered to folders that contain a manifest. A repo
   with none of these is one service (= today).
5. **Layouts supported** — (a) ecosystem root **is** the monorepo; (b) ecosystem root is a
   folder of repos, one or more of which is a monorepo (depth 2: `root/<repo>/packages/*`).
   Deeper nesting is out of scope. Mixed (b) — some children polyrepos, some monorepos — is
   the same code path.
6. **Staleness gate for discovery** — `git log -1 --format=%H -- <relative service path>`
   (last commit touching that subtree, run from the repo root) instead of `rev-parse HEAD`;
   otherwise every commit anywhere in the monorepo invalidates every service's profile. For
   polyrepo the subtree is `.` and the two are equivalent, so this is switched
   unconditionally — no branch on "is monorepo".
7. **Service name** — `ServiceInfo.name` is the path relative to the *ecosystem root*, forward
   slashes: polyrepo `foo` (= today's basename), layout (a) `packages/foo`, layout (b)
   `mono/packages/foo`. One rule, and today's names are unchanged. Everything that wants a
   *short* name (`report_result`'s `service`, the widget chip, the memory slug,
   `ServiceDigest`'s name) uses the basename of `servicePath`, as today — `packages/foo` in
   two different monorepos collide on `foo` in the memory vault and get `MemoryPaths`'
   existing hash-suffix, which is the designed behavior, not a new case.
8. **Skill/agent materialization target — decision A (cwd)**, resolved by the Step 0 spike on
   2026-09-11. `claude -p --setting-sources project 'List the skills available to you by name,
   nothing else.'` run from `<mono>/packages/foo` (with `root-skill` at `<mono>/.claude/skills/`
   and `pkg-skill` at `<mono>/packages/foo/.claude/skills/`) printed:
   `pkg-skill, root-skill, dataviz, update-config, ...` — **both** project skills were listed,
   which is the spec's "decision A" outcome (only `pkg-skill`, or both). So: `AssetProvisioningService.provision`
   materializes at the **service cwd** (`<worktree>/packages/foo/.claude/`), not the worktree
   root; `excludeProvisionedAssets` writes the worktree-relative `packages/foo/.claude/skills/`
   line (not the bare `.claude/skills/` it writes today). Codex needs no change — it already
   derives `skillsDir` from `config.cwd` (`sidecar-codex/src/session.ts:384-387`), which under
   decision A is already the correct (service) directory. The spike was not repeated for Codex
   since its extra-roots resolution is deterministic code (`join(config.cwd, '.claude',
   'skills')`), not an ambiguous walk-up, and cwd already matches decision A.

## Steps

Each step lands on its own, leaves polyrepo behavior byte-identical, and keeps every suite
green — that's a standing DoD line for all of them, not repeated below. Suggested order is
the numbering; Steps 1 and 2 are independent of each other, everything else stacks.

### Step 0 — Spike: where does a subfolder-cwd session find its skills?

Provisioning (`AssetProvisioningService.provision(worktree, …)`) materializes
`.claude/skills` and `.claude/agents` at the **worktree root** today
(`worktree.resolve(".claude/skills")`, `:49-50`), and the Claude sidecar runs with
`settingSources: ['project']` (`sidecar/src/session.ts:251`). With cwd moved to
`<worktree>/packages/foo`, it is not documented whether Claude Code resolves project skills
from cwd's `.claude/`, from the git root's, or both — and this decides where Step 3
materializes them. Codex has the same question in a different shape: it discovers skills only
via `skills/extraRoots/set` pointed at `join(config.cwd, '.claude', 'skills')`
(`sidecar-codex/src/session.ts:384-387`), so it follows cwd unless told otherwise.

Do this by hand, before any code:

```bash
git init /tmp/mono && cd /tmp/mono && npm init -y >/dev/null \
  && npm pkg set 'workspaces[]=packages/*' && mkdir -p packages/foo && (cd packages/foo && npm init -y >/dev/null)
mkdir -p .claude/skills/root-skill packages/foo/.claude/skills/pkg-skill
printf -- '---\nname: root-skill\ndescription: at git root\n---\nsay ROOT\n' > .claude/skills/root-skill/SKILL.md
printf -- '---\nname: pkg-skill\ndescription: at package\n---\nsay PKG\n' > packages/foo/.claude/skills/pkg-skill/SKILL.md
git add -A && git commit -qm init
cd packages/foo && claude -p --setting-sources project 'List the skills available to you by name, nothing else.'
```

Outcomes → decision **A** (only `pkg-skill` listed, or both): materialize at the **cwd**
(`<worktree>/packages/foo/.claude/`), Codex needs no change. Decision **B** (only
`root-skill`): keep materializing at the worktree root, and Codex's `extraRoots` must point at
`join(writableRoot, '.claude', 'skills')` instead of cwd (Step 4 carries that). Either way
`excludeProvisionedAssets` (`SessionService.java:532`) must write the matching
*worktree-relative* paths into `info/exclude` — today it hardcodes `.claude/skills/`, which
under A would be `packages/foo/.claude/skills/`.

Repeat once for `codex` (`codex` CLI from `packages/foo`, with `.claude/skills` registered
as an extra root the way the adapter does) only if A/B differ for the two providers — the
adapters may legitimately need different placements, and that's fine as long as
`AssetProvisioningService` takes the target dir as a parameter (it already takes
`worktree`; it becomes `assetsRoot`).

**DoD.** The outcome (A or B, per provider) is written as decision 8 in this doc's list and
in the decision log, with the exact CLI output pasted into the decision-log row. Nothing
else changes.

### Step 1 — `ServiceDetector` + `GitWorktreeService.findServices` (pure, no callers yet)

New `git/ServiceDetector` — pure, `Path repoRoot` + `List<String> fallbackGlobs` in,
`List<Path>` of service folders (absolute, sorted) out. No Spring, no git, unit-testable
with fixture trees under `@TempDir`. Detection order, first match wins:

| Manifest at repo root | What's parsed | Notes |
|---|---|---|
| `package.json` | `workspaces` — a JSON array, or `{"packages": [...]}` (yarn) | Jackson, already on the classpath |
| `pnpm-workspace.yaml` | `packages:` list items (`- 'glob'`), line-based | no YAML lib; tolerate quotes/comments; `!`-prefixed = exclusion |
| `pom.xml` | `<module>x</module>` entries | regex, no XML parser; a module is a dir (or a path to a pom — take its parent) |
| `settings.gradle` / `settings.gradle.kts` | `include(":a", ":b:c")` / `include ':a'` | `:a:b` → `a/b`; honor `project(':a').projectDir = file('x')` only if trivially parseable, else ignore |
| `Cargo.toml` | `[workspace]` `members = [...]` (may be multi-line), `exclude = [...]` | line-based TOML subset |
| `go.work` | `use ./a` and `use ( … )` blocks | |
| none of the above | `fallbackGlobs` (from the setting), keep only folders containing a manifest: `package.json` / `pom.xml` / `build.gradle(.kts)` / `pyproject.toml` / `go.mod` / `Cargo.toml` / `Dockerfile` | |

Glob semantics, deliberately minimal: a pattern is a `/`-separated relative path whose
segments are literal or `*` (matches one directory level); `**` is treated as `*`;
`!pattern` removes matches. A matched folder is dropped if it isn't a directory, is hidden
(`.`-prefixed), or is `node_modules`/`target`/`build`/`dist`. **Submodule edge:** a matched
folder that itself contains `.git` (a file, for submodules — `Files.exists` covers both) is
its own repo, not a service of this one; `findServices` reports it with `repoPath` = that
folder (polyrepo-style). A manifest that parses to zero folders falls through to the next
row; a manifest that fails to parse logs at `debug` and falls through. A repo where every
row yields nothing is **one service** = the repo root.

`GitWorktreeService` gains:

- `record ServiceInfo(String name, String servicePath, String repoPath)` and
  `List<ServiceInfo> findServices(Path ecosystemRoot, List<String> fallbackGlobs)`:
  if `ecosystemRoot/.git` exists → layout (a): `ServiceDetector` on the root, each result a
  service with `repoPath = root`. Else for each direct child (sorted, as `findRepos` does):
  child has `.git` → `ServiceDetector` on it (one or many services, `repoPath = child`);
  child has no `.git` → skipped, as today. `name` per decision 7. `findRepos` stays as-is
  (still used internally and by callers not migrated until Step 5).
- `Path repoRootOf(Path servicePath)` — walk up until a `.git` exists; `Optional.empty()`
  if none before the filesystem root.
- `String lastCommitTouching(Path repo, Path servicePath)` — `git log -1 --format=%H --
  <repo.relativize(servicePath) or ".">`, null on failure or an empty repo (keeps
  `ServiceDiscoveryService`'s "null SHA always regenerates" contract).

New setting `ecosystem.monorepo-service-globs` (string, comma-separated, default
`packages/*,services/*,apps/*,libs/*`) — with R8c this is: one component on
`Settings`/`SettingsPatch`(+`Builder`), one `strField` row in `SettingsService`, one line in
the frontend `Settings` interface, one text input in `SettingsDialog` → "Sessions" next to
the ecosystem root. `SettingsService` exposes nothing extra; callers split on `,` and trim.

**DoD.** `ServiceDetectorTest` covers: each manifest row with a fixture tree (npm array +
yarn object forms, pnpm with an exclusion, Maven, Gradle `include`, Cargo multi-line
`members`, `go.work` block form), the glob fallback with the manifest filter dropping an
empty folder, a submodule child reported as its own repo, and "no markers → one service =
root". `GitWorktreeServiceTest` (new, `@TempDir` + real `git init`) covers `findServices`
on layouts (a), (b) and polyrepo — asserting a polyrepo root returns exactly what
`findRepos` returns today, name for name — plus `repoRootOf` from a nested folder and from a
non-repo folder, and `lastCommitTouching` returning a different SHA for two packages after a
commit that touched only one. Settings round-trip: `PATCH /api/settings` with
`monorepoServiceGlobs` persists and comes back. No production caller of `findServices` yet;
`./mvnw test` and `npm test`/`npm run build` in `frontend` green.

### Step 2 — Schema + entity (`V14`, `service_path` everywhere it's identity)

`V14__monorepo_service_path.sql`:

```sql
ALTER TABLE session ADD COLUMN service_path TEXT;            -- NULL = same as repo_path
ALTER TABLE service_profile RENAME COLUMN repo_path TO service_path;  -- UNIQUE constraint follows the column
ALTER TABLE service_profile ADD COLUMN repo_path TEXT;       -- git root; NULL = same as service_path
```

`memory_doc.service_path` / `memory_episode.service_path` / `memory_proposal.service_path`
are untouched — they were always the identity column, only their meaning widens.

- `session/SessionEntity`: `servicePath` component (nullable in storage), `Builder.
  servicePath(...)`, and two derived accessors so no caller ever branches:
  `servicePath()` → `service_path` or `repoPath` when null; `cwdPath()` →
  `worktreePath` when `servicePath()` equals `repoPath`, else
  `Path.of(worktreePath).resolve(Path.of(repoPath).relativize(Path.of(servicePath())))`.
  (Derived on every call, not persisted — it's two `Path` ops.) Jackson serializes both
  accessors, so the REST `SessionEntity` and the frontend type gain `servicePath` and
  `cwdPath` for free.
- `session/SessionRepository`: `insert` column list + params, `mapRow` (`rs.getString(
  "service_path")`). Nothing else writes the row's identity.
- `discovery/ServiceProfileRepository`: `ServiceProfile.repoPath` → `servicePath` plus a
  new `repoPath` component; `SELECT` column list, `findByRepoPath` → `findByServicePath`,
  `findVisible(List<String> servicePaths)`, `upsert(servicePath, repoPath, …)` and its
  `ON CONFLICT (service_path)`, `bumpDiscoveredAt`, `upsertEmbedding`, and `hybridSearch`'s
  `pathFilter` (`service_path = ANY(...)`) — a rename with one added column, no logic
  change. Callers (`ServiceDiscoveryService`, `ServiceDiscoveryController`,
  `ServiceDiscoveryMcpTools`) pass what they passed before (which is the service path under
  the new meaning) and a null `repoPath` until Step 5 fills it.
- `web/SessionController.SessionSummary`: add `servicePath` (resolved, never null). The
  frontend ignores unknown fields, so this is safe ahead of Step 6.

**DoD.** Flyway applies V14 on a DB with existing sessions/profiles/memory rows and
`ApplicationTests.contextLoads` passes against it. `SessionRepositoryDbTest`: insert with
`servicePath = null` → `servicePath()` returns `repoPath` and `cwdPath()` returns
`worktreePath`; insert with `servicePath = <repoPath>/packages/foo` → `cwdPath()` is
`<worktreePath>/packages/foo`. `ServiceProfileRepository`'s `*DbTest` (extend the existing
one) round-trips `repoPath` and the renamed lookup. `GET /api/sessions` and
`GET /api/sessions/{id}` show `servicePath == repoPath` for every pre-existing session. No
behavior change anywhere — every write site still stores NULL.

### Step 3 — Session creation on a service subfolder (backend + picker API)

- `session/SessionService.CreateOptions`: add `servicePath` (nullable). `web/SessionController.
  CreateSessionRequest`: add `servicePath`; `DuplicateSessionRequest` unchanged (duplicate
  copies the source's).
- `session/SessionConfigFactory.prepare`: resolution order — if `servicePath` given: resolve
  `repoPath` by `worktrees.repoRootOf(servicePath)` when `repoPath` is blank (so
  `spawn_child_session` and the quick dialog can pass the service alone), else validate
  the given `repoPath` **is** that root; reject (`IllegalArgumentException`, surfaces as
  400) when `servicePath` is not inside a git repo, or is not a known service — i.e. not in
  `findServices(ecosystemPath, globs)` and not the repo root itself. If only `repoPath`
  given: today's path exactly (`servicePath` stays null in storage). `configOverridesFrom`
  (`:352`) carries `servicePath` so `duplicate` / `lastSessionConfig` reproduce it.
- `session/SessionService.create`: worktree is still `worktreeRoot/<id>` of `entity.
  repoPath()` — `syncBaseBranch` / `createWorktree` / `removeWorktree` (`close`) all keep
  using `repoPath`, unchanged. After `createWorktree`: `excludeProvisionedAssets(worktree,
  assetsRoot)` writes `<rel>/.claude/skills/`, `<rel>/.claude/agents/` (where `<rel>` is
  the assets root relative to the worktree — empty for polyrepo and decision B, so the
  written lines are byte-identical to today's), plus `.claude-ui.pid` as before;
  `assets.provision(assetsRoot, …)` with `assetsRoot` = `entity.cwdPath()` under decision
  A or the worktree under B. PID file stays at the worktree root (`SidecarManager.pidFile`
  reads `session.worktreePath()`; the orphan sweep in `MaintenanceController` is
  unaffected).
- `web/MetaController.services()`: `findServices(ecosystemRoot, globs)`; `ServiceInfo`
  gains `repoPath` and `monorepo` (`!servicePath.equals(repoPath)`); the configured
  default-repo fallback (`:45-48`) is kept and itself goes through `ServiceDetector` (a
  default repo that is a monorepo lists its packages). `branches(repo)` is unchanged —
  the dialog must call it with the service's `repoPath`, not `servicePath` (Step 6).

**DoD.** `SessionConfigFactoryTest`: `servicePath` alone → `repoPath` resolved via a fake
`repoRootOf`; `servicePath` outside any repo → rejected; `servicePath` under a repo but not a
known service → rejected; `repoPath` alone → entity `servicePath()` equals `repoPath` and
overrides from `configOverridesFrom` contain no `servicePath` key (proving polyrepo config
is byte-identical). `SessionStateMachineTest`'s `FakeGitWorktreeService` grows
`findServices`/`repoRootOf`; a new case creates a session with `servicePath = <repo>/
packages/foo` and asserts `provision` was called with `<worktree>/packages/foo` (A) or
`<worktree>` (B), and the `info/exclude` content. `GET /api/repo/services` against the
manual-test monorepo (below) lists `packages/foo` / `packages/bar` with `monorepo: true`
and `repoPath` = the root; against a polyrepo folder the response is identical to before
except for the two added fields. `POST /api/sessions` with `servicePath` creates a worktree
of the monorepo whose `cwdPath` ends in `packages/foo` (visible in the entity) — the sidecar
still starts at the worktree root at this step (Step 4 moves it), which is fine for the
polyrepo case and merely "wrong cwd" for the monorepo case until the next step lands.

### Step 4 — Sidecars: `--writable-root`, cwd, context dirs

- `sidecar/src/index.ts`: `--writable-root <dir>` → `config.writableRoot` (default: cwd).
  `sidecar/src/permissions.ts`: `readOnlyDenial(toolName, input, cwd, writableRoot = cwd)`
  — the root check uses `writableRoot`, the relative-path resolution still uses `cwd`; the
  denial message names the writable root. `sidecar/src/session.ts:271` passes both.
- `sidecar-codex/src/index.ts`: same flag (explicitly — see decision 2); `sidecar-codex/src/
  session.ts`: `sandboxPolicyFor` → `writableRoots: [config.writableRoot]`; `thread/start.
  cwd` stays `config.cwd` (which is now the service cwd — correct); under decision B,
  `skills/extraRoots/set` uses `join(config.writableRoot, '.claude', 'skills')`.
  Both `protocol.ts` copies unchanged (no event/command change) — `check-protocol-sync.mjs`
  stays green. Update each `usage()` string.
- `process/SidecarManager.buildArgs`: `--cwd s.cwdPath()`, `--writable-root
  s.worktreePath()` unconditionally (both adapters honor it; no capability needed — but
  note it in both `capabilities.json` docs only if a third adapter ever needs to opt out);
  under `caps.contextDirs()`: layout (a) — `s.servicePath()` differs from `s.repoPath()`
  **and** `s.ecosystemPath()` equals `s.repoPath()` — emit `--context-dir <worktree>`
  instead of the ecosystem path; layout (b) — service differs from repo, ecosystem is
  something else — emit both; polyrepo — unchanged. `ProcessBuilder.directory` stays the
  worktree (the PID file and `git` commands assume it).

**DoD.** `sidecar` vitest: `readOnlyDenial` allows `<worktree>/packages/bar/x.ts` with
cwd `<worktree>/packages/foo` and `writableRoot = <worktree>`; denies `<orig>/packages/foo/
x.ts`; with `writableRoot` omitted behaves exactly as the existing tests assert.
`sidecar-codex` vitest: `sandboxPolicyFor` carries the root. `SidecarManagerTest.buildArgs`:
polyrepo session → the arg list equals today's plus exactly `--writable-root <worktree>`
(assert by removing that pair and comparing to the pre-phase expectation); layout (a) →
`--cwd <worktree>/packages/foo`, `--context-dir <worktree>`, no `--context-dir
<ecosystem>`; layout (b) → both context dirs; codex provider → no `--context-dir`, still
`--writable-root`. `npm test` + `tsc` in both sidecars, `check-protocol-sync.mjs` green.
Live: the monorepo session's "sidecar pid … spawned (…)" log line shows `--cwd …/packages/
foo --writable-root …/<id>`.

### Step 5 — Scope memory, discovery and orchestration on `servicePath`

- `memory/*` — every `session.repoPath()` used as a *scope* becomes `session.servicePath()`:
  `SessionConfigFactory.memorySystemPromptBlock` (`episodes.recentByService`, `:307`),
  `ReflectionService` (`docs.findIndex` `:134`, `proposals.insert` `:154`,
  `episodes.insert` `:168`, `applyOp`'s `servicePath` `:202`, the prompt text `:273`),
  `MemoryMcpTools.repoPathOf` → `servicePathOf` (`:108`). `MemoryController`'s
  `servicePath` param is already the right name. `MemoryPaths`' javadoc says "session.
  repoPath" — reword to "servicePath"; the code is unchanged (marker file + hash-suffix
  collision handling already do the right thing, decision 7).
- `discovery/*` — `ServiceDiscoveryRequested(sessionId, servicePath, repoPath)`;
  `SessionService.close` (`:281`) publishes `session.servicePath()` / `session.repoPath()`.
  `ServiceDiscoveryService.discover` / `rediscover` / `updateDescription`: the three
  `Files.exists(path.resolve(".git"))` checks become "resolves to a repo via `repoRootOf`
  and is a known service or a repo root"; `currentCommitSha(path)` → `lastCommitTouching(
  repoRoot, servicePath)` (decision 6); `profiles.upsert(servicePath, repoRoot, …)`.
  `generate`'s `ServiceDigest.render(path)` already works on any folder (README / manifest
  sniff / shallow listing relative to what it's given) — no change; its `name` is the
  basename, per decision 7. `ServiceDiscoveryController.services()` and
  `ServiceDiscoveryMcpTools.visiblePaths` use `findServices(...)` and map `servicePath`;
  the controller's `ServiceView.repoPath` is renamed `servicePath` (frontend follows in
  Step 6 — until then the dialog shows `undefined` for that one field, acceptable for one
  step or ship 5+6 together).
- `session/OrchestrationMcpTools` — `listServices` → `findServices(ecosystemPath, globs)`
  (returns `ServiceInfo`, tool description updated: "services (git repos or monorepo
  packages)"); `spawnChildSession`'s parameter is already named `servicePath`: its "not a
  git repository" check becomes "not a known service under this session's ecosystem", the
  resolved repo root feeds `defaultBranch(repoRoot)`, and `CreateOptions` gets both
  `repoPath = repoRoot` and `servicePath`; the child inherits `ecosystemPath` as today.
  `checkChildren`'s `ChildInfo.servicePath` (already so named) is filled from
  `c.servicePath()`; `reportResult`'s `service` (`:148`) from `child.servicePath()` — same
  basename code, now the package name.

**DoD.** `MemoryMcpToolsTest`: a session with `servicePath = …/packages/foo` searches memory
scoped to that path, not the repo root. `ServiceDiscoveryService` test (new, fake git +
fake profiles): two services in one repo — a commit touching only `packages/bar` leaves
`foo`'s profile at `bumpDiscoveredAt` (SHA unchanged) and regenerates `bar`'s; rediscover
on a folder that isn't a known service is rejected. `OrchestrationMcpTools` test (new or
extend `SessionStateMachineTest`): `spawn_child_session` on `packages/bar` from a
`packages/foo` session resolves `repoPath` to the monorepo root and the child's
`report_result` payload names the service `bar`. Live on the manual-test monorepo: a
reflected session's episodes land under `memory.root/services/foo/` (not
`services/mono/`); `find_service` from a session on `packages/bar` returns `packages/foo`'s
profile; Rediscover from the service dialog yields two profiles, each description
mentioning only its own package.

### Step 6 — Frontend

- `protocol.ts`: `ServiceInfo` gains `repoPath`, `monorepo`; `SessionEntity` /
  `SessionSummary` gain `servicePath` (and `cwdPath` on the entity); `ServiceProfileView.
  repoPath` → `servicePath`; `Settings.monorepoServiceGlobs` (already added in Step 1).
  `api/rest.ts`: `serviceDiscoveryRediscover` / `serviceDiscoveryUpdate` send
  `servicePath`; `createSession` sends `servicePath`.
- `CreateSessionDialog`: the picker's option label is `s.name` (decision 7) with a small
  "monorepo" chip when `s.monorepo`; the selected value carries the whole `ServiceInfo` so
  the create request sends `repoPath` **and** `servicePath` and `api.branches(...)` (`:185`)
  is called with the service's `repoPath` (today it's called with the picker value, which
  would be the subfolder — `MetaController.branches` rejects it as "not a git repository").
  The `title` on the ecosystem field (`:421`) gains "…; in a monorepo the session's own
  worktree is attached instead". `QuickSessionDialog` has its own picker (`:112`) and
  `api.branches(repoPath)` (`:71`) — same change. `useTicketImport` does **not** touch the
  service selection (verified — it only produces `branchName`/`prompt`), no change there.
- Service-name chips: `SessionWidget.tsx:225-227`, `ExposeOverlay.tsx:62`,
  `ContinuationPickerDialog.tsx:77` show `repoPath.split('/').pop()` — switch to
  `servicePath`. `store/store.ts:32/48` (`repoPath` on the widget view) gains
  `servicePath`. `GitPanel` is unchanged (operates on the worktree).
- `ServiceDiscoveryDialog` (or whatever renders `ServiceProfileView`): field rename only.
- `SettingsDialog` → "Sessions": the globs input (Step 1), with the default shown as
  placeholder.

**DoD.** `npm test` + `npm run build` green; the picker on the manual-test monorepo shows
`packages/foo` / `packages/bar` with the chip and the branch dropdown populates (i.e.
`branches` was called with the root); creating from both dialogs sends `servicePath`
(check the request in devtools); the widget header, Exposé card and continuation picker
show `foo`, not `mono`; a polyrepo ecosystem's picker, chips and requests are visually and
byte-for-byte as before (the request simply has `servicePath == repoPath`).

### Step 7 — Docs + decision log

`docs/ARCHITECTURE.md` §2 "Worktrees isolate work" gains the monorepo sentence (service
subfolder as cwd, whole worktree writable, worktree root as context) and §3d's discovery
notes mention `lastCommitTouching`; `CLAUDE.md`'s "Ecosystem root" settings bullet
(`:339`) and `docs/DEPLOY.md` §7's settings tour (`:133`) mention monorepo detection + the
globs setting; DEPLOY.md's worktree note says a monorepo worktree is a full checkout
(`git worktree add` shares objects, so disk cost is the working tree only); the decision
log gets one "Phase 11 landed" row summarizing decisions 1–8; `docs/plan/README.md`'s
phases table row 11 outcome column filled in; this doc's status line flipped.

**DoD.** `grep -rn "findRepos" src/main/java` shows only `GitWorktreeService` itself and
`findServices`' internal use — every enumeration goes through services now. All
suites green: `./mvnw test` (incl. `integration`), `npm test` + `npm run build` in
`frontend` / `sidecar` / `sidecar-codex`, `check-protocol-sync.mjs`.

## Out of scope for this phase

- Per-service default templates/branches (still 5.4).
- Sparse checkout of just the service's subtree — worktrees of a large monorepo are full
  checkouts. Acceptable; `git worktree add` shares objects, only the working tree costs disk.
- Nesting deeper than depth 2 (`root/<repo>/packages/*`), and workspaces-inside-workspaces.
- Per-sibling context-dir enumeration to avoid the stale-duplicate in layout (b) (decision 3).
- Nx / Turborepo / Lerna project graphs — they sit on top of `package.json` workspaces,
  which is already covered; `lerna.json#packages` can be a one-row addition to the
  `ServiceDetector` table later if someone needs it.

## Definition of Done (whole phase)

- Every step's DoD above holds.
- With `ecosystem.root` pointed at a monorepo, `GET /api/repo/services` lists each workspace
  package (name = its relative path, `repoPath` = the monorepo root, `monorepo: true`);
  creating a session on `packages/foo` yields a worktree of the monorepo on the new branch
  with the sidecar's cwd at `<worktree>/packages/foo` (visible in the "sidecar pid … spawned
  (… --cwd …)" log line); an `Edit` on `<worktree>/packages/bar/x.ts` from that session is
  **allowed** (write boundary = worktree) and, in `acceptEdits` mode, applied without a
  prompt; an `Edit` on the original checkout's path is auto-denied; the session's memory
  episodes land under `memory.root/services/foo/`, not `<monorepo-name>/`; `find_service`
  from a session on `packages/bar` returns `packages/foo`'s profile; `spawn_child_session`
  on `packages/bar` from a `packages/foo` session works and the child's `report_result`
  names the service `bar`.
- A polyrepo ecosystem behaves byte-for-byte as before (`servicePath == repoPath`,
  `--writable-root == <worktree>` with `--cwd == <worktree>`, context dir = the original
  ecosystem folder, `info/exclude` content identical), and every pre-existing `session` /
  `memory_*` / `service_profile` row is valid without a data migration.
- `ServiceDetectorTest` covers each manifest type + the glob fallback + "a repo with no
  workspace markers is one service".
- All existing suites green: `./mvnw test` (incl. `integration`), `npm test` + `npm run
  build` in `frontend` / `sidecar` / `sidecar-codex`, `check-protocol-sync.mjs`.

## Manual test script

1. Set up a throwaway monorepo:
   ```bash
   git init /tmp/mono && cd /tmp/mono && npm init -y >/dev/null \
     && npm pkg set 'workspaces[]=packages/*' \
     && mkdir -p packages/foo packages/bar \
     && (cd packages/foo && npm init -y >/dev/null) && (cd packages/bar && npm init -y >/dev/null) \
     && git add -A && git commit -qm init
   ```
   Point Settings → Sessions → ecosystem root at `/tmp/mono`.
2. **New Session** → the service picker shows `packages/foo` and `packages/bar` with a
   monorepo chip; pick `foo`, branch `feat-x` → the branch dropdown lists `main`
   (`branches` was called with the root).
3. In the session: ask the agent to run `pwd` → ends in `packages/foo`. Ask it to create
   `packages/bar/hello.txt` → allowed, appears in the Git panel as dirty. Switch the mode
   chip to `acceptEdits` and ask for another edit under `packages/bar` → applied with no
   prompt. Ask it to edit `/tmp/mono/packages/foo/package.json` (the original checkout) →
   auto-denied error card naming the worktree as the writable root.
4. Close with "commit" → the commit lands on `feat-x` in `/tmp/mono` (`git -C /tmp/mono
   log feat-x -1`).
5. Enable reflection, run a short second session on `foo`, close it, approve the proposal →
   `memory.root/services/foo/` exists (not `services/mono/`).
6. Service dialog → Rediscover → two profiles, one per package, each description mentioning
   only its own package. Then `echo x >> /tmp/mono/packages/bar/README.md && git -C /tmp/mono
   commit -qam bar` → Rediscover → only `bar`'s profile regenerates (log shows one system
   turn; `foo`'s `discoveredAt` bumps without a turn).
7. From a session on `packages/foo`, ask the agent to `list_services` → both packages; to
   `spawn_child_session` on `packages/bar` → a second worktree of `/tmp/mono` appears under
   the worktree root with cwd `packages/bar`; the child's `report_result` shows
   `[child report — … / bar]` in the parent.
8. Layout (b): `mkdir /tmp/eco && mv /tmp/mono /tmp/eco/ && git init /tmp/eco/solo && (cd
   /tmp/eco/solo && git commit --allow-empty -qm init)`; repoint the ecosystem root at
   `/tmp/eco` → the picker shows `solo`, `mono/packages/foo`, `mono/packages/bar`; a
   session on `mono/packages/foo` logs both `--context-dir` values; a session on `solo`
   logs exactly what it logged before this phase.
9. Finally repoint the ecosystem root at a real polyrepo folder → picker, chips, spawn log
   line and `info/exclude` are exactly as before this phase.
