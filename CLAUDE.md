# claude-ui — Claude Code Multi-Session Manager

Spring Boot backend + React dashboard for running parallel Claude Code sessions, each
in its own git worktree, driven through per-session Node sidecar processes (Claude
Agent SDK). Single user, LAN deployment, PostgreSQL persistence.

## Repository layout

```
├─ src/                    # Spring Boot backend (Java 25, Boot 4.x, package de.pamir.claude.ui)
│  └─ main/resources/db/migration/   # Flyway migrations — the ONLY way schema changes
├─ sidecar/                # Node + TypeScript session engine (Claude Agent SDK) — Phase 1
├─ sidecar-codex/          # Codex CLI provider adapter (codex app-server JSON-RPC) — Phase 5.13
├─ frontend/               # Vite + React dashboard — Phase 3
├─ docker-compose.yaml     # PostgreSQL 17 (pgvector image)
├─ docs/
│  ├─ plan/                # Phase plans; README.md holds the AUTHORITATIVE decision log
│  ├─ PROTOCOL.md          # Sidecar/WS message contracts
│  ├─ ARCHITECTURE.md      # As-built architecture + backlog implementation sketches
│  └─ DEPLOY.md            # Deploying on another machine (macOS) — prereqs, run, update
└─ CLAUDE.md               # this file
```

Work proceeds phase by phase (`docs/plan/phase-*.md`). Each phase has a Definition of
Done checklist and a manual test script; a phase starts only after the previous one's
DoD fully passes. Check `docs/plan/README.md` (decision log) before questioning any
architectural choice — most have been explicitly decided.

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 25 (Temurin) | WSL: installed at `~/.jdks/jdk-25.0.4.1+1`, exported in `~/.bashrc`. IntelliJ manages its own JDK 25 separately |
| Node | ≥ 22 LTS | via nvm (`~/.nvm`); needed by `sidecar/` and `frontend/`, not by the `claude` CLI (native binary) |
| Docker | any recent | Postgres runs in Docker Desktop (Windows). WSL integration may be OFF — see DB section |
| claude CLI | logged in | sidecars use the invoking user's `~/.claude` credentials |
| codex CLI | logged in (optional) | only needed for `provider: codex` sessions; sidecar-codex uses the invoking user's `~/.codex` credentials (`codex login` once, interactively) — same posture as the `claude` CLI row above |

## Build & test (CLI)

**WSL/Windows:**

```bash
# make sure JDK 25 is active (a login shell picks this up from ~/.bashrc)
export JAVA_HOME="$HOME/.jdks/jdk-25.0.4.1+1"; export PATH="$JAVA_HOME/bin:$PATH"
./mvnw -v                     # must report Java 25

./mvnw clean verify           # full build + tests — REQUIRES Postgres running (see below)
./mvnw clean verify -DskipTests   # compile-only, no DB needed
```

Gotcha (bash): `export A=x PATH=$A/bin:$PATH` in ONE statement expands `$A` before the
assignment takes effect — export `JAVA_HOME` and `PATH` as two statements.

**macOS:** JDK 25 and Maven are typically already on `PATH` (e.g. via sdkman/brew), and
`mvnw` loses its executable bit across some git checkouts (`git ls-files -s mvnw` shows
`100644`) — use the system `mvn` instead of `./mvnw`:

```bash
mvn -v                        # must report Java 25

mvn clean verify              # full build + tests — REQUIRES Postgres running (see below)
mvn clean verify -DskipTests  # compile-only, no DB needed
```

## Database

```bash
docker compose up -d          # if WSL integration is enabled
# Fallback when the docker CLI is unavailable in WSL (integration off):
"/mnt/c/Program Files/Docker/Docker/resources/bin/docker.exe" compose -f 'D:\projects\claude-ui\docker-compose.yaml' up -d
```

- DB `claude_ui`, user `claude_ui`, password `claude_ui` (dev default), port
  `127.0.0.1:5432`. Docker Desktop relays the published port into WSL, so
  `localhost:5432` works from both Windows and WSL.
- Password override env var is `CLAUDE_UI_DB_PASSWORD` — deliberately namespaced:
  the user's `~/.bashrc` exports an unrelated `DB_PASSWORD`; never use that name.
- **Schema changes go through Flyway only** (`src/main/resources/db/migration/V*__*.sql`,
  applied at startup). No manual DDL, no generated schema. Boot 4 note: Flyway needs
  `spring-boot-starter-flyway`; plain `flyway-core` silently does nothing.
- **`docker compose down` keeps the `pgdata` volume** (only `down -v` removes it) — editing
  or renaming an already-applied migration file during dev iteration then hits Flyway's
  checksum-mismatch validation error on next startup, since the old content is still
  recorded in `flyway_schema_history` on that persisted volume. If a migration you're
  actively iterating on hasn't shipped/been committed yet, `docker compose down -v` before
  the next `up -d` is the easy reset; don't do this once a migration is real/committed.

## Run the project

```bash
# 1. Postgres must be up (see Database section), then build the jar.
#    IMPORTANT: stop a running backend first — a live JVM holds the jar and the
#    spring-boot repackage fails half-written.
# WSL/Windows: use ./mvnw. macOS: use mvn (see "Build & test" above for why).
mvnw="./mvnw"; command -v mvn >/dev/null && [ "$(uname)" = "Darwin" ] && mvnw="mvn"
$mvnw package -DskipTests -Dskip.installnodenpm -Dskip.npm   # fast: reuses frontend/dist
$mvnw package -DskipTests                                    # full: rebuilds frontend too
# (run `cd frontend && npm run build` first if frontend sources changed and you use the fast form)

# 2. Generate a token, start in background, print the token for the browser login:
TOKEN=$(head -c 24 /dev/urandom | base64 | tr -dc 'a-zA-Z0-9' | head -c 20)
echo "$TOKEN" > /tmp/claude-ui.token
CLAUDE_UI_TOKEN="$TOKEN" nohup java -jar target/claude.ui-0.0.1-SNAPSHOT.jar \
  --server.address=0.0.0.0 > /tmp/claude-ui.log 2>&1 &
echo $! > /tmp/claude-ui.pid
until curl -sf localhost:8080/actuator/health >/dev/null; do sleep 1; done
echo "UI: http://localhost:8080  token: $(cat /tmp/claude-ui.token)"
```

- **WSL gotcha**: bind `0.0.0.0`, not `127.0.0.1` — the Windows→WSL localhost relay
  only forwards wildcard binds, so a loopback-bound server is invisible to the
  Windows browser (ERR_CONNECTION_REFUSED). The startup guard therefore requires a
  token (`CLAUDE_UI_TOKEN`); tokenless is only allowed on `127.0.0.1`.
- Logs: `tail -f /tmp/claude-ui.log` for raw stdout; structured logs land in `logs/`
  (override dir with env `CLAUDE_UI_LOG_DIR`):
  - `logs/claude-ui.log` — everything INFO+, rolled daily/10MB, 14 days kept
  - `logs/error.log` — ERROR only with full stack traces, 30 days kept
  - `logs/sidecar/<sessionId>.log` — each session's sidecar stderr (timestamped)
- Token again: `cat /tmp/claude-ui.token`.

### Stop / kill

```bash
kill "$(cat /tmp/claude-ui.pid)"          # graceful: @PreDestroy shuts sidecars down
# fallback when the pid file is stale — kill whatever listens on 8080:
kill "$(ss -tlnp | grep 8080 | grep -o 'pid=[0-9]*' | head -1 | cut -d= -f2)"
# orphaned sidecars, if any survive:
pkill -f "dist/index[.]js --cwd"
```

Note the `[.]` in the pkill pattern: `pkill -f` matches its own shell's command line,
so an unescaped pattern kills the shell that runs it (learned the hard way).

Config lives in `application.yaml` under `claude-ui.*` (default repo path, worktree
root, skills root, max sessions, auth token, provider launch commands), bound by
`de.pamir.claude.ui.config.AppProperties` and logged at startup (token masked). Service
repo and ecosystem path are selectable **per session** in the create dialog; the config
values are only defaults (the ecosystem root default itself is a persisted setting, not
env-based — see below). Local secrets belong in gitignored `application-local.yaml` or
env vars — never commit them. Provider launch commands (`claude-ui.providers.<id>.command`)
ship two entries out of the box:
```yaml
claude-ui:
  providers:
    claude:
      command: ["node", "sidecar/dist/index.js"]
    codex:
      command: ["node", "sidecar-codex/dist/index.js"]
```
Session/template `provider` (default from the persisted `session.default-provider`
setting, Settings dialog → "Sessions") selects which entry `SidecarManager` spawns.

## Sidecar (Phase 1+)

```bash
cd sidecar && npm install && npm run build    # tsc build to dist/
npm run drive -- --cwd /path/to/dir           # manual REPL driver for the NDJSON protocol
```

One sidecar process per session; stdout is protocol NDJSON only, logs go to stderr.
The NDJSON contract is the provider adapter interface — keep it provider-neutral
(`providerSessionId`, capabilities handshake), Claude specifics stay inside the sidecar.

## Codex provider adapter (Phase 5.13)

```bash
cd sidecar-codex && npm install && npm run build   # tsc build to dist/
```

Second adapter implementation, wrapping `codex app-server`'s JSON-RPC-over-stdio
protocol (not `codex exec`, which is non-interactive and can't do the tool-approval
round trip) — translated to the same NDJSON adapter protocol v1 the Claude sidecar
speaks. `sidecar-codex/src/protocol.ts`/`stdio.ts` are synced copies of `sidecar/`'s
shared, provider-neutral types (a plain copy, not a cross-package import, to avoid
coupling the two packages' build order — keep both in sync if protocol v1 changes).
Narrower capability set than Claude's: no plan mode, no `acceptEdits`, no custom
agents (`agentSources`) — Codex has no equivalent to Claude's static subagent files at
all, confirmed permanently infeasible, not just deferred. An explicit `plan`/
`acceptEdits` permission mode or a non-empty `agentSources` on a `codex` session is
rejected at creation time, not silently downgraded. Skills and MCP **are** supported
(as of the 2026-08-30 follow-up): skills via `skills/extraRoots/set` pointing at the
worktree's already-materialized `.claude/skills/` (Codex reads the same `SKILL.md`
format), MCP via `thread/start`'s `config.mcp_servers` (thread-scoped, not the global
`codex mcp add` registration) — `sidecar-codex/src/mcp.ts` translates the same
Claude-shaped `mcpConfig` file the backend already writes, passing any bearer token as
a named env var on the spawned child (Codex's own auth mechanism) rather than an
inline header. Codex reports token counts but no per-turn USD, so `costUsd` is
estimated backend-side (`SessionService.applyCodexCostEstimate`) from a
Settings-editable price table (Settings dialog → "Codex" → Pricing). Full design
rationale, the capability/permission-mode mapping, and
live-confirmed protocol quirks: `docs/plan/phase-5.13-codex-provider.md`.

## Frontend (Phase 3+)

```bash
cd frontend && npm install && npm run dev     # Vite on :5173, proxies /api and /ws to :8080
```

Production build is wired into `mvn package` (frontend-maven-plugin → `static/`), so
the backend jar serves everything at http://localhost:8080/.

**Build speed**: the FIRST `mvn package` downloads a Node distro into `target/` and
runs `npm install` — slow on /mnt/d (DrvFS), expect ~10+ min. Later builds are fast.
To skip the frontend rebuild entirely (reuses `frontend/dist`):
`./mvnw package -DskipTests -Dskip.installnodenpm -Dskip.npm`

**Vite dev-server watcher gotcha (WSL + DrvFS)**: `npm run dev`'s file watcher (chokidar/
inotify) does not reliably see edits to files on `/mnt/d` — Vite keeps serving its
in-memory transformed copy of a file indefinitely after the *first* request for it,
silently ignoring later on-disk changes (no error, no HMR log). Symptom: an edit that
provably typechecks and is on disk has *zero* effect in the browser, even after a hard
reload. Fix: kill and restart the `npm run dev` process after editing frontend source
while manually verifying in a browser — don't trust HMR here. (`mvn package`'s frontend
build is unaffected; it always reads fresh from disk.)

## Limits & caps

All operational limits are env-tunable (read at backend startup; the sidecar inherits
the backend's environment):

| Env var | Default | What it caps |
|---|---|---|
| `CLAUDE_UI_MAX_SESSIONS` | `4` | Concurrent live sidecar processes; create/resume beyond it → 409 (including 7.4's `spawn_child_session`, surfaced to the parent as a tool error). PARKED sessions don't count, so a parent that parks after spawning fans out wider than the raw count suggests — raise this for wide fan-outs |
| `CLAUDE_UI_IDLE_PARK_MINUTES` | `30` | Minutes a session may sit IDLE before its sidecar is shut down (PARKED); next message transparently wakes it |
| `CLAUDE_UI_TOOL_OUTPUT_LIMIT` | `16384` | Bytes of tool output kept per result (sidecar truncates, `truncated` flag set) |
| `CLAUDE_UI_JOURNAL_PAYLOAD_CAP` | `65536` | Max bytes for one journal event payload; larger payloads stored as a truncated preview |
| `CLAUDE_UI_WS_BUFFER_LIMIT` | `1048576` | Per-client WS outbound buffer; a slow consumer overflowing it is disconnected (reconnects + replays losslessly) |
| `CLAUDE_UI_LOG_DIR` | `logs` | Log directory (backend rolling logs + per-session sidecar stderr) |
| `CLAUDE_UI_TOKEN` | — | Dashboard/API auth token (required for non-loopback binds) |
| `CLAUDE_UI_REPO` | `/mnt/d/projects/claude-ui` | Default service repo (per-session selectable in the UI) |
| `CLAUDE_UI_WORKTREE_ROOT` | `~/claude-worktrees` | Where session worktrees live |
| `CLAUDE_UI_SKILLS_ROOT` | `~/claude-skills` | *Default* for the managed skills root, which is now a persisted setting (`library.skills-root`) — the create-dialog picker, provisioning's repo cache, and library imports all read the setting |
| `CLAUDE_UI_MEMORY_ROOT` | `~/claude-memory` | *Default* for the managed semantic-memory root (`memory.root` persisted setting) — an Obsidian-compatible vault of Markdown files with YAML frontmatter; the DB is a rebuildable search index over it, not the source of truth |
| `CLAUDE_UI_VOYAGE_API_KEY` | — | Voyage AI API key (a secret — env var only); enables the library's "vectorize" setting + semantic search (`VoyageEmbeddingClient`, voyage-3.5-lite, pgvector) **and** memory's dense-search arm. Unset = those features fall back to sparse-only (Postgres FTS + trigram for memory), everything else works |
| `CLAUDE_UI_LINEAR_API_KEY` | — | Linear personal API key (a secret — env var only, never persisted); enables "Import ticket" in the create dialog (fetches a ticket via Linear's MCP server on the singleton system session, generates branch name + kickoff prompt via Haiku). When configured (or the OAuth toggle is on), the Linear MCP server is also layered by default into every regular session's `mcpConfig` (`SessionService.linearMcpServer()`/`withDefaultLinearMcp()`), so the agent can read/update tickets directly — unless the session's own `mcpConfig` already declares its own `linear` entry, which wins. Regular sessions go through the normal permission-approval flow for its tools (the system session pre-approves them instead, since backend-initiated turns have nobody to answer a prompt). |

**Per-session limits** (create dialog / template / `PATCH /api/sessions/{id}`, not env):
`costBudgetUsd` (turns are refused once cumulative cost reaches it; in-flight turns
finish; raise via the widget's cost chip), `maxTurns` (agentic turns per prompt),
`thinking` budget and `effort` level, `reflectionEnabled` (opt-in end-of-session memory
retrospective — see "Long-term memory" below; the widget's 🧠 button triggers one
manually regardless of this flag).

**Permission modes** (create dialog, or click the widget's mode chip to cycle at
runtime): `default` (ask for edits & commands), `acceptEdits`, `plan`, and
`bypassPermissions` — the last skips **every** approval prompt, Bash included, with
no per-session safety net of our own (the worktree-only `readOnlyDenial` check in
`sidecar/src/permissions.ts` lives inside the `canUseTool` callback, which the
underlying CLI does not invoke at all in this mode — `allowDangerouslySkipPermissions`
is its own explicit opt-in, set in `sidecar/src/session.ts` only when this mode is
selected). Use only for sessions you already fully trust.

**Persisted settings** (Settings dialog; `app_setting` table, `SettingsService`/
`SettingsController` — `GET`/`PATCH /api/settings`): non-secret, UI-editable, take
effect on the next use with no backend restart.
- **Ecosystem root** (Settings dialog → "Sessions") — default read-only context folder
  + service discovery root (parent of all sibling services); empty = no default wider
  context. Overridable per session in the create dialog (`ecosystemPath`, `null` = no
  wider context for that session). Replaces the old `CLAUDE_UI_ECOSYSTEM_ROOT` env var.
- **OAuth toggle** (Settings dialog → "Linear integration") — alternative to
  `CLAUDE_UI_LINEAR_API_KEY` for SSO-gated Linear accounts (e.g. Google identity): omits
  the Authorization header, relying on the ambient `claude` CLI's own cached OAuth
  credential for `mcp.linear.app` — run
  `claude mcp add --transport http linear https://mcp.linear.app/mcp` once,
  interactively, on the backend host first. Ignored if `CLAUDE_UI_LINEAR_API_KEY` is set.
- **Branch-naming guidance** (Settings dialog → "Linear integration") — free text
  appended to the Haiku prompt that generates a ticket import's `branchName`/`prompt`,
  e.g. "keep the ticket number uppercase" or "format as feat(TICKET)-description /
  fix(TICKET)-description".
- **PR checks** (Settings dialog → "PR checks") — `enabled` (default on) and
  `poll-interval-seconds` (default 180, floor 30) for the background GitHub PR CI
  poller (`PrCheckPollingService`, ticks every 30s and re-reads both settings each
  time, so changes take effect on the next tick). Uses the ambient `gh` CLI auth
  already used for PR creation — no separate token. One PR tracked per session
  (`session.pr_url`/`pr_check_status`/`pr_head_sha`/`pr_checked_at`); polling stops
  once a terminal result (`SUCCESS`/`FAILURE`/`MERGED`/`CLOSED`) is seen and re-arms
  on the next push to that branch. Status changes journal a `pr_status_changed` event
  (see docs/PROTOCOL.md) that drives the Git panel's status pill and a desktop
  notification via the same unfocused-tab `notify()` helper used for turn completion.
- **Skill library** (Settings dialog → "Skill library"; feature docs:
  `docs/plan/phase-6-skill-library.md`, `docs/ARCHITECTURE.md` §3a) —
  `library.skills-root` / `library.agents-root` (managed import destinations;
  skills root defaults to `CLAUDE_UI_SKILLS_ROOT`), `library.vectorize` (default
  off; needs the Voyage key), `library.sync-enabled` (default on) and
  `library.sync-interval-minutes` (default 60, floor 5) for the background source
  sync (`LibrarySyncService`, ticks every 60s, interval as cutoff — PR-checks
  pattern). The 📚 dashboard dialog scans a local folder or GitHub repo (via `gh`,
  GitHub-only for now), imports skills/agents with metadata + tags (AI-fill via the
  Haiku system session), and synced sources auto-update/archive assets and surface
  new upstream files as badge + desktop notification.
- **Long-term memory** (Settings dialog → "Memory"; feature docs:
  `docs/plan/phase-5.3-memory-reflection.md`, `docs/ARCHITECTURE.md` §3b) —
  `memory.root` (managed vault; defaults to `CLAUDE_UI_MEMORY_ROOT`), `memory.enabled`
  (default on — injects the memory MCP tools + episodic window into every session),
  `memory.reflection-default` (default off — per-session `reflectionEnabled` always
  overrides), `memory.reflection-model` (default `haiku`), `memory.sync-interval-
  minutes` (default 5, floor 1) for picking up hand-edited vault files
  (`MemorySyncService`), and `memory.retention-days` (default 0 = never prune) for
  pruning a CLOSED-and-reflected session's raw journal (`MemoryRetentionService`,
  hourly tick — the episode/semantic memory a reflection wrote is the durable record
  from that point on), and `memory.reflection-approval-required` (**default on**) —
  a reflection is held as a pending proposal for explicit approve/discard (editable
  first, like a permission prompt's "edit before allow") rather than written
  immediately; turn off to restore straight auto-apply. The 🧠 dashboard dialog
  searches (hybrid dense+sparse) and browses/edits/archives memory across services,
  plus a "Pending" tab (topbar badge count) for approving/discarding proposals; the
  widget's 🧠 button triggers an immediate reflection on that session.

**Fixed internals** (code constants, for awareness): stream_delta journal batching
50 events / 250 ms with coalescing after each completed turn; crash stderr tail 100
lines; WS send timeout 10 s; git command timeout 60 s; sidecar shutdown grace 5 s + 2 s
before force-kill; auto-title ≤6 words / 60 s timeout; log rotation 10 MB daily,
14 days app / 30 days errors (200 MB / 100 MB total caps); 7.4's `spawn_child_session`
MAX_CHILDREN = 5 (lifetime per parent, not concurrent — children aren't recycled).

## Conventions

- Ports: backend 8080, Vite 5173, Postgres 5432.
- Commit style: imperative summary line; commits/pushes only when asked.
- Errors over REST: RFC 7807 problem+json. All inter-process protocols: NDJSON.
- Per-session event ordering uses a backend-assigned monotonic `seq`; clients resume
  with `afterSeq`.
- The repo lives on `/mnt/d` (Windows drive) under WSL — git prints CRLF warnings;
  `.gitattributes` normalizes endings, this is expected noise. Primary deployment
  target is macOS; keep everything platform-neutral.
