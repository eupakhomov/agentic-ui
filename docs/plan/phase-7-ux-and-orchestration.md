# Phase 7 — Dashboard UX & Multi-Service Orchestration

Four independently shippable items (phase-5 backlog format: each carries its own
decisions, tasks, DoD, and manual test): keyboard-first control (7.1), window
management (7.2), session continuation/handoff (7.3), and parent/child
multi-service orchestration (7.4). 7.1+7.2 are pure-frontend siblings; 7.3+7.4
share a lineage migration (V11) and both build on machinery 5.3 already proved
(TranscriptDigest, `runSystemTurn`, the in-process MCP server). Suggested order:
7.1 → 7.2 → 7.3 → 7.4.

Decisions settled up front (2026-09-06, with the user):

1. **7.3 transfers context as an AI handoff summary by default** (one system-
   session turn over the transcript digest — the /compact pattern), with a
   checkbox to inject the raw digest instead when fidelity beats context budget.
2. **7.4 children push results**: a `report_result` MCP tool delivers the child's
   summary into the parent's message queue (waking a PARKED parent via the
   existing queue/wake semantics) — the parent is event-driven, never polling.
3. **7.2's Exposé renders live store-backed cards**, not scaled widget DOM: the
   Zustand store already holds every open session's transcript (fed by the
   per-widget WS), so cards are genuinely live with zero extra connections.
4. **7.4 children stay open after reporting** — the human reviews each child's
   diff and closes it through the normal dirty-close flow; the parent never
   destroys work. Consistent with the app's human-in-the-loop posture.

---

## 7.1 Keyboard shortcuts

No global key handling exists today (only the composer's Enter-to-send). Add a
document-level `keydown` handler in `Dashboard` plus a **focused widget**
concept: `focusedId` in the store, set by click or by hotkey, shown as a focus
ring on the widget. Single-key Gmail/Linear-style bindings — no browser-reserved
modifier combos.

**Suppression rules** (the whole feature lives or dies on these): ignore keys
when the event target is an input/textarea/select/contentEditable, while an IME
composition is active, and while any dialog is open — except `Esc`, which closes
dialogs / blurs the composer / exits maximize/Exposé, in that priority order.

**Proposed map** (static v1 — rebinding UI is out of scope):

| Key | Action |
|---|---|
| `?` | hotkey cheatsheet overlay |
| `n` | new session dialog |
| `j` / `k` (also `]` / `[`) | focus next / previous widget |
| `1`–`9` | focus widget N in grid order |
| `Enter` or `i` | focus the focused widget's composer |
| `y` / `d` | approve / deny the focused widget's oldest pending permission request |
| `g` | toggle the focused widget's git panel |
| `f` | maximize/restore focused widget (7.2) |
| `x` | minimize focused widget (7.2) |
| `e` | Exposé overlay (7.2) |
| `m` `l` `u` `t` `,` | memory / library / usage / templates / settings dialogs |
| `Esc` | close dialog → blur composer → exit maximize/Exposé |

`y`/`d` is the biggest win — the approval workflow is this app's hot path.
Deliberately no interrupt hotkey in v1 (too destructive for a single
unmodified key; the ⏹ button stays).

**Tasks**: store gains `focusedId`; `useHotkeys` hook (one listener, suppression
rules, map dispatch); focus ring CSS; cheatsheet overlay (`?`); wire `y`/`d` to
the existing `permission_response` send path (oldest pending request in the
focused widget's transcript view state).

- **DoD:** a full session round-trip with the mouse untouched: `n` → create →
  widget auto-focused → `Enter` → type a prompt that triggers a permission →
  `y` to approve → `f` to maximize → `Esc` to restore. Typing `n` inside the
  composer inserts the letter, opens nothing.

**Manual test**: run the DoD flow; open each dialog by key and close with `Esc`;
verify `1`/`2` focus the right widgets after dragging them to swap grid spots
(order = grid layout order, not creation order); verify keys are inert while the
create dialog is open except `Esc`.

## 7.2 Window management (maximize / minimize / Exposé)

The tiling grid (react-grid-layout) stays the base model — no free-floating
z-ordered windows (that's a rewrite of the layout layer; explicitly out of
scope). "Bring one to front" = maximize.

- **Maximize**: `maximizedId` state; the widget's existing grid-item DOM node
  gets `position: fixed; inset: 8px; z-index` styling — **no remount**, so the
  widget's WS connection is untouched (no disconnect/replay flash). Toggle via
  `f`, a header button, or double-clicking the header; `Esc` restores.
- **Minimize**: `minimizedIds` persisted in localStorage (like the layout).
  Minimized widgets are **hidden, not unmounted** (`display:none` on the grid
  item): their WS stays connected, so desktop notifications still fire, the
  store keeps updating (Exposé cards stay live), and restore is instant with no
  replay. A bottom **dock strip** shows a chip per minimized session — state
  dot, name, cost — click to restore; the chip pulses on WAITING_INPUT.
- **Exposé** (`e`, topbar button): full-screen overlay with one live card per
  open session — state dot, name, branch/repo chips, cost, last ~3 transcript
  lines from the store, highlighted border when a permission is pending.
  Includes minimized sessions. Click a card → restore/focus it (and exit
  Exposé). All data comes from the store; zero new connections.

**Tasks**: store `maximizedId`/`minimizedIds` + persistence; grid-item CSS
states; dock strip component; Exposé overlay component; header buttons; hotkey
wiring (from 7.1).

- **DoD:** maximize and restore with no WS reconnect (no "disconnected —
  reconnecting…" overlay flash); a minimized session's permission request still
  raises a desktop notification and pulses its dock chip; Exposé cards update
  live while a hidden session streams; minimized set and layout survive reload.

**Manual test**: two live sessions; minimize one, prompt it (via wake from the
dock or before minimizing) into a permission request → notification + pulsing
chip; open Exposé while the other streams → its card's last lines update live;
maximize during streaming → text keeps flowing, no reconnect; reload → same
minimized/maximized state gone (maximize is transient) but minimized set kept.

## 7.3 Continue from a previous session (handoff)

Start a new session carrying a prior session's context — browsable over roughly
the last 50 sessions, any state including CLOSED.

- **Backend** — `POST /api/sessions/{id}/handoff-summary`: renders
  `TranscriptDigest.render()` (the capped digest that already feeds reflection)
  and runs one system-session turn (ticket-import pattern, synchronous) with a
  handoff prompt: goal, current state, decisions made, next steps, gotchas —
  target ~1–2 KB of Markdown. `GET .../export.md` (5.9) already serves the raw
  digest side. New V11 column `continued_from_id UUID` (no FK cascade — lineage
  outlives the source row's future) records provenance.
- **Create dialog** — a "Continue from…" picker: recent sessions newest-first
  (client-capped at 50; search by name/branch; shows provider, repo, state,
  cost, closed date). Selecting one: (a) prefills repo/provider/model/permission
  mode from the source (the `duplicate()` config-copy logic, reused), (b)
  fetches the handoff summary — or the full digest when the "full transcript"
  checkbox is set — and (c) lands it **unsent in the Initial-prompt textarea**,
  editable before sending, exactly the ticket-import precedent (a reviewed
  draft, never an auto-fired turn). Sets `continuedFromId` on create.
- **Widget** — an ↩ chip ("continued from <name>") when `continuedFromId` set.
- **Pruning interaction** (5.3 retention): a session whose journal was pruned
  (`lastSeq == 0`) has nothing to hand off — the picker shows it disabled with
  a "journal pruned" note. The backend endpoint returns 409 for it.

**Tasks**: V11 migration (shared with 7.4); handoff-summary endpoint + prompt;
picker dialog; create-dialog wiring + `continuedFromId` through
create/entity/repository; ↩ chip; pruned-session guard.

- **DoD:** close a session that made a distinctive decision → "Continue from" it
  with the summary default → the new session, asked about that decision before
  doing anything else, answers correctly from the handoff draft alone. The
  full-digest checkbox injects the raw digest instead. A retention-pruned
  session appears disabled in the picker and the endpoint refuses it.

**Manual test**: seed a closed session containing a unique fake token; continue
via summary → token knowledge survives (or is absent if the summary dropped it —
then retry with full digest, which must contain it verbatim); verify the draft
is editable and nothing auto-sends; verify provider/model prefill matches the
source; prune a session (retention-days trick from the 5.3 script) → picker
disables it.

## 7.4 Multi-service child sessions (fan-out under the ecosystem)

For tasks spanning several services: mid-session, the parent agent decomposes
work and spawns one child session per affected service (discovered under the
session's `ecosystemPath`), each in its own worktree/branch on its own repo;
children report back and the parent synthesizes. Distinct from 5.11 prompt
fan-out (N alternatives on ONE repo, compare-and-pick) — this is decomposition
across DIFFERENT repos; 5.11 stays on the backlog unchanged.

**Transport — same in-process MCP server as memory** (5.3 decision 12a), new
`@McpTool` beans; Spring AI autoconfigures exactly one server per app, so a
second endpoint isn't free, and a second one isn't needed. One prerequisite fix:
today Claude sessions pre-approve the whole server via `allowedTools =
[mcp__memory]` — that blanket grant would silently auto-approve `spawn` too.
**Narrow it to the three read-only tools** (`mcp__memory__memory_tags`,
`__memory_search`, `__memory_read`); the orchestration tools then flow through
the normal permission prompt, which *is* the human gate on spawning — in
`default` mode every spawn is explicitly approved, no new approval mechanism.
(Accepted cosmetic wart: orchestration tools live under the `memory` mcpConfig
entry key; renaming the key would orphan the stored configs of existing
sessions for no functional gain.)

**Tools** (all take `sessionId`, 5.3 decision 12b pattern; injected only for
sessions that are not themselves children — depth 1, no grandchildren):

| Tool | Behavior |
|---|---|
| `list_services` | services under the session's `ecosystemPath` (reuses `/api/repo/services` logic); error if no ecosystem configured |
| `spawn_child_session` | `servicePath, branch, prompt, model?` → creates a session (provider inherited from the parent, `parent_session_id` set, normal worktree/branch on that service's repo). The child's kickoff prompt = the given prompt + an injected coda: *"You are a child session of <parent> working on <service>. When your task is done, call report_result with a concise summary."* Returns the child id. Refused with a clear tool error at the `CLAUDE_UI_MAX_SESSIONS` cap, above MAX_CHILDREN (fixed internal, 5) per parent, or when the caller is itself a child |
| `check_children` | id/name/service/state/cost/reported? per child — for stragglers and recovery, even though the primary flow is push |
| `report_result` | child-only (caller must have a parent): journals `child_reported` on both sessions and enqueues *"[child report — <name> / <service>]: <summary>"* into the **parent's** queue via the existing `sendUserMessage` path — a live-IDLE parent gets it dispatched immediately, a PARKED parent is transparently woken. The child then idles/parks like any session and **stays open for human review** |

**Backend**: V11 adds `parent_session_id UUID` (+ 7.3's `continued_from_id`);
entity/repository plumbing; the tools bean; allowedTools narrowing; `PROTOCOL.md`
entry for `child_reported`. Children are ordinary `kind='user'` sessions —
widgets, approvals, git panel, close flow all just work; the dashboard shows a
⑂ chip ("child of <parent>") and 7.2's dock/Exposé keep a parent + N children
manageable.

**Cap interplay**: default `CLAUDE_UI_MAX_SESSIONS=4` fits a parent + 3 children
exactly; document raising it for wider fan-outs. A PARKED parent doesn't count
against the cap (existing rule), so the practical pattern — parent spawns, ends
its turn, parks, children work, reports wake it — fans out wider than the naive
count suggests. Budgets stay per-session (`costBudgetUsd` is per child via the
spawn call's session config defaults); the usage dashboard already aggregates.

- **DoD:** a parent session on an ecosystem repo, told to make a cross-service
  change, calls `list_services`, spawns 2 children (each spawn surfaces a
  permission prompt in `default` mode), and ends its turn; both children do real
  work in their own worktrees and call `report_result`; the parked parent wakes
  twice, synthesizes both reports into a final answer; both children are still
  open for review afterwards. A child calling `spawn_child_session` is refused
  (depth 1). Spawning at the session cap returns a readable tool error, not a
  crash. Memory read tools are still pre-approved on Claude sessions after the
  allowedTools narrowing; spawn is not.

**Manual test**: ecosystem root with ≥3 sibling repos; create parent with
reflection off, `default` mode; prompt: "add field X to service A's API and
consume it in service B — use child sessions". Approve the two spawns; watch
children appear as ⑂ widgets; approve their edits; on each `report_result`
verify the parent (parked by then) wakes and receives the tagged report
message; parent's final turn references both. Then: ask the parent to spawn a
third child while 4 sessions are live → in-transcript tool error about the cap;
ask a child to spawn → refused. Check `child_reported` events in both journals.

---

## Cross-cutting

- **Migration**: one `V11__session_lineage.sql` — `continued_from_id UUID`,
  `parent_session_id UUID` on `session` (no FK cascades; lineage is
  informational and outlives rows).
- **New REST**: `POST /api/sessions/{id}/handoff-summary`. Everything else in
  7.4 is MCP tools on the existing server; 7.1/7.2 are frontend-only.
- **Docs on ship**: CLAUDE.md (hotkeys pointer, MAX_SESSIONS note for fan-out,
  fixed-internals MAX_CHILDREN), PROTOCOL.md (`child_reported`),
  ARCHITECTURE.md, this file's checkmarks, decision-log rows.
- **Out of scope**: hotkey rebinding UI; free-floating windows; true scaled-DOM
  Exposé; cross-provider handoff *resume* (handoff is text injection, so it
  already works across providers by construction); grandchildren (depth >1);
  automatic child cleanup; multi-parent DAGs.
