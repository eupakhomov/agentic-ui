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
| 2026-08-23 | **Per-session service & ecosystem selection** (pulled forward from backlog 5.7): the create dialog offers every git repo under `ecosystem-root` as the session's service (worktree source, `repoPath` in the create request), and the ecosystem context path is an editable per-session field (empty = no wider context). `claude-ui.repo-path` remains only the default selection |
| 2026-08-23 | **Provider-agnostic backend & UI**: the sidecar NDJSON contract is the *provider adapter interface* — the Claude sidecar is its reference implementation. Neutral naming (`providerSessionId`), a **capabilities handshake** in `ready` drives which controls the UI renders, sessions carry a `provider` id (default `claude`) + opaque `providerConfig`, adapters are launched from per-provider config, and asset materialization is a per-provider strategy. No second adapter until Phase 5 (Codex CLI candidate); the Claude UX is never dumbed down — capabilities gate the controls |
| 2026-08-23 | **Per-session Claude behavior config**: thinking budget preset, max turns per prompt, cost budget, extra system instructions, fallback model, custom subagents (materialized like skills), template kickoff prompts with placeholders. **Auto-edits vs per-edit confirmation = `permission-mode`** (`default` asks, `acceptEdits` auto-applies), togglable **mid-session** from the widget header via a `set_permission_mode` sidecar command. Message queueing while RUNNING, auto-titling, and idle-session parking included; hooks injection, transcript export, checkpoints/rewind, prompt fan-out, usage dashboard → Phase 5 |
| 2026-08-23 | **Skills per session**: session/template config lists *skill sources* — a skill dir, a single `SKILL.md`, an index file listing skill paths, or a **git repo of skills** (URL; cloned/updated into a local cache, skills discovered inside). The backend materializes them into the worktree's `.claude/skills/` at provisioning (symlink; copy fallback), never clobbering the repo's own skills; the sidecar loads project settings so they're discovered |
| 2026-08-30 | **Codex CLI provider adapter (5.13)**: targets `codex app-server`'s JSON-RPC-over-stdio protocol (not `codex exec`, which is non-interactive), in a new sibling `sidecar-codex/` package — `provider` was already a wired-through per-session field with no adapter to select. First pass is an MVP (no skills/MCP passthrough/plan mode); cost is estimated from Codex's token counts against a **Settings-editable per-model price table** (`codex.pricing`), since Codex reports no per-turn USD. Provider is chosen **per session/template**, defaulting from a new **Settings** entry (`session.default-provider`). Full mapping tables and rationale in [phase-5.13-codex-provider.md](phase-5.13-codex-provider.md) |
| 2026-08-30 | **Codex follow-up**: skills and MCP flipped to `skills: true`/`mcp: true` after live confirmation both are feasible — skills via `skills/extraRoots/set` pointing at the already-materialized `.claude/skills/` (Codex reads the same `SKILL.md` format), MCP via `thread/start.config.mcp_servers` (thread-scoped, not the global `codex mcp add` registration), with bearer tokens passed as a named env var on the spawned child rather than an inline header. **Agents confirmed permanently infeasible** (no Codex equivalent to Claude's static subagent files exists at all — `agentSources` is now rejected at creation for `provider: codex`, not silently dropped). `acceptEdits`/`plan` permission modes re-investigated and confirmed still not mappable — stays at 2 modes. The create dialog's permission-mode control changed from a `<select>` to a row of clickable chips (capability-filtered, same component for every provider) at the same time. See the design doc's "Follow-up" section for the full research. |
| 2026-09-05 | **Layered memory & reflection (5.3, redesigned)**: the original per-turn `memory_chunk` RAG sketch is superseded. Per-session **reflection switch** — one structured system-session turn at close (+ manual "Reflect now") retrospects the transcript and emits JSON memory ops. Memory is **layered**: episodic (`memory_episode` DB rows, append-only, auto-injected as a recent same-service window at spawn) vs semantic (**Markdown files with YAML frontmatter** as source of truth in a managed `memory.root`, DB rows only an index — human-editable, hash-synced), scoped **ecosystem vs service** (keyed by `repo_path`). Retrieval is **hybrid**: pgvector dense + Postgres FTS/`pg_trgm` sparse, fused with RRF (no ParadeDB — stock image). Agents consume memory via **hierarchical paging tools** (`memory_tags`/`memory_search`/`memory_read`) on an injected stdio MCP server (`memory-mcp/`, provider-neutral, works on Codex too) — no static top-k injection. Semantic memories interlink with **Obsidian-style `[[wikilinks]]`** (the memory root is a valid Obsidian vault; dangling links allowed and auto-resolved), indexed into `memory_link` so reads return the one-hop related-memory neighborhood with descriptions. Journal retention policy lands here, default keep-forever. Full design in [phase-5.3-memory-reflection.md](phase-5.3-memory-reflection.md) |
| 2026-09-06 | **Phase 7 planned (UX & orchestration)**: 7.1 single-key Gmail-style hotkeys over a new focused-widget concept (`y`/`d` approve/deny is the hot path); 7.2 window management keeps tiling as the base — maximize is CSS-only on the live grid item (no remount, WS intact), minimized widgets stay **mounted but hidden** (notifications + store keep working), Exposé renders **live store-backed cards** (the store already holds every transcript; zero extra WS). 7.3 continuation transfers context as an **AI handoff summary by default** (system-turn over `TranscriptDigest`, /compact pattern; raw-digest checkbox for fidelity), landing as an editable unsent draft (ticket-import precedent); retention-pruned sessions are not continuable. 7.4 multi-service fan-out via **orchestration MCP tools on the existing in-process server** — spawn is human-gated by the *normal tool-permission prompt* (requires narrowing the blanket `mcp__memory` pre-approval to the three read-only tools), children **push** results (`report_result` → parent queue, waking a PARKED parent) and **stay open for review**; depth 1 only. V11 adds `continued_from_id` + `parent_session_id`. [phase-7-ux-and-orchestration.md](phase-7-ux-and-orchestration.md) |
| 2026-08-29 | **Skill & agent library (Phase 6)**: curated library on top of the per-session sources. Embeddings via **Voyage API** (`CLAUDE_UI_VOYAGE_API_KEY`, `vector(1024)`, `voyage-3.5-lite`) behind an `EmbeddingClient` interface (shared groundwork for 5.3 RAG); feature degrades gracefully without the key. Import destinations **reuse skillsRoot** (+ new agents root), both promoted to persisted settings (env values stay defaults, like `ecosystem.root`). Scanner detection is **convention-first** (`SKILL.md` dir = skill, standalone agent `.md` = agent; name-contains heuristic only as low-confidence fallback). Remote sources via **`gh` (GitHub-only for now)** — `gh repo clone --depth 1` + `gh repo sync` into `.repo-cache` (SHA-256 slug): gh's stored auth makes private repos work with the same `gh auth login` prerequisite the PR features already have; a plain-git fallback for other remotes can slot in later. Sync notifications via dashboard polling + badge, not the per-session WS journal |

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
| 6 | [phase-6-skill-library.md](phase-6-skill-library.md) | Skill & agent library: scan/import/tag/search (pgvector), scheduled source sync |
| 7 | [phase-7-ux-and-orchestration.md](phase-7-ux-and-orchestration.md) | Hotkeys, window management (maximize/minimize/Exposé), session handoff, multi-service parent/child fan-out |

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
