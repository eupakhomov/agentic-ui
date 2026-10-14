# Phase 16 — Session sidebar & focus mode

Status: **landed (2026-10-15)**. Decisions 1–4 and proposals P1–P8 were confirmed
as written; see the decision log for what changed on contact with the code. Edit sites were checked against the code as of commit `77e3890`.

## Background

The dashboard is a tiling grid (react-grid-layout). Two things are poor today:

- **Status at a glance.** Phase 7.2 added the bottom dock strip, which covers minimized
  sessions only, and Exposé, which is a modal overlay. To see what every session is
  doing, or to see which one is blocked on you, you have to scan widget headers or open
  Exposé. That gets worse past four sessions.
- **One session at a time.** Maximize (`f`) is temporary. It isn't persisted, and
  switching to another session means restore, move focus, then maximize again.

Orca (onorca.dev), a comparable desktop tool, solves both with a persistent left sidebar
and one main session view. This phase adapts that layout without giving up the grid:

- A **collapsible left sidebar** lists every open session with its state, time in that
  state, cost and PR status. A pinned **Needs you** section lets you approve or deny a
  plain tool permission inline.
- A **focus layout mode** shows one session at full size. The sidebar is how you switch
  sessions, so there's no separate tab strip.

**Frontend only.** No backend, migration, sidecar or protocol change. Everything the
sidebar shows is already in the Zustand store or in WS envelopes:

- Each session's `views[id]` is filled by its own widget's WS.
- Envelopes carry `ts`, set in `SessionWebSocketHandler`, for replayed events as well as
  live ones.
- Approvals already have an imperative path: `widgetRegistry.respondPermission`, which the
  `y`/`d` hotkeys use.

## Confirmed decisions (2026-10-15)

1. **No tab strip.** The sidebar is the session list. Tabs would duplicate it.
2. **Two layout modes, `grid` and `focus`**, toggled by a topbar button and a hotkey, and
   persisted (`agentic-ui.layoutMode`). The grid stays the default and is unchanged.
3. **Every widget stays mounted in both modes.** In focus mode the non-active widgets are
   hidden with `display:none`, using the same mechanism as minimize. Their WS connections,
   notifications and store updates keep running, and switching sessions needs no replay.
   This extends the phase-7 decision that tiling is the base and hidden means mounted.
4. **The sidebar replaces the dock strip.** Minimized sessions appear in the sidebar,
   dimmed, and clicking one restores it. `DockStrip.tsx` and its CSS are deleted. Exposé
   stays.

## Proposals (confirmed as written 2026-10-15)

### P1 — Sidebar shape

- **Three fold states**, cycled by a hotkey and a topbar button, and persisted
  (`agentic-ui.sidebar`):
  - `expanded`: about 260px wide, full rows.
  - `rail`: about 44px wide. It shows a state dot per session (in sidebar order) and a
    Needs-you count badge at the top. Hovering a dot shows the session's name as a
    `title`.
  - `hidden`: no sidebar.

  The default is `expanded`, or `rail` when the window is narrower than 1100px.
  Drag-to-resize is out of scope.
- **Layout shell.** Body becomes a CSS grid: `sidebar | main`. The topbar spans both
  columns and stays sticky. The sidebar is `position: sticky` under the topbar, scrolls
  on its own and uses `--panel` with a right `1px --border`.
- **Grid width.** Grid width currently comes from `window.innerWidth - 24`
  (`Dashboard.tsx` `width` state). It must subtract the sidebar's width. The cleanest way
  is a `ResizeObserver` on `.grid-wrap` in place of the window `resize` listener, so the
  grid follows fold changes without a reload.

### P2 — Sidebar rows (expanded)

- **Needs you** section, pinned at the top. It holds every session with a
  `pendingPermission`, oldest first by `stateSince`. It's hidden when empty. Each row
  shows:
  - The session name and the tool name.
  - A one-line, mono, ellipsized summary of the request: the command for `Bash`,
    `file_path` for `Edit`/`Write`/`Read`, and the first 80 characters of
    `JSON.stringify(input)` otherwise.
  - Actions:
    - Plain tool permission: **Allow** (primary) and **Deny**, sent through
      `getWidget(id).respondPermission(requestId, permissionResponseFor(...))`. This is
      the exact path the `y`/`d` hotkeys use.
    - Plan approval (`ExitPlanMode`) and `AskUserQuestion`: **Open** only, which focuses
      the session and scrolls to its card. Approving a plan you haven't read, or
      answering a question you haven't seen, from a one-line row would be a footgun.
  - No edit-before-allow here. That stays on the full card.

  The row's buttons disable and `.pulse` while a response is in flight. It clears when
  the store's `permission_response` reducer nulls `pendingPermission`.
- **Sessions** section, **grouped by service** (`servicePath` basename, like the widget
  chip), groups sorted by name, and sessions within a group in creation order. Creation
  order is the order of the `sessionIds` array, so rows never jump around as states
  change.
  - Child sessions (`parentSessionId`) nest one level under their parent, indented. If the
    parent is closed, they fall back to top level.
  - Each row shows:
    - A state dot.
    - The name, ellipsized.
    - Time in state, e.g. `4m`, in `--muted`. It turns `--amber` for WAITING_INPUT.
    - A PR icon coloured by `prCheckStatus`, only when the session has a PR.
    - Cost, in `--muted`, 11px.
    - A `review` chip for review sessions.

    Branch and model appear in the row's `title` only, to keep rows to a single line.
  - The focused session's row gets the existing focus accent. Minimized sessions render
    at reduced opacity with the `MinimizeToDock` glyph.
  - Click a row: restore if minimized, then focus. In grid mode, also scroll the widget
    into view. In focus mode, it becomes the shown session.
- **System session.** Unchanged: the topbar toggle decides whether it's visible. When
  shown, it gets its own **System** group at the bottom of the sidebar.

### P3 — "Time in state"

The store gains `stateSince: string | null`, set from `e.ts` in the `state_changed`
reducer case. Replayed events carry their original `ts`, so the value survives a reload
correctly. The sidebar formats it relatively (`<1m`, `4m`, `2h`, `3d`) and re-renders on
one shared 30-second interval owned by the sidebar component. That's one timer in total,
not one per row.

### P4 — PR status moves into the store

`SessionWidget` keeps PR status in its local `entity` state today:

- `pr_status_changed` handling at `SessionWidget.tsx:108-111`.
- The chip at `:321-325`.
- The GitPanel create callback at `:443-445`.

The sidebar needs the same data, and two sources of truth would drift. So:

- `SessionView` gains `prUrl`, `prCheckStatus` and `parentSessionId`. They're seeded in
  the existing `seed()` call in `SessionWidget`'s detail effect. `prUrl` and
  `prCheckStatus` are updated by a new `pr_status_changed` reducer case.
- The widget's chip and GitPanel props read from the store. The GitPanel "PR created"
  callback calls `seed()` instead of `setEntity`.
- The widget's own `entity` state stays for everything else.

### P5 — Focus mode mechanics

- **Which session is shown.** The shown session is `focusedId`. If that's null or names a
  closed session, it falls back to the first session in sidebar order. `j`/`k`/`1`–`9`
  already move `focusedId`, so they switch the shown session for free.
- **CSS.**
  - `.grid-wrap.focus-mode` hides every `.grid-item-wrap` except the active one (a new
    `.active` class).
  - The active one reuses the `.maximized` overrides, but with
    `inset: <topbar-height> 8px 8px calc(var(--sidebar-w) + 8px)`, so it fills the main
    column instead of the viewport.
  - `--sidebar-w` is set on the shell from the fold state.
- **Protecting the grid layout.** `onLayoutChange` is ignored while in focus mode. Hiding
  all but one item, together with the width change, could otherwise make
  react-grid-layout compact the layout and overwrite the saved positions. Switching back
  to grid restores the layout exactly. Minimize has the same exposure today; the guard
  covers both.
- **Maximize and minimize in focus mode.** `maximizedId` doesn't apply (the active session
  already fills the main column), so `f` and the maximize button are inert. The button
  is hidden in focus mode. Minimizing the active session is allowed: it hides the
  session and focus moves to the next one in sidebar order.
- **No 2-way split** in v1 (see Out of scope).

### P6 — Hotkeys

The following keys are currently free:

| Key | Action |
|---|---|
| `b` | cycle sidebar fold state (expanded → rail → hidden) |
| `v` | toggle grid / focus layout |
| `a` | jump to the oldest Needs-you session: restore, focus, and scroll to its pending card |

`orderedIds` (`Dashboard.tsx`, passed to `useHotkeys`) returns grid order in grid mode
and sidebar order in focus mode, so `1`–`9` match what's on screen. In focus mode,
sidebar rows show a faint `1`–`9` hint. `permissionResponseFor` moves out of
`hotkeys/useHotkeys.ts` into a shared `permissions.ts`, used by both the hotkey and the
sidebar, with its unit test alongside.

### P7 — Notification click focuses the session

`notify.ts`'s `show()` only calls `window.focus()` on click. It gains an optional
`onClick`, and `SessionWidget` passes `() => restoreAndFocus(sessionId)`. That callback
comes from Dashboard through a new prop, the same function the sidebar row uses. Clicking
"needs input" in the OS notification then lands on that session.

### P8 — Icons

New role names in `icons.ts`, chosen to avoid collisions (`Expose` is already
`LayoutGrid`):

- `SidebarToggle` → `PanelLeft`
- ~~`GridMode` → `LayoutDashboard`~~ dropped at Step 2: it reads the same as `Expose` at 16px, so focus mode is a single toggle using `.active`
- `FocusMode` → `RectangleHorizontal` (not `Square`, which is already `Interrupt`)
- `NeedsYou` → `BellDot`

Final picks will be checked against the whole file at Step 2.

## Steps

### Step 1 — Store and shared helpers

- `store.ts`:
  - `SessionView` gains `stateSince`, `prUrl`, `prCheckStatus` and `parentSessionId`,
    all defaulting to null in `emptyView()`.
  - `state_changed` sets `stateSince = e.ts ?? new Date().toISOString()`.
  - A new `pr_status_changed` case.
- `SessionWidget.tsx`:
  - Seed the three entity fields.
  - The PR chip and GitPanel read from the store.
  - Drop the local `pr_status_changed` patch.
- New `permissions.ts`: `permissionResponseFor` plus `permissionSummary(toolName, input)`,
  the one-line summary used by the sidebar. `useHotkeys.ts` imports it from there.
- New `sidebarModel.ts`: a pure function `(ids, views, minimizedIds) →
  { needsYou[], groups[] }` covering the ordering, grouping and child-nesting rules from
  P2. It's the unit-testable core of the sidebar.
- **DoD:**
  - The vitest cases pass:
    - `stateSince` from `ts` on replay.
    - The `pr_status_changed` reducer.
    - `permissionResponseFor` (moved).
    - `permissionSummary` for Bash, Edit and unknown tools.
    - `sidebarModel`: oldest-first Needs-you, stable creation order, a child under its
      parent, an orphaned child at top level, and System last.
  - The widget's PR chip behaves exactly as before.

### Step 2 — Sidebar and layout shell

- New `components/Sidebar.tsx`:
  - Renders `sidebarModel` output in the three fold states.
  - Inline Allow/Deny/Open.
  - The 30-second relative-time tick.
- `Dashboard.tsx`:
  - The shell grid.
  - Fold state with persistence.
  - The `ResizeObserver` width (P1).
  - A `restoreAndFocus(id)` callback, shared by sidebar rows, notifications (P7) and `a`.
  - Remove `<DockStrip>`.
- Delete `DockStrip.tsx` and the `.dock-*` rules in `styles.css`.
- New CSS for the sidebar. Tokens only, so no new colour tokens are expected. Font sizes
  use the `calc(... * var(--font-scale))` scale, and the light theme gets checked.
- `notify.ts` gains `onClick` (P7).
- **DoD:**
  - All four sessions show in the sidebar with live state and time in state.
  - A plain permission on any session, minimized or not, can be allowed from the sidebar
    without touching the widget. The transcript card flips to its resolved state.
  - A plan card shows only **Open**.
  - The fold state survives a reload.
  - The grid reflows when the sidebar folds.
  - No dock strip remains.

### Step 3 — Focus mode

- `layoutMode` state with persistence, the topbar toggle, and the `.focus-mode` and
  `.active` CSS (P5).
- The `onLayoutChange` guard.
- `orderedIds` follows the mode.
- Maximize is hidden in focus mode.
- **DoD:**
  - Grid → focus → switch sessions through the sidebar and `j`/`k` → back to grid. The
    grid layout is identical to before, as checked by comparing the `agentic-ui.layout`
    value in localStorage.
  - No WS reconnect at any point (no "reconnecting…" flash).
  - A hidden session's permission still notifies and appears in Needs you.

### Step 4 — Hotkeys, cheatsheet and docs

- Add `b`, `v` and `a` to `useHotkeys.ts` and `HotkeyCheatsheet.tsx`. The `f` cheatsheet
  line says "(grid mode)".
- Docs:
  - `docs/ARCHITECTURE.md`: the dashboard box in the diagram, and the 7.2 paragraph,
    which mentions the dock strip.
  - `README.md`: the Sessions/UI feature bullets. Re-shoot `docs/images/dashboard.png`
    with the sidebar.
  - `docs/plan/README.md`: a decision-log row and a phase-table row.
- **DoD:** the whole-phase DoD below holds.

## Out of scope for this phase

- **2-way split in focus mode** (pinning a second session beside the active one). It's
  the obvious follow-up, and the CSS approach in P5 extends to it.
- **Mobile/PWA drawer** (ARCHITECTURE §5.6). The rail state is shaped so it can become an
  off-canvas drawer under 700px later. This phase doesn't add the breakpoint.
- **Sidebar drag-to-resize, custom grouping or sorting, and filtering.**
- **Closed or recent sessions in the sidebar.** That's what the continuation picker and
  the usage dashboard are for.
- **A dashboard-level status WS** (status for sessions without a mounted widget). Not
  needed, because every open session's widget is mounted in both modes.
- **Ctrl+K command palette, diff-line annotations, and best-of-N prompts.** These are
  separate candidates from the same Orca comparison.

## Definition of Done (whole phase)

- Every step's DoD above holds.
- Grid mode, with the sidebar hidden, looks and behaves like phase 15, apart from the
  dock strip being gone.
- Approving or denying from the sidebar and with `y`/`d` produce identical
  `permission_response` commands. Plans and AskUserQuestion can't be resolved from the
  sidebar.
- Switching layout mode, folding the sidebar or switching sessions never opens a new WS
  or loses the saved grid layout.
- Both themes and all three font sizes render the sidebar correctly.
- Frontend `npm test`, `tsc --noEmit` and `npm run build` pass. No backend, sidecar or
  migration file is touched.

## Manual test script

1. Start the backend and open four sessions: two Claude in `default` mode, one in `plan`
   mode, and one review session. The sidebar shows them grouped by service in creation
   order, each with a state dot and a time in state that advances (wait 1 minute).
2. Prompt session A into a Bash permission. It appears under **Needs you** with the
   command summary, and its time in state turns amber. Click **Allow** in the sidebar.
   The row disappears, A's transcript card shows "allowed", and A continues.
3. Minimize session B, then prompt it (before minimizing, or by waking it) into a
   permission. A desktop notification fires. Clicking the notification restores B and
   focuses it. Repeat with the tab focused on A, and deny from the sidebar this time.
4. Drive the plan-mode session to a plan. Its Needs-you row shows only **Open**. Click it:
   the session is focused and scrolled to the plan card.
5. Press `b` three times: expanded → rail → hidden. The grid widens at each step without
   a reload. Reload: the fold state is kept.
6. Note the `agentic-ui.layout` value in localStorage. Press `v`: one session fills the
   main column, and `j`/`k` and sidebar clicks switch it with no reconnect overlay.
   Prompt a hidden session into a permission: it shows up in Needs you, and `a` jumps to
   it. Press `v` again: the grid is back, and `agentic-ui.layout` is unchanged.
7. Have a session push and open a PR from the Git panel. The sidebar row gains the PR
   icon, which changes colour when the checks finish (PR-check polling). The widget's PR
   chip matches it.
8. Spawn a child session (orchestration). It nests under its parent. Close the parent:
   the child moves to the top level.
9. Toggle the system session in the topbar: a **System** group appears at the bottom of
   the sidebar and then disappears.
10. Switch to the light theme and the large font size, and repeat steps 1–2 visually.
