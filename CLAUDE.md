# claude-ui — Claude Code Multi-Session Manager

Spring Boot backend + React dashboard for running parallel Claude Code sessions, each
in its own git worktree, driven through per-session Node sidecar processes (Claude
Agent SDK). Single user, LAN deployment, PostgreSQL persistence.

## Repository layout

```
├─ src/                    # Spring Boot backend (Java 25, Boot 4.x, package de.pamir.claude.ui)
│  └─ main/resources/db/migration/   # Flyway migrations — the ONLY way schema changes
├─ sidecar/                # Node + TypeScript session engine (Claude Agent SDK) — Phase 1
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

## Build & test (CLI)

```bash
# WSL: make sure JDK 25 is active (a login shell picks this up from ~/.bashrc)
export JAVA_HOME="$HOME/.jdks/jdk-25.0.4.1+1"; export PATH="$JAVA_HOME/bin:$PATH"
./mvnw -v                     # must report Java 25

./mvnw clean verify           # full build + tests — REQUIRES Postgres running (see below)
./mvnw clean verify -DskipTests   # compile-only, no DB needed
```

Gotcha (bash): `export A=x PATH=$A/bin:$PATH` in ONE statement expands `$A` before the
assignment takes effect — export `JAVA_HOME` and `PATH` as two statements.

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

## Run the project

```bash
# 1. Postgres must be up (see Database section), then build the jar.
#    IMPORTANT: stop a running backend first — a live JVM holds the jar and the
#    spring-boot repackage fails half-written.
./mvnw package -DskipTests -Dskip.installnodenpm -Dskip.npm   # fast: reuses frontend/dist
./mvnw package -DskipTests                                    # full: rebuilds frontend too
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
root, ecosystem root, skills root, max sessions, auth token, provider launch
commands), bound by `de.pamir.claude.ui.config.AppProperties` and logged at startup
(token masked). Service repo and ecosystem path are selectable **per session** in the
create dialog; the config values are only defaults. Local secrets belong in gitignored
`application-local.yaml` or env vars — never commit them.

## Sidecar (Phase 1+)

```bash
cd sidecar && npm install && npm run build    # tsc build to dist/
npm run drive -- --cwd /path/to/dir           # manual REPL driver for the NDJSON protocol
```

One sidecar process per session; stdout is protocol NDJSON only, logs go to stderr.
The NDJSON contract is the provider adapter interface — keep it provider-neutral
(`providerSessionId`, capabilities handshake), Claude specifics stay inside the sidecar.

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

## Limits & caps

All operational limits are env-tunable (read at backend startup; the sidecar inherits
the backend's environment):

| Env var | Default | What it caps |
|---|---|---|
| `CLAUDE_UI_MAX_SESSIONS` | `4` | Concurrent live sidecar processes; create/resume beyond it → 409. PARKED sessions don't count |
| `CLAUDE_UI_IDLE_PARK_MINUTES` | `30` | Minutes a session may sit IDLE before its sidecar is shut down (PARKED); next message transparently wakes it |
| `CLAUDE_UI_TOOL_OUTPUT_LIMIT` | `16384` | Bytes of tool output kept per result (sidecar truncates, `truncated` flag set) |
| `CLAUDE_UI_JOURNAL_PAYLOAD_CAP` | `65536` | Max bytes for one journal event payload; larger payloads stored as a truncated preview |
| `CLAUDE_UI_WS_BUFFER_LIMIT` | `1048576` | Per-client WS outbound buffer; a slow consumer overflowing it is disconnected (reconnects + replays losslessly) |
| `CLAUDE_UI_LOG_DIR` | `logs` | Log directory (backend rolling logs + per-session sidecar stderr) |
| `CLAUDE_UI_TOKEN` | — | Dashboard/API auth token (required for non-loopback binds) |
| `CLAUDE_UI_REPO` | `/mnt/d/projects/claude-ui` | Default service repo (per-session selectable in the UI) |
| `CLAUDE_UI_WORKTREE_ROOT` | `~/claude-worktrees` | Where session worktrees live |
| `CLAUDE_UI_ECOSYSTEM_ROOT` | `/mnt/d/projects` | Default read-only context folder + service discovery root |
| `CLAUDE_UI_SKILLS_ROOT` | `~/claude-skills` | Skills library scanned for the create-dialog picker |

**Per-session limits** (create dialog / template / `PATCH /api/sessions/{id}`, not env):
`costBudgetUsd` (turns are refused once cumulative cost reaches it; in-flight turns
finish; raise via the widget's cost chip), `maxTurns` (agentic turns per prompt),
`thinking` budget and `effort` level.

**Fixed internals** (code constants, for awareness): stream_delta journal batching
50 events / 250 ms with coalescing after each completed turn; crash stderr tail 100
lines; WS send timeout 10 s; git command timeout 60 s; sidecar shutdown grace 5 s + 2 s
before force-kill; auto-title ≤6 words / 60 s timeout; log rotation 10 MB daily,
14 days app / 30 days errors (200 MB / 100 MB total caps).

## Conventions

- Ports: backend 8080, Vite 5173, Postgres 5432.
- Commit style: imperative summary line; commits/pushes only when asked.
- Errors over REST: RFC 7807 problem+json. All inter-process protocols: NDJSON.
- Per-session event ordering uses a backend-assigned monotonic `seq`; clients resume
  with `afterSeq`.
- The repo lives on `/mnt/d` (Windows drive) under WSL — git prints CRLF warnings;
  `.gitattributes` normalizes endings, this is expected noise. Primary deployment
  target is macOS; keep everything platform-neutral.
