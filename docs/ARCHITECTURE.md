# claude-ui — Architecture (as built)

Status: Phases 0–4 complete plus git panel (5.1), PR creation (5.2), desktop
notifications (5.14), and per-session service/ecosystem selection (5.7). This
document describes the running system and sketches implementations for the remaining
backlog (5.3–5.13). The dated decision log in `docs/plan/README.md` remains the
authority on *why*; this file covers *what and how*.

## 1. System overview

```
┌────────────────────────────  Browser (React SPA)  ───────────────────────────┐
│  Dashboard grid (react-grid-layout, layout in localStorage)                  │
│  SessionWidget ×N: transcript ⟵ zustand reducer ⟵ WS envelopes              │
│    permission/plan cards · git panel · queued chips · mode toggle · budget   │
│  TokenGate (bearer in localStorage) · desktop Notifications (unfocused tab)  │
└───────┬──────────────────────────────────────────────────────────────────────┘
        │ REST /api/** (Authorization: Bearer)          WS /ws/sessions/{id}
        │                                               (token via Sec-WebSocket-Protocol,
        ▼                                                afterSeq replay + live)
┌────────────────────────────  Spring Boot backend  ───────────────────────────┐
│ Controllers: sessions · templates · git ops · meta (services/branches/skills)│
│              maintenance (orphans) · SPA fallback                            │
│ SessionService ── state machine, FIFO queue, budgets, parking, auto-title    │
│   │        │                                                                 │
│   │        ├─ GitWorktreeService / GitOpsService (shell git, gh CLI)         │
│   │        ├─ AssetProvisioningService (skills/agents → .claude/, symlinks)  │
│   │        └─ SidecarManager → SidecarHandle ×N (virtual-thread pipes,       │
│   │                            PID files, stderr → logs/sidecar/<id>.log)    │
│   ▼                                                                          │
│ EventJournal (Postgres, per-session seq, delta batching+coalescing)          │
│   └─ SessionEventBus → N WS subscribers (replay-then-live, seq-deduped)      │
└───────┬──────────────────────────────────────────────────────────────────────┘
        │ NDJSON over stdio (provider adapter protocol v1, docs/PROTOCOL.md)
        ▼
┌───────────────────────  Sidecar (Node, one per session)  ────────────────────┐
│ @anthropic-ai/claude-agent-sdk: streaming input, canUseTool bridge,          │
│ summarized thinking + progress, interrupt, resume, permission modes          │
│ cwd = git worktree (one per session, branch-isolated)                        │
└──────────────────────────────────────────────────────────────────────────────┘
```

## 2. Core invariants

- **The journal is the single source of truth.** Every event (adapter output,
  user input echoes, state changes, queue snapshots) gets a per-session monotonic
  `seq` and is persisted before fan-out. Any client can rebuild the full UI state
  from `afterSeq=0`; reconnects resume losslessly from the last seen seq.
- **The adapter contract is provider-neutral.** The backend/UI never reference
  Claude specifics; capabilities announced in `ready` gate which controls render.
  A second provider = a new adapter binary + a `claude-ui.providers.<id>` entry.
- **Sessions outlive processes.** `providerSessionId` (persisted from `system_init`)
  makes sidecars disposable: crash → CRASHED + Resume; idle timeout → PARKED with
  transparent wake; backend restart → sweep marks CRASHED, PID files reap orphans.
- **Worktrees isolate work.** One worktree+branch per session under `worktree-root`;
  provisioned assets and the PID file are kept out of `git status` via the
  per-worktree `info/exclude`; close resolves dirt explicitly (commit/stash/discard).

## 3. Key tables (V2 migration)

`session` (config + provider/session ids + capabilities + state) ·
`session_event(session_id, seq, ts, type, payload jsonb)` ·
`session_queue` (FIFO pending messages) · `session_template(config jsonb)`.
Postgres runs the `pgvector/pg17` image — the `vector` extension is available but
unused until 5.3.

## 4. Backlog implementation sketches (5.3–5.13)

### 5.3 Long-term memory / RAG (pgvector)
- `V3__memory.sql`: `CREATE EXTENSION vector; memory_chunk(id, session_id, source,
  content text, embedding vector(1024), ts)`, HNSW index.
- **Ingestion**: on `turn_complete`, a background virtual thread summarizes the turn
  (one-shot `claude -p --model haiku`, same pattern as auto-titling) and embeds it.
  Embedding provider is the open decision: Voyage API (needs a key) vs a local
  embedder; recommend starting with whatever key is at hand behind a tiny
  `EmbeddingClient` interface.
- **Retrieval**: opt-in per template — at session create, top-k chunks across past
  sessions of the same repo are written to `<worktree>/.claude-ui-memory.md` and
  referenced via `--append-system-prompt`. Also a `GET /api/search?q=` for the UI.
- Journal retention (prune CLOSED sessions' events after ingestion) lands here.

### 5.4 Templates v2
Template config already carries every session field. Missing: per-template default
base branch + per-service default template (add `service_path` column to
`session_template`, dialog picks the matching template automatically), and a
"duplicate session" action (`POST /api/sessions/{id}/duplicate` — copy config, fresh
branch name).

### 5.5 Model switching mid-session
The SDK exposes `setModel`. Add a `set_model` command to the adapter protocol
(mirroring `set_permission_mode`: command + `model_changed` ack event), a
`PATCH`-like control from the model chip, and journal the change. ~2 hours.

### 5.6 Mobile / PWA
Single-column stack under 700px (CSS only), sticky input bar, `manifest.json` +
minimal service worker for installability. Notifications already work; consider Web
Push later (needs a push service — non-trivial, defer).

### 5.7 Multi-repo — remaining gaps
Shipped: per-session service picker (repos under `ecosystem-root`) + editable
ecosystem path. Missing: multiple ecosystem roots (make `ecosystem-root` a list;
`/api/repo/services` merges), repos outside any root (free-form path input with
validation — backend already validates any `repoPath`), per-service defaults (see 5.4).

### 5.8 Per-session hooks / settings injection
Extend AssetProvisioningService: template config key `settings` (JSONB) written to
`<worktree>/.claude/settings.local.json` (local layer avoids colliding with a
committed `settings.json`). Needs nothing from the sidecar — the SDK already loads
project settings. Guard: refuse if the repo tracks `settings.local.json`.

### 5.9 Transcript export
Pure read: `GET /api/sessions/{id}/export.md` renders the journal (user/assistant
turns, collapsed tool summaries, cost footer). ~100 lines in a new controller; add a
download button next to the git one. No schema changes.

### 5.10 Turn checkpoints & rewind
On `turn_complete`, if the worktree is dirty: `git add -A && git commit` onto a
ref `refs/claude-ui/<session>/turn-<n>` (commit on the branch, then update-ref;
or plain branch commits with a tag-like ref). "Rewind" = `git reset --hard <ref>`
(refuse while RUNNING). The transcript's turn footers become rewind anchors
(`checkpoint` event carries the ref). Interacts with close-dirty flow: checkpointed
turns are already committed, so close becomes cleaner too.

### 5.11 Prompt fan-out
`POST /api/sessions/fanout {prompts×1, branches×N, template}` → N create calls
(the create endpoint needs no change). UI: a compare view rendering N widgets
side-by-side with a diff summary per session (reuse `git/diff`). Defer the fancy
diff-compare grid; a "fan out" checkbox in the create dialog + naming convention
(`branch-1..N`) is a good first cut.

### 5.12 Usage dashboard
One query over `session_event` (`turn_complete` payloads): cost by day/session/model.
Endpoint + a small chart page (recharts or plain SVG). Journal retention (5.3) must
keep `turn_complete` rows or roll them into a `usage_daily` table first.

### 5.13 Codex CLI provider adapter
The proof of provider-agnosticism. New `sidecar-codex/` translating Codex CLI's
headless protocol to adapter protocol v1: map its approval requests to
`permission_request`, its session id to `providerSessionId`, announce a reduced
capabilities set (no plan mode, no skills). Register under
`claude-ui.providers.codex`; the UI needs zero changes — that's the whole point.

## 5. Operational notes

Limits/caps and env vars: see CLAUDE.md "Limits & caps". Logs: `logs/claude-ui.log`,
`logs/error.log`, `logs/sidecar/<sessionId>.log`. Auth: bearer token everywhere
(REST header, WS subprotocol), startup guard refuses tokenless non-loopback binds.
Metrics: `claudeui.sessions.active|parked` via `/actuator/metrics` (token-gated).
