# Phase 4 — Lifecycle Safety & Hardening

> Implementation notes (as built): file logging added beyond plan — Logback rolling
> `logs/claude-ui.log` + `logs/error.log` (stack traces) + per-session sidecar stderr
> at `logs/sidecar/<id>.log`. Auto-title runs a one-shot `claude -p --model haiku`
> (user's CLI login, no API key needed) and only ever renames a session still carrying
> its default (= branch) name. Parking clears the worktree PID file on process exit.
> Not exercised live (code-reviewed only): PID-orphan reap after a `kill -9`'d
> backend, and real provider rate-limit surfacing (cannot be triggered on demand).

**Goal:** make the system boringly reliable: clean shutdown paths, orphan cleanup,
resource limits, error surfacing, and journal hygiene. Mostly backend work with small
UI touches. This phase turns the demo into a daily-driver.

**Estimated effort:** ~1–1.5 days.

## Tasks

### 4.1 Shutdown & orphan safety
- `@PreDestroy`: `shutdown` command to every live sidecar → wait up to 5 s → `destroy()`
  → 2 s → `destroyForcibly()` on the handle **and all descendants** (Claude tool
  subprocesses). Verify no `node`/`claude` processes survive a backend stop.
- Startup sweep: sessions in live DB states are marked CRASHED (Phase 2 policy) —
  extend with a PID-file check per session (`<worktree>/.claude-ui.pid`): if a recorded
  PID still runs a sidecar, kill it before marking CRASHED (protects against orphans
  from a `kill -9`'d backend).
- Worktree sweep on startup: `git worktree list --porcelain` entries under
  `worktree-root` with no matching non-CLOSED session → logged and listed in a new
  `GET /api/maintenance/orphans`; explicit `POST /api/maintenance/orphans/clean`
  removes them (never auto-delete silently).

### 4.2 Limits & backpressure
- Enforce `max-sessions` on create **and** resume.
- Journal size guard: per-session event count/size metric; `stream_delta` coalescing —
  on `assistant_message` completion, replace that message's delta rows with the final
  block (single DELETE+state marker) so long sessions don't grow unbounded. Replay
  falls back to whole messages for coalesced turns (acceptable fidelity loss after the fact).
- Tool output truncation already at 16 KB (sidecar); add journal payload hard cap (64 KB)
  with `truncated` flag as defense in depth.

### 4.3 Error surfacing
- API-level errors from the SDK stream (rate limits, auth expiry, overloaded) →
  distinct `error` payload codes → UI toast + inline transcript marker instead of a
  silent stall. Rate-limit errors show retry-after when available.
- `claude` CLI not found / not authenticated at spawn → FAILED state with actionable
  message ("run `claude` once as <user> to log in").
- Stderr tail (last 100 lines, ring buffer) attached to every CRASHED transition.

### 4.4 Observability
- Micrometer counters/gauges: active sessions, events/sec, journal rows, per-session
  cumulative cost; exposed via actuator (already on the LAN behind the token filter —
  verify actuator endpoints are covered by auth, lock down to `health,metrics`).
- Structured log line per lifecycle transition with sessionId.

### 4.5 Cost budget enforcement
- `cost_budget_usd` per session (nullable = unlimited): backend tracks cumulative
  `turn_complete.costUsd`; crossing the budget lets the in-flight turn finish, then
  refuses new turns (including queued ones) with a `budget_exhausted` event. Widget
  shows a budget bar near the cost chip; the user can raise the budget from the kebab
  menu (`PATCH /api/sessions/{id}` with the new value).

### 4.6 Auto-titling
- After the first `turn_complete`, if the session still has its default name, a cheap
  Haiku call (direct Anthropic API or a one-shot `claude -p`) generates a ≤6-word
  title from the first exchange; stored via the normal update path, broadcast as a
  `session_renamed` event. Failure is silent (name stays).

### 4.7 Idle session parking
- A session IDLE for `idle-park-minutes` (config, default 30) gets a clean sidecar
  `shutdown` → state PARKED (Node process gone, worktree and journal untouched).
- Any incoming `user_message` (or queue dispatch) to a PARKED session transparently
  respawns the sidecar with `--resume <providerSessionId>` and then delivers the message;
  the widget shows a brief "waking…" indicator. PARKED sessions don't count toward
  `max-sessions`' *process* budget but their worktrees remain.

### 4.8 UX touches
- Cumulative cost in widget header (sum of `turn_complete.costUsd` from journal).
- "Session age / last activity" in header tooltip; idle-session visual dimming.
- Confirm-on-tab-close if any session is RUNNING.

## Out of scope
- Auto-restart/auto-resume policies (explicit user action stays the rule);
  multi-user; TLS automation.

## Definition of Done
- [ ] Backend stop (Ctrl-C and `kill`) leaves zero `node` sidecar or tool child processes.
- [ ] Backend `kill -9` + restart: orphan sidecars detected via PID files and killed; sessions CRASHED and resumable.
- [ ] Orphan worktree listed by maintenance endpoint and removed only via explicit clean call.
- [ ] Delta coalescing: a long session's journal shrinks after each completed message; replay still renders correct transcripts.
- [ ] Simulated rate-limit / auth error appears as a visible widget error, not a hang (test by revoking auth or a mock sidecar emitting the error event).
- [ ] `claude` unauthenticated spawn produces FAILED with the actionable message.
- [ ] `max-sessions` enforced across create+resume combinations.
- [ ] Actuator restricted to `health,metrics` and behind the token; metrics show live session gauge.
- [ ] Cumulative cost visible per widget and correct against summed `turn_complete` events.
- [ ] Budget: a session with a $0.05 budget finishes its in-flight turn, then refuses the next with `budget_exhausted`; raising the budget from the widget unblocks it.
- [ ] Auto-title: an untitled session gets a sensible short name after its first turn; a user-renamed session is never overwritten.
- [ ] Parking: an idle session parks after the configured timeout (no Node process); sending a message wakes it with context intact; parked sessions free process slots under `max-sessions`.

## Manual test script

| # | Action | Expected |
|---|---|---|
| 1 | 2 active sessions → Ctrl-C backend → `pgrep -f sidecar` | no processes; DB states CRASHED |
| 2 | Start 2 sessions, `kill -9` backend JVM, restart | startup log shows orphan kill via PID files; widgets show CRASHED + Resume |
| 3 | `git worktree add` a stray tree under worktree-root, restart | listed in `/api/maintenance/orphans`; clean call removes it |
| 4 | Long multi-turn session; inspect `session_event` counts before/after messages complete | delta rows coalesced; F5 replay still correct |
| 5 | Point sidecar env at bad credentials, send prompt | widget shows auth error marker, session recoverable |
| 6 | Create sessions up to `max-sessions`, then one more; also try resume beyond limit | 409 both ways |
| 7 | `curl /actuator/env` without token | 401/404; `metrics` with token works |
| 8 | Compare header cost vs `SELECT sum((payload->>'costUsd')::numeric) …` | matches |
| 9 | Create session with `costBudgetUsd: 0.05`, run turns past it | `budget_exhausted`; raise budget via kebab menu → next turn works |
| 10 | New session, one exchange, wait | header shows generated title; rename manually, next turn does not overwrite |
| 11 | Set `idle-park-minutes: 1`, wait; `pgrep -f sidecar` | no process, state PARKED; send message → "waking…", correct contextual answer |
