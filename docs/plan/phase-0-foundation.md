# Phase 0 — Foundation & Environment

**Goal:** a committed git repo with a working toolchain (JDK 25, Node LTS, Docker/Postgres),
and the existing Spring Boot skeleton booting with a healthy database connection and
Flyway wired in. No product features yet.

**Estimated effort:** ~half a day.

## Tasks

### 0.1 Version control
- `git init`, verify existing `.gitignore` covers: `target/`, `.idea/` (keep `.idea/` decision — recommend ignoring), `node_modules/`, `dist/`, `application-local.yaml`, `.env`.
- Initial commit of the current skeleton before any changes (clean baseline to diff against).

### 0.2 Toolchain
- **JDK 25**: IntelliJ already manages a JDK 25 for the project; the CLI needs one too.
  Note: an IntelliJ-downloaded JDK is a Windows build — WSL shells need their own Linux
  JDK 25 (e.g. via sdkman or apt), exported as `JAVA_HOME` for `./mvnw`. On the eventual
  Mac target the IntelliJ-managed JDK (`~/Library/Java/…` or `~/.jdks`) can be reused
  directly via `JAVA_HOME`. `./mvnw -v` must report 25 in the build shell.
- **Node LTS (≥ 22)** in WSL via nvm: needed by `sidecar/` (Agent SDK) and `frontend/` (Vite).
  Note: the installed `claude` CLI is a native binary and does not depend on Node.
- Confirm `claude --version` works for the user that will run the backend
  (the CLI reads credentials from that user's `~/.claude`).

### 0.3 PostgreSQL
- Add `docker-compose.yaml` at repo root:

```yaml
services:
  postgres:
    image: pgvector/pgvector:pg17        # pgvector baked in for later RAG/memory work
    environment:
      POSTGRES_DB: claude_ui
      POSTGRES_USER: claude_ui
      POSTGRES_PASSWORD: claude_ui       # dev-only; overridden via env for anything else
    ports:
      - "127.0.0.1:5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data
volumes:
  pgdata:
```

- **Fallback** if Docker is not available in WSL: a native Postgres 17 install with the
  same DB/user; all connection settings live in configuration, nothing assumes Docker.
- pgvector is not enabled in any migration yet — the image just makes
  `CREATE EXTENSION vector;` possible later (Phase 5).

### 0.4 Backend dependencies & config
- `pom.xml` additions (Spring Boot 4.1.x starters):
  - `spring-boot-starter-websocket`
  - `spring-boot-starter-data-jdbc`
  - `org.postgresql:postgresql` (runtime)
  - `spring-boot-starter-flyway` + `org.flywaydb:flyway-database-postgresql`
    (Boot 4 modularization: plain `flyway-core` does NOT autoconfigure — the starter is required)
- `application.yaml`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/claude_ui
    username: claude_ui
    password: ${CLAUDE_UI_DB_PASSWORD:claude_ui}
server:
  address: 0.0.0.0          # LAN deployment; auth added in Phase 2
  port: 8080

claude-ui:
  repo-path: /mnt/d/projects/<target-service>  # the service repo sessions will work on
  worktree-root: ${HOME}/claude-worktrees      # any writable location; plain config value
  ecosystem-root: /mnt/d/projects              # parent folder holding ALL services; attached
                                               # to every session read-only as wider context
                                               # (overridable per session/template)
  skills-root: ${HOME}/claude-skills           # optional library of reusable skills / skill
                                               # index files offered in the session dialog
  providers:                                   # adapter launch commands by provider id
    claude:
      command: ["node", "sidecar/dist/index.js"]
  max-sessions: 4
  auth-token: ${CLAUDE_UI_TOKEN:}              # enforced starting Phase 2
```

- **Flyway is the sole owner of the Postgres schema**: every change ships as a
  versioned `src/main/resources/db/migration/V*__*.sql`, applied automatically at
  backend startup; no manual DDL, no `ddl-auto`-style generation. Phase 0 adds
  `V1__baseline.sql` (history table + no-op marker) purely to prove the pipeline;
  real schema arrives in Phase 2 as `V2__core_schema.sql`.
- `@ConfigurationProperties(prefix = "claude-ui")` record `AppProperties` with the keys above.

## Out of scope
- Any schema beyond the Flyway baseline; auth enforcement; sidecar/frontend scaffolding.

## Definition of Done
- [ ] Repo is a git repository with an initial baseline commit and correct `.gitignore`.
- [ ] `java --version` reports 25; `./mvnw clean verify` succeeds (skeleton test passes).
- [ ] `node --version` reports ≥ 22 LTS.
- [ ] `docker compose up -d` (or native fallback) yields a reachable Postgres with DB `claude_ui`.
- [ ] Backend boots; Flyway applies `V1__baseline` exactly once; restart applies nothing new.
- [ ] `GET /actuator/health` returns `UP` including the `db` component.
- [ ] `AppProperties` binds and is logged at startup (token value masked).

## Manual test script

| # | Action | Expected |
|---|---|---|
| 1 | `git log --oneline` | baseline commit present |
| 2 | `./mvnw clean verify` | BUILD SUCCESS on JDK 25 |
| 3 | `docker compose up -d && docker compose ps` | postgres `running (healthy)` |
| 4 | `psql -h localhost -U claude_ui -c '\dt'` (pw `claude_ui`) | connects; `flyway_schema_history` after step 5 |
| 5 | `./mvnw spring-boot:run` | boots, log shows Flyway `V1` applied, no errors |
| 6 | `curl -s localhost:8080/actuator/health` | `"status":"UP"` with `db: UP` |
| 7 | Stop app, run again | Flyway logs "no migrations necessary"; still healthy |
