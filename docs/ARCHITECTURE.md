# claude-ui — Architecture (as built)

Status: Phases 0–4 complete plus git panel (5.1), PR creation (5.2), desktop
notifications (5.14), per-session service/ecosystem selection (5.7), and the skill &
agent library (Phase 6). This document describes the running system and sketches
implementations for the remaining backlog (5.3–5.13). The dated decision log in `docs/plan/README.md` remains the
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
Postgres runs the `pgvector/pg17` image; V7 enables the `vector` extension for the
skill library (see below); V9 (§3b) reuses it for long-term memory and adds `pg_trgm`.

## 3a. Skill & agent library (Phase 6)

Curated library on top of per-session skill sources (`de.pamir.claude.ui.library`):

- **Tables (V7)**: `asset_source` (dir/repo ref, sync flag + last-sync state) ·
  `library_asset` (kind, name/description, managed-copy `location`, `source_path`,
  SHA-256 `content_hash`, ACTIVE/ARCHIVED) · `asset_tag` · `asset_embedding`
  (`vector(1024)`, HNSW cosine) · `source_discovery` (new upstream files).
- **Scan** (`AssetScanService`): convention-first — `SKILL.md` dir = one skill
  (whole-dir tree hash), `.md` under `agent(s)` path = agent, name-contains only a
  low-confidence fallback. Repo sources fetched exclusively via `gh`
  (`RepoCacheService`: `gh repo clone --depth 1` / `gh repo sync`, cache under
  `<skillsRoot>/.repo-cache/<sha256[:16]>`), GitHub-only by design for now.
- **Import** (`LibraryService`): copies into the managed roots (persisted settings
  `library.skills-root` / `library.agents-root`; skills root is also what the
  create-dialog picker and provisioning read), never overwrites different content
  (`-2` suffix + warning), dedupes identical content, writes asset+tags, best-effort
  embeds (`VoyageEmbeddingClient` behind `EmbeddingClient`, key
  `CLAUDE_UI_VOYAGE_API_KEY`, model voyage-3.5-lite).
- **AI-fill** (`LibraryAiService`): batches ≤5 file contents per Haiku system-session
  turn (`SessionService.runSystemTurn`), returns name/description/tags per path.
- **Sync** (`LibrarySyncService`): 60s tick, interval as `last_synced_at` cutoff
  (PrCheckPollingService shape). Changed hash → refresh copy + re-embed; vanished →
  ARCHIVED (files kept); reappeared → restored; unimported → `source_discovery`.
  Frontend polls `GET /api/library/sources` for the 📚 badge + desktop notification
  (the journal/WS pipeline is per-session, so a global event has no transport).
- **API**: `/api/library/{scan,import,ai-fill,assets,search,sources}` — see
  `LibraryController`.

## 3b. Long-term memory & reflection (Phase 5.3)

Full design + decisions: `docs/plan/phase-5.3-memory-reflection.md`.

- **Tables (V9)**: `memory_doc` (scope/service_path, name=slug, description, `tags
  TEXT[]`, content, content_hash, status, `vector(1024)` embedding + generated
  `tsvector`) · `memory_link` (wikilink graph, `to_doc_id` nullable = dangling) ·
  `memory_episode` (append-only, per-session summary, its own embedding/tsvector).
  `session` gains `reflection_enabled`/`reflected_seq`.
- **Semantic memory's source of truth is the filesystem, not the DB** — Markdown +
  YAML frontmatter under a managed vault (`memory.root`), laid out
  `ecosystem/*.md` / `services/<slug>/*.md` (`MemoryPaths` resolves the slug,
  collision-suffixed by repo-path hash, each dir marked with a `.repo-path` file).
  The vault is a valid **Obsidian vault** by construction: filenames are slugs,
  `[[wikilinks]]`/`[[slug|alias]]` in the body resolve the same way Obsidian would
  (same-scope-first, then ecosystem, then any service — `MemoryRepository.
  resolveSlug`), dangling links auto-resolve when the target is (re)written.
  `MemoryDocService` is the only writer; `MemorySyncService` (1-minute tick, the
  configured interval applied as a last-run cutoff) re-indexes hand-edited files.
- **Reflection** (`ReflectionService`): one structured system-session turn per
  session close (async — a Spring `ReflectionRequested` event, not a direct
  `SessionService` dependency, avoids a circular bean; the manual "Reflect now"
  🧠 button calls it synchronously from `SessionController` instead) or manual
  trigger. `TranscriptDigest.render()` (`de.pamir.claude.ui.journal` — provider-
  neutral, not memory-specific, so it also backs 5.9's transcript export via the
  sibling `renderMarkdown()`) renders the journal into a capped text digest;
  `SessionService.runSystemTurn(prompt, model, timeout)` gained a model-override
  overload so reflection can run on a different model than the system session's
  default haiku. The model's JSON response (`episode` + up to 10 `semantic` ops)
  is, **by default, held for human approval rather than applied** (`memory.
  reflection-approval-required`, default true): a `memory_proposal` row
  (V10, `PENDING`/`APPROVED`/`DISCARDED`, partial unique index enforcing at most
  one `PENDING` row per session) and a `reflection_proposed` journal event
  instead of a write. The 🧠 dialog's Pending tab approves (optionally editing
  the episode text or any op — mirrors `permission_response`'s `updatedInput`)
  or discards; either way `ReflectionService.applyReflection()` — the same
  method the auto-apply path calls directly when the setting is off — is what
  actually writes and journals `reflection_complete`; a discard journals
  `reflection_discarded` and writes nothing, leaving `reflectedSeq` unset so a
  later reflection isn't blocked by a discarded one.
- **Retrieval is hybrid**: pgvector cosine + Postgres FTS (`websearch_to_tsquery`/
  `ts_rank_cd`) + `pg_trgm` similarity (catches exact identifiers embeddings blur),
  fused with Reciprocal Rank Fusion in one SQL query (`MemoryRepository.
  hybridSearch`/`MemoryEpisodeRepository.hybridSearch`). Dense arm skipped when
  Voyage is unconfigured — sparse/trigram still work.
- **Agent-facing tools are in-process, not a spawned process**: three `@McpTool`
  beans (`MemoryMcpTools`) served by `spring-ai-starter-mcp-server-webmvc`
  (Streamable-HTTP) at `/api/mcp/memory`, which — because it's mounted under
  `/api/**` — is already covered by the existing bearer-token `AuthTokenFilter`,
  no new auth code. Every session's `mcpConfig.memory` entry is the same static
  `{type: "http", headers: {Authorization}}` block `SessionService.
  memoryMcpServer()` builds (same shape as `linearMcpServer()`, same reused
  `CLAUDE_UI_TOKEN` — not a new secret). Since MCP transport context doesn't
  cleanly expose the inbound session identity to a WebMVC tool method, each tool
  takes an explicit `sessionId` argument instead (resolved server-side to that
  session's `repoPath` for the scope filter); the session learns its own id from
  the episodic-window system-prompt block `SessionService.
  memorySystemPromptBlock()` adds at every spawn (`SidecarManager.spawn` gained
  an `extraSystemPrompt` parameter, combined with the session's own
  `instructions` into one `--append-system-prompt`).
- **Human-facing**: `MemoryController` (`/api/memory/{search,docs,episodes}`) and
  the 🧠 dashboard dialog (`MemoryDialog.tsx`) — hybrid search across scopes,
  browse/edit/archive docs (archive keeps the file, library-style), episode list.
- **Retention**: `MemoryRetentionService` (hourly tick) deletes the raw
  `session_event` rows of a CLOSED session once its `reflected_seq` is set and
  `memory.retention-days` (default 0 = never) has elapsed — the episode/semantic
  memory a reflection wrote is the durable record from that point on.

## 3c. Dashboard UX & orchestration (Phase 7)

Full design + decisions: `docs/plan/phase-7-ux-and-orchestration.md`.

- **7.1 hotkeys**: one document-level `keydown` listener (`useHotkeys`), suppressed
  while typing or a dialog is open. A module-level registry (`widgetRegistry.ts`,
  not React state) lets it reach into a specific `SessionWidget` — focus the
  composer, toggle the git panel, respond to a pending permission — since there's
  no shared component tree between the global listener and per-widget state.
  `y`/`d` special-case `AskUserQuestion` (no safe keystroke "allow"; `d` reuses its
  existing skip message) and `ExitPlanMode` (friendlier deny wording), otherwise
  send the same plain allow/deny `PermissionCard` already does. Every handled key
  calls `preventDefault()` unconditionally (not just where an action fires) — opening
  a dialog whose first field autofocuses can otherwise race the browser's own
  default text-insertion for that same keystroke, landing the letter in the field.
- **7.2 window management**: tiling (react-grid-layout) stays the base; maximize is
  a CSS class (`!important`, since RGL sets `position`/`transform` inline) on the
  same grid-item DOM node — no remount, no WS reconnect. Minimize is `display:none`
  on that node, not unmount, so the widget's WS/store stay live (Exposé cards and
  desktop notifications keep working while hidden). Exposé and the dock strip read
  straight from the Zustand store; zero new connections.
- **7.3 continuation**: `session.continued_from_id` (V11). `HandoffService`
  (mirrors `GitAssistService`'s digest→system-turn shape) runs one system-session
  turn over `TranscriptDigest.render()` — the same capped digest reflection uses —
  producing a ~1-2 KB Markdown brief (`POST /api/sessions/{id}/handoff-summary`);
  the picker's "full transcript" checkbox instead calls the existing uncapped
  `export.md` (5.9). Either way the text lands unsent in the new session's compose
  box (`CreateSessionDialog`'s `onCreated(id, draftInput)`, the ticket-import
  precedent) — never auto-fired.
- **7.4 orchestration**: `session.parent_session_id` (V11) plus four `@McpTool`
  beans (`OrchestrationMcpTools`) on the *same* in-process MCP server as memory
  (Spring AI autoconfigures exactly one server per app). This forced narrowing
  `allowedTools`' memory grant from the blanket `mcp__memory` server-level entry to
  the three read-only tool names (`mcp__memory__memory_{tags,search,read}`), so
  `spawn_child_session` on that same server flows through the normal
  tool-permission prompt instead of inheriting the pre-approval. Depth 1 (no
  grandchildren) and the per-parent `MAX_CHILDREN` cap are enforced inside the tool
  bodies, not by hiding tools per session — the server's tool list is static and
  application-wide. `report_result` journals `child_reported` on both sessions
  (`SessionEventBus.publish`, same journal-then-fan-out shape as `SessionService`'s
  private `record()`) and delivers the tagged summary into the parent's queue via
  the already-public `SessionService.sendUserMessage` — no bespoke wake logic
  needed, PARKED parents already transparently wake on enqueue. `GitWorktreeService.
  findRepos()`/`defaultBranch()` are shared between `list_services` and
  `MetaController`'s pre-existing (global-root) `/api/repo/services`, parameterized
  by `Path` so the tool can scan a *session's* `ecosystemPath` instead.

## 4. Backlog implementation sketches (5.4–5.13)

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
`GET /api/sessions/{id}/export.md` (`SessionController`) renders the journal via
`TranscriptDigest.renderMarkdown()` — user/assistant turns with timestamps,
collapsed tool-call summaries, reflection events, a cost+model footer —
`text/markdown` with a `Content-Disposition` filename. No schema changes. The
dashboard has no kebab menu (actions are inline header buttons), so the download
is a ⬇ button there instead: since a bearer-token API response can't be linked to
directly from `<a href>`, the frontend fetches the text itself and saves it via a
`Blob` + temporary anchor, same shape as any other authenticated action.

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
The proof of provider-agnosticism. Design doc with the full capability/permission-mode
mapping and rationale: `docs/plan/phase-5.13-codex-provider.md`. Summary: new
`sidecar-codex/` speaks `codex app-server`'s JSON-RPC-over-stdio protocol (not `codex
exec`, which is non-interactive and can't do the approval round-trip), translated to
adapter protocol v1 — approval requests to `permission_request`, its thread id to
`providerSessionId`. Registered under `claude-ui.providers.codex`; the dashboard needs
zero code that branches on the provider name — only on capabilities, same as the
Claude adapter already requires. Reduced capabilities set vs. Claude: no plan mode, no
`acceptEdits`, no custom agents (confirmed no Codex equivalent exists at all — not
deferred). Skills and MCP are supported (a same-day follow-up flipped both from
out-of-scope once confirmed live-feasible): skills via `skills/extraRoots/set`
pointing at the same materialized `.claude/skills/` Claude sessions use; MCP via
`thread/start`'s `config.mcp_servers`, with bearer tokens passed as a named env var on
the spawned child rather than an inline header (Codex's own auth mechanism differs
from Claude's).

## 5. Operational notes

Limits/caps and env vars: see CLAUDE.md "Limits & caps". Logs: `logs/claude-ui.log`,
`logs/error.log`, `logs/sidecar/<sessionId>.log`. Auth: bearer token everywhere
(REST header, WS subprotocol), startup guard refuses tokenless non-loopback binds.
Metrics: `claudeui.sessions.active|parked` via `/actuator/metrics` (token-gated).
