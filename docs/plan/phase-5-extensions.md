# Phase 5 — Extensions Backlog

Not a single phase but a prioritized backlog; each item is independently shippable
after Phase 4 and carries its own mini-DoD. Order below is the suggested order.

## 5.1 Git panel per widget — ✅ shipped 2026-08-24
Status/diff/log view for the session's worktree; commit + push from the UI.
- Backend: `GET /api/sessions/{id}/git/status|diff|log`, `POST …/git/commit`, `…/push`.
- **DoD:** edit → see diff → commit → push round-trip from a widget; refuses to touch
  a RUNNING session's worktree mid-tool-execution.

## 5.2 PR creation — ✅ shipped 2026-08-24 (needs `gh auth login` once)
"Open PR" button: pushes branch and creates a PR via `gh pr create` (uses the user's
existing `gh` auth), with title/body prefilled from session summary.
- **DoD:** one click yields a real PR URL shown in the widget.

## 5.3 Long-term memory / RAG on pgvector
The reason the Postgres image ships pgvector. Design sketch (to be refined in its own doc):
- `V3__memory.sql`: `CREATE EXTENSION vector;`
  `memory_chunk(id, session_id, source TEXT, content TEXT, embedding vector(1024), ts)`.
- Ingestion: on `turn_complete`, summarize/chunk the turn (cheap model via sidecar or
  direct API call) and embed (e.g. voyage-3 or any local embedder — decision point).
- Retrieval: on new session creation (opt-in per template), top-k similar chunks across
  past sessions injected via `--append-system-prompt` or a context file in the worktree.
- Also usable for cross-session search in the UI ("where did I solve X?").
- Journal retention policy is decided here too: raw events of CLOSED sessions can be
  pruned after N days *once ingested into memory* — until this item lands, keep everything.
- **DoD:** ask in a fresh session about a decision made in a closed session → answer
  reflects the retrieved memory; UI search over past transcripts returns ranked hits.

## 5.4 Session config templates v2
Building on Phase 2 templates: per-template default base branch, env var sets,
MCP server bundles, and a "duplicate session" action.
- **DoD:** create a session from a template with MCP servers attached and verify the
  servers appear in `system_init`.

## 5.5 Model switching mid-session — ✅ shipped 2026-08-24
SDK supports changing model between turns; expose a model picker in the widget header.
- New `set_model` sidecar command / `model_changed` event (mirrors `set_permission_mode`),
  gated by a new `modelSwitch` capability. `turn_complete` now also carries `model`
  (tracked sidecar-side from the preceding `system_init`, which fires every turn).
  `session.model` is kept current from both `system_init` and `model_changed` so a
  later resume/respawn relaunches with the last active model, not the create-time one.
  Widget header model chip is now click-to-cycle (sonnet → opus → haiku), same
  interaction pattern as the permission-mode chip.
- **DoD:** switch sonnet→haiku mid-conversation; next `turn_complete` shows new model + cheaper cost.

## 5.6 Mobile / small-screen layout
Single-column stacked widgets, sticky input bar; PWA manifest so it installs on a phone.
- **DoD:** run a session end-to-end (incl. approving a permission) from a phone browser.

## 5.7 Multi-repo support — ✅ largely shipped early (Phase 3)
Per-session service picker (git repos discovered under `ecosystem-root`) and editable
per-session ecosystem path landed with Phase 3. Remaining ideas for later: multiple
ecosystem roots, repos outside the ecosystem folder, per-service default templates.

## 5.8 Per-session hooks / settings injection
Materialize a `.claude/settings.json` into the worktree from template config (e.g.
PostToolUse format-on-edit hooks). Deliberately deferred: easy to confuse with
repo-owned settings; needs a merge story if the repo ships its own.
- **DoD:** a template-defined PostToolUse hook runs on edits in a fresh session
  without touching the repo's committed settings.

## 5.9 Transcript export
`GET /api/sessions/{id}/export.md` — journal rendered to Markdown (turns, tool calls
collapsed, costs footer); download button in the widget kebab menu.
- **DoD:** exported file renders correctly in a Markdown viewer and matches the transcript.

## 5.10 Turn-level checkpoints & rewind
Auto-commit the worktree after each `turn_complete` onto a session ref
(`refs/claude-ui/<session>/turn-<n>`), "Rewind to here" per turn block resets the
worktree (respecting the dirty-close flow). Worktree isolation makes this safe.
- **DoD:** make 3 turns of edits, rewind to turn 1, files match; forward history recoverable from refs.

## 5.11 Prompt fan-out
One prompt → N new sessions on N branches from the same template ("try 3 approaches"),
side-by-side compare view of diffs/costs; create API accepts batch (the Phase 2
endpoint should keep its request shape array-friendly to not preclude this).
- **DoD:** fan out one prompt to 3 branches; compare view shows 3 diffs; pick one, close others.

## 5.12 Usage dashboard — ✅ shipped 2026-08-26
Costs page: per day / per session / per model, from `turn_complete` events (SQL + one chart).
- **DoD:** totals match the SQL sum; filters by date range and model work.
- One cross-session query (`EventJournal.usageSince`, joined to `session` for names) returns raw
  per-turn `{sessionId, sessionName, ts, model, costUsd}` rows for a lookback window (`GET
  /api/usage?months=`); day/month grouping, date-range preset, and model filter are all applied
  client-side in the new `UsageDashboard.tsx`, which also renders a prominent "this month" total
  (costs reset monthly) and by-session/by-model breakdown tables. Chart is a hand-rolled SVG bar
  chart (no new npm dependency), built per the `dataviz` skill's mark specs.
- Folded in the housekeeping reminder this exercise surfaced: `GET /api/usage/stale-sessions`
  lists non-system PARKED/CRASHED/FAILED sessions untouched for 3+ days (fixed constant) whose
  worktree may still be on disk, with a dirty-check per candidate; the dashboard's housekeeping
  section reuses the existing `CloseDialog` unchanged (it only needs a `sessionId`) to close them —
  no new close/delete logic. Topbar 📊 button shows a count badge when any exist.

## 5.13 Codex CLI provider adapter
Second implementation of the adapter interface wrapping OpenAI's Codex CLI: maps its
headless/approval semantics onto our NDJSON contract, announces its (smaller)
capabilities set, registered under `claude-ui.providers.codex`. The exercise that
proves the backend/UI are genuinely provider-agnostic.
- **DoD:** a `provider: codex` session runs a prompt with tool approval end-to-end in
  the same dashboard; unsupported controls (e.g. plan mode) simply don't render;
  Claude sessions are entirely unaffected side by side.

## 5.14 Notifications — ✅ shipped 2026-08-24 (browser desktop notifications: finished / needs input / crashed, unfocused tab only)
Browser notifications (or ntfy/webhook) when a session hits WAITING_INPUT or
CRASHED while unwatched — the whole point of parallel sessions is not staring at them.
- **DoD:** permission request in a background tab raises a desktop notification;
  clicking it focuses the widget.

## 5.15 System sessions + Linear ticket import — ✅ shipped 2026-08-24
Backend-initiated tasks (ticket import today; PR-comment generation etc. later) reuse
the normal session/sidecar machinery — a new `session.kind` column (`user` | `system`)
tags them, giving each task a real transcript instead of an opaque subprocess call.
- **Exactly one system session** at a time: `SessionService.getOrCreateSystemSession()`
  (find a non-CLOSED/FAILED `kind='system'` row, wake/resume it, or create fresh) and
  `runSystemTurn(prompt, timeout)` are both serialized by a single lock, so concurrent
  callers queue behind each other rather than racing to create a second one or mixing
  up whose `turn_complete` is whose. The conversation is deliberately never reset
  between unrelated tasks — it doubles as an audit log — with Close-from-the-widget as
  the manual reset valve if context grows stale.
- No git worktree: a system session's `--cwd` is a plain scratch directory
  (`<worktree-root>/_system/<id>/`), `repo_path`/`branch`/`base_branch` are sentinel
  values, and `close()` skips the dirty-check/worktree-removal path for it.
- Global integration config is env-var only (`CLAUDE_UI_LINEAR_API_KEY`, no DB/UI
  storage — matches the app's existing "secrets live in env vars" convention): when
  set, the system session is spawned with Linear's remote MCP server
  (`https://mcp.linear.app/mcp`, static Bearer-token auth, no OAuth flow) attached.
- `POST /api/tickets/import {ticketRef}` runs one Haiku turn on the system session
  instructing it to fetch the ticket via Linear's MCP tools and return
  `{branchName, prompt}` as JSON; `CreateSessionDialog` prefills the branch field and a
  new freeform "Initial prompt" textarea (a literal `kickoffPrompt` override, additive
  to the existing template+placeholder mechanism, not a replacement for it).
- Dashboard hides `kind='system'` sessions by default; a topbar toggle (🤖, with a
  live count) reveals them in the same widget grid via the existing `SessionWidget`
  (Git panel button hidden for them — no real repo underneath).
- **DoD:** with `CLAUDE_UI_LINEAR_API_KEY` set, importing a ticket prefills branch +
  prompt in the create dialog; a second import reuses the same system session (no
  second row created); the session is invisible by default and appears under the 🤖
  toggle with a full transcript of every import call.

**Addendum 2026-08-25:** the OAuth toggle moved from `CLAUDE_UI_LINEAR_OAUTH` (env
var, restart required) to a persisted setting — new `app_setting(key, value)` table
(`V4__app_settings.sql`), `SettingsService`/`SettingsRepository`, `GET`/`PATCH
/api/settings`, edited from the Settings dialog. `systemMcpConfig()` reads it on
demand at system-session-creation time, so a toggle takes effect on the next ticket
import with no backend restart. `CLAUDE_UI_LINEAR_API_KEY` (the actual secret) is
untouched — still env-only, never written to `app_setting`. Also added a second
persisted setting, `ticket-import.spec`: free text appended to the Haiku prompt that
generates a ticket's `branchName`/`prompt` (e.g. branch-naming conventions). No
seed-from-env migration for either — pre-launch, single user, not needed.
