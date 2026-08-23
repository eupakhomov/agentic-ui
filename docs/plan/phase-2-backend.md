# Phase 2 — Backend Core

**Goal:** the Spring Boot backend manages the full session lifecycle: provisions git
worktrees, spawns/supervises sidecars, journals every event to Postgres, exposes REST
for session/template management and WebSocket for live streaming with replay. Secured
with a bearer token. Testable end-to-end with `curl` + `websocat` — no frontend yet.

**Estimated effort:** 2–3 days.

## Architecture

```
REST /api/**  ─┐                       ┌─ GitWorktreeService (jgit? no — shell out to git)
WS /ws/**     ─┼─ SessionService ──────┼─ SidecarProcessManager (one SidecarHandle per session)
               │      │                └─ EventJournal (Postgres append + replay)
               │      └─ SessionRegistry (in-memory state, backed by DB)
               └─ TemplateService
```

- Git operations **shell out to `git`** (worktree semantics are fiddly; jgit's worktree
  support is incomplete). All git invocations go through one `GitCommandRunner` with
  timeouts and captured stderr.
- `SidecarHandle`: process + stdin writer + two virtual-thread pipe readers (stdout
  NDJSON → event pipeline; stderr → rolling log buffer, dumped on crash).
- Event pipeline per session: sidecar event → assign `seq` → persist to journal →
  fan out to current WS subscribers. Persist-then-fanout keeps replay authoritative.

## Database schema — `V2__core_schema.sql`

```sql
CREATE TABLE session (
  id                UUID PRIMARY KEY,
  name              TEXT NOT NULL,
  provider          TEXT NOT NULL DEFAULT 'claude',  -- adapter id; launch command from config
  provider_config   JSONB,                 -- opaque provider-specific settings
  repo_path         TEXT NOT NULL,         -- the specific service repo (worktree source)
  ecosystem_path    TEXT,                  -- parent folder of all services, attached read-only
  context_dirs      TEXT[] NOT NULL DEFAULT '{}',  -- extra read-only dirs beyond ecosystem_path
  branch            TEXT NOT NULL,
  base_branch       TEXT NOT NULL,
  worktree_path     TEXT NOT NULL,
  provider_session_id TEXT,                -- from system_init; enables --resume
  model             TEXT,
  permission_mode   TEXT NOT NULL DEFAULT 'default',
  allowed_tools     TEXT[] NOT NULL DEFAULT '{}',
  disallowed_tools  TEXT[] NOT NULL DEFAULT '{}',
  mcp_config        JSONB,
  env_vars          JSONB,
  skill_sources     JSONB NOT NULL DEFAULT '[]',   -- [{type: dir|file|index|repo, ref}, …]; resolved+materialized at provisioning
  agent_sources     JSONB NOT NULL DEFAULT '[]',   -- same shapes; materialized into .claude/agents/
  instructions      TEXT,                  -- extra system prompt (--append-system-prompt)
  max_thinking_tokens INT,                -- null = SDK default; 0 = thinking off
  max_turns         INT,                   -- cap agentic turns per user message
  fallback_model    TEXT,
  cost_budget_usd   NUMERIC,               -- null = unlimited; enforced in Phase 4

  state             TEXT NOT NULL,         -- see state machine below
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE session_event (
  session_id UUID   NOT NULL REFERENCES session(id) ON DELETE CASCADE,
  seq        BIGINT NOT NULL,              -- per-session monotonic, backend-assigned
  ts         TIMESTAMPTZ NOT NULL DEFAULT now(),
  type       TEXT   NOT NULL,              -- protocol event type
  payload    JSONB  NOT NULL,
  PRIMARY KEY (session_id, seq)
);

CREATE TABLE session_template (
  id          UUID PRIMARY KEY,
  name        TEXT NOT NULL UNIQUE,
  description TEXT,
  config      JSONB NOT NULL,              -- {model, permissionMode, allowedTools, disallowedTools, mcpConfig, envVars,
                                           --  baseBranch?, ecosystemPath?, contextDirs?, skillSources?, agentSources?,
                                           --  instructions?, maxThinkingTokens?, maxTurns?, fallbackModel?, costBudgetUsd?,
                                           --  kickoffPrompt?}   -- kickoffPrompt may contain {{placeholders}} filled in the create dialog

  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Notes: `stream_delta` events are high-volume; they **are** journaled (needed for
faithful replay) but batched in the writer (flush every 250 ms or 50 events). If volume
becomes a problem, Phase 4 may coalesce consecutive deltas per assistant message.

## Asset provisioning (skills & agents)

During PROVISIONING, `AssetProvisioningService` resolves each entry of `skill_sources`
and materializes them via a **per-provider layout strategy** — for Claude:
skills into `<worktree>/.claude/skills/<skill-name>`, `agent_sources` (same source
shapes) into `<worktree>/.claude/agents/`; other providers supply their own layout or
report the capability as unsupported:

- `dir` — a directory containing `SKILL.md` (or containing multiple skill dirs: each
  child with a `SKILL.md` is taken individually).
- `file` — a single `SKILL.md`; materialized as `<name>/SKILL.md` using its frontmatter name.
- `index` — a text/JSON file listing further sources (paths or repo URLs), expanded
  recursively (cycle-guarded, depth-capped).
- `repo` — a git URL; cloned/updated (shallow) into `<skills-root>/.repo-cache/<slug>`
  once per session creation, then treated like `dir`.

Materialization is by **symlink** (copy fallback if the platform/filesystem refuses),
so library edits reach future turns without re-provisioning. Never overwrites a skill
the target repo itself ships — collisions are skipped and journaled as a warning event.
Skill names surface in the session detail for the UI to display.

## Session state machine

```
CREATING → PROVISIONING → STARTING → IDLE ⇄ RUNNING → (IDLE)
                              │        │        └→ WAITING_INPUT → RUNNING
        any ──────────────────┴────────┴──→ CRASHED ──resume──→ STARTING
IDLE|CRASHED → CLOSING → CLOSED   (CLOSING handles dirty-worktree resolution)
IDLE → PARKED → STARTING          (idle timeout shuts sidecar down; next message
                                   transparently resumes — introduced in Phase 4)
FAILED (provisioning/start error, worktree cleaned up)
```

State transitions are events too (`type: "state_changed"`) — journaled and fanned out
like everything else, so the UI needs no separate status channel.

## REST API (all under bearer auth)

| Method & path | Body / params | Behavior |
|---|---|---|
| `POST /api/sessions` | `{name, branch, baseBranch, templateId?, overrides?, kickoffValues?}` | create worktree, spawn sidecar; template config merged with overrides; if the template has a `kickoffPrompt`, its `{{placeholders}}` are filled from `kickoffValues` and sent as the first user message once IDLE; `ecosystemPath` defaults to configured `ecosystem-root` (overridable in template/overrides, `null` = no wider context); backend passes it plus any `contextDirs` to the sidecar as `--context-dir` args; 409 if branch already checked out or max-sessions reached |
| `GET /api/sessions` | — | list with state, branch, model, cost-to-date |
| `GET /api/sessions/{id}` | — | full detail incl. worktree path, providerSessionId, capabilities |
| `GET /api/sessions/{id}/events?afterSeq=n` | — | journal page (REST fallback to WS replay) |
| `POST /api/sessions/{id}/resume` | — | for CRASHED: respawn sidecar with `--resume` |
| `DELETE /api/sessions/{id}?dirty=fail\|commit\|stash\|discard` | `dirty=commit` takes `{message}` | graceful shutdown → dirty check → resolve per param (`fail` = 409 with `git status` payload, the default) → `worktree remove` + `prune` → state CLOSED |
| `GET /api/repo/branches` | — | local branches of the configured repo (for the create dialog) |
| `GET /api/skills` | — | skills discovered under `skills-root` (name, description from frontmatter, source path/kind) for the create-dialog picker |
| `GET/POST/PUT/DELETE /api/templates[/{id}]` | template JSON | CRUD; template config is **copied** into the session at creation, so editing/deleting a template never affects running sessions |

## WebSocket

- Endpoint: `GET /ws/sessions/{id}?afterSeq=n`. Auth token is carried in the
  `Sec-WebSocket-Protocol` header (decided — query-string tokens leak into logs; the
  handshake interceptor validates and echoes the subprotocol back per RFC 6455).
- On connect: replay journal `> afterSeq`, then a `{"type":"replay_complete","lastSeq":n}`
  marker, then live events. Fan-out supports **N subscribers per session** (or zero —
  sessions run fine with nobody watching).
- Inbound frames = sidecar commands, restricted set: `user_message`,
  `permission_response`, `interrupt`, `set_permission_mode`. Everything else → error frame.
- **Message queueing**: a `user_message` arriving while the session is RUNNING is
  queued (persisted, visible in session detail) and dispatched on `turn_complete`,
  FIFO. A `queue_updated` event keeps subscribers in sync; queued messages can be
  deleted before dispatch (`DELETE /api/sessions/{id}/queue/{n}`).
- `set_permission_mode` is forwarded to the sidecar; the resulting
  `permission_mode_changed` event is journaled and also updates `session.permission_mode`
  so the current mode survives restart/resume.
- Outbound frame = journal envelope: `{seq, ts, type, payload}`.
- Slow-consumer policy: per-socket outbound queue capped (e.g. 1000); overflow → close
  socket with policy code (client reconnects with `afterSeq` and catches up from journal).

## Security (LAN deployment)

- `AuthTokenFilter` on `/api/**` and the WS handshake: constant-time compare against
  `claude-ui.auth-token`; if the property is empty, server refuses to bind to non-loopback
  (fail-fast at startup rather than running open on the LAN).
- TLS: documented `server.ssl.*` self-signed setup in `docs/plan/README.md`; optional.
- CORS: disabled (same-origin serving); Vite dev uses a proxy instead.

## Sidecar supervision details

- Spawn: launch command resolved from `claude-ui.providers.<provider>.command` config
  (Claude default: `node sidecar/dist/index.js`), plus `--cwd <worktree>` and the
  session's args; env passthrough + session `env_vars`; working dir = worktree.
  The `ready` event's `capabilities` object is stored on the session and exposed in
  the REST detail — the UI renders provider-dependent controls from it.
- Crash detection: process exit without `shutdown` command → state CRASHED, stderr tail
  journaled as an `error` event.
- Startup handshake: no `ready` within 15 s → kill, state FAILED.
- Backend restart recovery (v1 policy): on boot, any session in a live state is marked
  CRASHED (sidecars died with the JVM) and offered resume in the UI. `@PreDestroy`
  sends `shutdown` to all, waits 5 s, then `destroyForcibly()` on descendants.

## Out of scope
- Frontend; automatic resume without user action; multi-repo support (single configured
  repo in v1 — schema already carries `repo_path` so this can widen later).

## Definition of Done
- [ ] `V2__core_schema.sql` applies cleanly on fresh and existing Phase-0 databases.
- [ ] Full session lifecycle via curl+websocat (script below) works end-to-end.
- [ ] Worktree edge cases: branch already checked out → 409 with clear message; dirty worktree delete with `dirty=fail` → 409 + status; `discard` removes; `stash`/`commit` preserve work (verified in git).
- [ ] Replay: reconnect with `afterSeq` mid-session yields no gaps and no duplicates (seq-verified).
- [ ] Two websocat clients on one session both receive identical live streams.
- [ ] Kill sidecar with `kill -9` → session CRASHED with journaled stderr tail; `POST …/resume` restores conversation context.
- [ ] Backend restart while a session is IDLE → session shows CRASHED, resume works.
- [ ] Requests without/with wrong token → 401 on REST and WS handshake; startup with empty token + non-loopback bind → refuses to start.
- [ ] Template CRUD works; creating a session from a template applies its config (visible in `system_init` model/tools).
- [ ] Ecosystem context: a session created with the default `ecosystem-root` can answer a question about a sibling service's code over WS; a session created with `ecosystemPath: null` cannot see outside its worktree.
- [ ] Skills: a session created with one source of each kind (dir, file, index, repo) shows all resolved skills in its detail; invoking one over WS works; a name colliding with a repo-shipped skill is skipped with a journaled warning. An `agent_sources` entry materializes into `.claude/agents/` the same way.
- [ ] Behavior config plumbed end-to-end: `instructions`, `maxThinkingTokens`, `maxTurns`, `fallbackModel` all reach the sidecar args (verify via process cmdline + observable behavior).
- [ ] Queueing: two `user_message`s sent during a long turn dispatch FIFO after it; queue visible via REST; deleting a queued message works.
- [ ] `set_permission_mode` over WS changes edit-approval behavior mid-session and persists across a resume.
- [ ] Kickoff prompt: creating from a template with `kickoffPrompt` + `kickoffValues` fires the filled prompt automatically as the first turn.
- [ ] Max-sessions limit enforced with 409.
- [ ] `docs/PROTOCOL.md` extended with the WS envelope + REST contracts.

## Manual test script (excerpt; full script lives in `docs/plan/manual-tests/phase-2.md` when implemented)

`TOKEN=...; H="Authorization: Bearer $TOKEN"`

| # | Action | Expected |
|---|---|---|
| 1 | `curl -H"$H" -XPOST /api/templates -d '{"name":"default","config":{"model":"sonnet","permissionMode":"default"}}'` | 201 with id |
| 2 | `curl -H"$H" -XPOST /api/sessions -d '{"name":"s1","branch":"feat/x","baseBranch":"main","templateId":…}'` | 201; `git worktree list` shows new tree; state reaches IDLE |
| 3 | `websocat "ws://…/ws/sessions/{id}?afterSeq=0" -H…` then send `{"type":"user_message","text":"list files"}` | replay (creation events), `replay_complete`, live deltas, `turn_complete` |
| 4 | Prompt that triggers Bash; answer `permission_response` deny | denial handled in stream |
| 5 | Second websocat with `afterSeq=0` while first stays open | full history replays; both get subsequent events |
| 6 | `kill -9` sidecar pid; observe WS | `state_changed: CRASHED` + error event |
| 7 | `curl -XPOST …/resume`; ask about earlier turn | context recalled |
| 8 | Touch a file in worktree; `DELETE …?dirty=fail` | 409 listing the dirty file |
| 9 | `DELETE …?dirty=stash` | 200; stash exists on branch; worktree gone; state CLOSED |
| 10 | `curl` with no token | 401 |
| 11 | Create session with `skillSources: [{type:"repo", ref:"<git-url>"}, {type:"index", ref:"<path>"}]` | worktree `.claude/skills/` contains resolved skills as symlinks; session detail lists them; `/skill-name` usable over WS |
