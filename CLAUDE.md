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
│  └─ PROTOCOL.md          # Sidecar/WS message contracts (written during Phases 1–2)
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

## Run the backend

```bash
./mvnw spring-boot:run                        # or:
java -jar target/claude.ui-0.0.1-SNAPSHOT.jar
curl -s localhost:8080/actuator/health        # expect "status":"UP" with db UP
```

Config lives in `application.yaml` under `claude-ui.*` (repo path, worktree root,
ecosystem root, skills root, max sessions, auth token, provider launch commands),
bound by `de.pamir.claude.ui.config.AppProperties` and logged at startup (token masked).
Local secrets belong in gitignored `application-local.yaml` or env vars — never commit them.

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

## Conventions

- Ports: backend 8080, Vite 5173, Postgres 5432.
- Commit style: imperative summary line; commits/pushes only when asked.
- Errors over REST: RFC 7807 problem+json. All inter-process protocols: NDJSON.
- Per-session event ordering uses a backend-assigned monotonic `seq`; clients resume
  with `afterSeq`.
- The repo lives on `/mnt/d` (Windows drive) under WSL — git prints CRLF warnings;
  `.gitattributes` normalizes endings, this is expected noise. Primary deployment
  target is macOS; keep everything platform-neutral.
