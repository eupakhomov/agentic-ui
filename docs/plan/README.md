# Claude Code Multi-Session Manager — Implementation Plan

A Spring Boot backend + React dashboard for running parallel Claude Code sessions,
each in its own Git worktree, driven through Node sidecars using the Claude Agent SDK.

## Decision log

| Date | Decision |
|---|---|
| 2026-08-23 | Integration via **Agent SDK sidecar** (one Node process per session), not raw CLI control protocol |
| 2026-08-23 | Deployment: **LAN, single user** → bearer-token auth on REST + WS, optional TLS, configurable bind address |
| 2026-08-23 | Persistence: **PostgreSQL** (sessions, event journal, config templates; pgvector-ready for later RAG/memory) |
| 2026-08-23 | Frontend: **React + react-grid-layout**, Vite, TypeScript |
| 2026-08-23 | **Java 25** (matches existing pom; IntelliJ manages the JDK, CLI builds need it on `JAVA_HOME` too) |
| 2026-08-23 | **Flyway** manages all Postgres schema migrations (versioned `V*__*.sql`, applied on backend startup) |
| 2026-08-23 | WS auth token is carried in the **`Sec-WebSocket-Protocol` header**, never the query string |
| 2026-08-23 | Primary deployment target is a **Mac laptop**; WSL is only the current dev box. No filesystem-placement constraints for worktrees — `worktree-root` is just a config value (default `~/claude-worktrees`) |
| 2026-08-23 | **Multi-service ecosystem context**: session config carries **two folders** — the specific service repo (checked out as the writable worktree) and its **parent ecosystem folder** containing the sibling services, attached **read-only** (SDK `additionalDirectories` / `--add-dir`; writes into it auto-denied by the sidecar) so Claude can read how the service's peers communicate |
| 2026-08-23 | **Provider-agnostic backend & UI**: the sidecar NDJSON contract is the *provider adapter interface* — the Claude sidecar is its reference implementation. Neutral naming (`providerSessionId`), a **capabilities handshake** in `ready` drives which controls the UI renders, sessions carry a `provider` id (default `claude`) + opaque `providerConfig`, adapters are launched from per-provider config, and asset materialization is a per-provider strategy. No second adapter until Phase 5 (Codex CLI candidate); the Claude UX is never dumbed down — capabilities gate the controls |
| 2026-08-23 | **Per-session Claude behavior config**: thinking budget preset, max turns per prompt, cost budget, extra system instructions, fallback model, custom subagents (materialized like skills), template kickoff prompts with placeholders. **Auto-edits vs per-edit confirmation = `permission-mode`** (`default` asks, `acceptEdits` auto-applies), togglable **mid-session** from the widget header via a `set_permission_mode` sidecar command. Message queueing while RUNNING, auto-titling, and idle-session parking included; hooks injection, transcript export, checkpoints/rewind, prompt fan-out, usage dashboard → Phase 5 |
| 2026-08-23 | **Skills per session**: session/template config lists *skill sources* — a skill dir, a single `SKILL.md`, an index file listing skill paths, or a **git repo of skills** (URL; cloned/updated into a local cache, skills discovered inside). The backend materializes them into the worktree's `.claude/skills/` at provisioning (symlink; copy fallback), never clobbering the repo's own skills; the sidecar loads project settings so they're discovered |

## Repository layout (target)

```
claude-ui/
├─ pom.xml, src/…          # Spring Boot backend (existing skeleton)
├─ sidecar/                # Node + TS session engine (@anthropic-ai/claude-agent-sdk)
├─ frontend/               # Vite + React + TS dashboard
├─ docker-compose.yaml     # PostgreSQL (pgvector image)
└─ docs/
   ├─ plan/                # These phase documents
   └─ PROTOCOL.md          # Authoritative message contracts (written in Phase 1–2)
```

## Phases

| Phase | Doc | Outcome |
|---|---|---|
| 0 | [phase-0-foundation.md](phase-0-foundation.md) | Toolchain, git repo, Postgres up, app boots against it |
| 1 | [phase-1-sidecar.md](phase-1-sidecar.md) | Standalone session engine proven from the terminal |
| 2 | [phase-2-backend.md](phase-2-backend.md) | REST + WS backend: worktrees, process supervision, journal, templates |
| 3 | [phase-3-frontend.md](phase-3-frontend.md) | Dashboard with live widgets, approvals, reconnect/replay |
| 4 | [phase-4-hardening.md](phase-4-hardening.md) | Lifecycle safety, limits, crash/resume, cost display |
| 5 | [phase-5-extensions.md](phase-5-extensions.md) | Backlog: git panel, PRs, RAG/memory on pgvector, … |

Each phase ends in a runnable state, has a **Definition of Done** checklist, and a
**manual test script** with concrete commands and expected results. A phase is not
started until the previous phase's DoD is fully checked.

## Cross-cutting conventions

- **Ports**: backend `8080`, Vite dev server `5173`, Postgres `5432`.
- **Package root**: `de.pamir.claude.ui`.
- **All inter-process protocols are NDJSON** (one JSON object per line): sidecar stdio and the WS channel.
- **Event ordering**: every session event gets a per-session monotonic `seq` (assigned by the backend). Clients resume with `afterSeq`.
- **Sidecar logging** goes to stderr only; stdout is protocol-pure.
- **Errors over REST** use RFC 7807 problem+json (Spring's default `ProblemDetail`).
- **Secrets/config**: auth token and DB credentials come from `application-local.yaml` (gitignored) or env vars, never committed.
