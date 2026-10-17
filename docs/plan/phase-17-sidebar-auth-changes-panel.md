# Phase 17 — Two-state sidebar, optional auth, PR changes panel

Status: **landed (2026-10-17)**, after review the same day. Edit sites were
read at the current working tree, after Phase 16 (commit `701cac8`). The Slack integration
originally drafted here moved to [phase-18-slack.md](phase-18-slack.md) so this phase's DoD
doesn't wait on an external app setup.

## Background

Three independent enhancements, ordered by size. Each can land on its own.

| Item | Area | Size |
|---|---|---|
| A. Sidebar: two fold states | frontend | small |
| B. Auth token becomes a Settings toggle (applied on restart); loopback-only + Host/Origin guard when off | backend + frontend | medium |
| C. Right-side PR changes panel (CodeMirror 6, read-only) with "ask the agent about these lines" | backend + frontend | medium–large |

## Confirmed decisions (2026-10-17)

1. **Sidebar has two states**, `expanded` and `rail`. `hidden` is removed.
2. **Token auth is kept but conditional.** A persisted setting decides whether the bearer token
   is required; it **takes effect on the next backend start**, and the Settings dialog says so.
   With auth off, the server accepts requests from trusted client addresses only (loopback by
   default) and validates `Host`/`Origin`. The guard is **per request**, not a bind-address
   refusal at startup — see the review notes below for why.
3. **CodeMirror 6** renders the panel.
4. **The panel is read-only in v1.** Instead of editing files by hand, the user selects lines and
   asks the agent about them. In-place editing was dropped in review: it would need a write
   endpoint, hash-based conflict handling and a guard against saving while the agent is RUNNING,
   and it works against the app's model (the agent owns the worktree).

### Review notes (what changed from the first draft, and why)

- **B — bind-based guard replaced by a per-request client-address check.** The first draft refused
  to start with auth off unless bound to loopback. That is unusable on WSL, where `start.sh`
  binds `0.0.0.0` because the Windows→WSL relay only forwards wildcard binds (`CLAUDE.md`,
  "WSL gotcha"), and it contradicts `DEPLOY.md`'s own recommended remote path (Caddy or
  Tailscale in front of a loopback backend — the proxy *is* a loopback client). Checking
  `remoteAddr` against a trusted-CIDR list enforces the actual property ("only local clients")
  wherever the socket is bound.
- **B — Host check is a configurable, port-agnostic list.** DNS rebinding relies on the attacker's
  hostname, so the port adds nothing, and a port-specific check would break the Vite dev
  server (its proxy forwards `Host: localhost:5173`).
- **B — applied on restart.** Drops the runtime switch, the 409 refusals, the "you were just
  logged out" edge case and most of `/api/auth/status`.
- **C — read-only + select-lines.** See decision 4.
- **D — moved to Phase 18.**

---

## A. Sidebar: two states

The `rail` state is already the "folded with dots" view, so this phase only removes `hidden`.

- `Dashboard.tsx`: `FOLD_CYCLE` becomes `['expanded', 'rail']`.
- `Sidebar.tsx`: narrow the exported `SidebarFold` type to `'expanded' | 'rail'`.
- `loadFold()`: a stored `hidden` maps to `rail` (and is rewritten), so existing localStorage
  keeps working. Default stays `expanded`, or `rail` when the window is narrower than 1100px.
- `useHotkeys.ts`: update the `cycleSidebar` comment (`expanded ↔ rail`). `b` now toggles.
- Topbar toggle tooltip: `Sidebar: <fold> — click to toggle (b)`.
- Delete the `sidebar-hidden` CSS and any grid-column rule only it used.
- Rail mode: clicking the Needs-you badge does what `a` does (focus the oldest pending session),
  since there is no longer a way to hide the sidebar to reach the widget instead.
- Tests: update store/`sidebarModel` tests that mention `hidden`; add a `loadFold` migration test.
- Docs: edit Phase 16 P1 ("Three fold states") with a pointer to this phase, and the hotkey
  cheatsheet text.

## B. Optional token auth

### Behaviour

A persisted setting `auth.token-required` (Settings dialog → new "Access" section) decides
whether the bearer token is enforced. **It is read once at startup** (`AuthTokenService` is a
singleton built from `AppProperties` + the setting), so a change applies on the next start. The
dialog shows "applies on next restart" next to the toggle, and shows the *effective* state
(`required` / `off`) beside the stored one when they differ.

| `auth.token-required` (effective) | Client address | Result |
|---|---|---|
| on | any | Bearer token required on `/api/**` and WS, as today |
| off | in `auth.trusted-cidrs` | Allowed. `Host`/`Origin` validation applies to REST and WS |
| off | elsewhere | `403` problem+json: "auth disabled; only trusted client addresses accepted" |

- The token value still comes from `agentic-ui.auth-token` / `AGENTIC_UI_TOKEN`. The setting
  only controls enforcement. Settings is not a secret store (see `SettingsService`), so the token
  never moves into it.
- **Default:** on when a token is configured, off otherwise — existing installs behave the same.
  Turning it on with no token configured is accepted by the dialog but *effectively off*; the
  dialog explains that (`tokenConfigured: false`), the startup log warns once.
- With auth off, no bind-address restriction is needed any more: `StartupGuard`'s current
  "no token + non-loopback bind → refuse" rule is replaced by the per-request check. It still
  logs a one-line warning at startup when auth is off and the bind is non-loopback, naming the
  trusted CIDRs.

### Config (env / `application-local.yaml`, not Settings)

```yaml
agentic-ui:
  auth-token: ${AGENTIC_UI_TOKEN:${CLAUDE_UI_TOKEN:}}
  auth:
    trusted-cidrs: ["127.0.0.0/8", "::1/128"]   # clients accepted when the token is off
    allowed-hosts: ["localhost", "127.0.0.1", "[::1]"]   # Host header, port-agnostic
    allowed-origins: []                          # extra Origins; loopback on any port is implied
```

WSL users add the vEthernet range (or use mirrored networking, where Windows clients arrive
from loopback). A reverse proxy on loopback needs its external hostname in `allowed-hosts`.

### Backend

- `AuthTokenService.required()` = `tokenConfigured && settings.authTokenRequired()` evaluated at
  construction. Expose `effective()` for `/api/auth/status`.
- `SettingsService`: add a boolean field `authTokenRequired` (component on `Settings` and
  `SettingsPatch`, one row in `fields`; default from "token configured").
- `StartupGuard`: becomes a logger, not a refuser (see above). Keep the class; keep its test
  updated.
- New `LocalClientFilter` (`/api/**`, `/actuator/**` except health, and the WS handshake),
  **active only when auth is off**:
  - `remoteAddr ∈ trusted-cidrs`, else `403`.
  - `Host` (without port) ∈ `allowed-hosts`, else `403`. Blocks DNS rebinding.
  - If `Origin` is present: loopback on any port, or ∈ `allowed-origins`, else `403`. A missing
    `Origin` is allowed (curl, non-browser clients). `Sec-Fetch-Site: cross-site` is rejected when
    present — it's sent on every modern-browser request and is simpler than reasoning about
    when `Origin` is omitted.
- `WebSocketConfig.java:33` currently has `setAllowedOrigins("*")`. Replace it with the same
  allowed-origins resolution so the handshake is covered by one rule, not two.
- `GET /api/auth/status` (always open) returns `{required: boolean, tokenConfigured: boolean}`.
  Nothing else.

### Frontend

- Settings dialog, "Access" section: "Require access token" toggle with the hint
  *"Applies on next restart. With the token off, only local clients are accepted."* and, when the
  stored value differs from `required` from `/api/auth/status`, a note *"currently: on/off —
  restart to apply"*. When `tokenConfigured` is false, the hint says the token is unset
  (`AGENTIC_UI_TOKEN`) so the toggle has no effect.
- The token gate is shown only on a 401, as today. With auth off there are no 401s, so no prompt.

### Tests

- `AuthTokenFilter`: required/not-required × valid/invalid/missing token.
- `LocalClientFilter`: trusted/untrusted `remoteAddr`; matching and non-matching `Host` with and
  without port; `Origin` loopback on another port, listed, unlisted, absent; `Sec-Fetch-Site`
  cross-site; WS handshake path.
- `AuthTokenService`: the four combinations of token configured × setting.
- Frontend: Settings toggle states, "restart to apply" note, and the gate not appearing when auth
  is off.

### Docs

Update `CLAUDE.md` (the `AGENTIC_UI_TOKEN` row, the "WSL gotcha" paragraph that says the guard
requires a token, the persisted-settings list), `DEPLOY.md` (LAN access, "Local-only, no token"),
`ARCHITECTURE.md` (TokenGate, auth paragraphs), `start.sh` (it can stop generating a token when
the setting is off — read `/api/auth/status`? No: the backend isn't up yet. It keeps generating
one; harmless), and add a decision-log line to `docs/plan/README.md`. Document remote access
with auth off: SSH tunnel, Tailscale, or an authenticating reverse proxy on loopback with its
hostname in `allowed-hosts`.

## C. PR changes panel (read-only) with "ask the agent"

### What it does

A right-side panel per session that lists what the branch would carry into a PR, shows each file
as a syntax-highlighted diff, and lets the user select lines and send a prompt to the agent
that is prefilled with the file, line range and the selected text. No editing.

### Library

CodeMirror 6, through a thin wrapper of our own (~60 lines; `@uiw/react-codemirror` would be a
dependency whose React 19 fit needs checking, for little gain):

- `@codemirror/lang-java`, `@codemirror/lang-javascript` (TS and TSX), `lang-json`,
  `lang-markdown`, `lang-yaml`, `lang-xml`, `lang-sql`, `lang-html`, `lang-css`.
- Kotlin: `@codemirror/legacy-modes/mode/clike` (`kotlin`) via `StreamLanguage`.
  Highlighting only; there is no language service.
- `@codemirror/merge` for the diff: **unified by default**, side-by-side only when the pane is
  wider than ~900px; `collapseUnchanged` on, so a 400-line file with a 3-line change needs no
  scrolling.
- Read-only state (`EditorState.readOnly`), selection enabled.
- Theme follows the app's light and dark themes via the `--*` tokens.
- The whole panel is `React.lazy`, and languages are dynamic imports by extension, so a user who
  never opens it pays nothing in the main bundle.

### Backend (new REST under `/api/sessions/{id}/changes`, behind the auth and local-client filters)

- `GET  /` → one list: **working tree vs merge-base of `baseBranch`**, built on
  `GitOpsService` (`diffVsBase` and `status` already exist; add a name-status variant). Each row:
  `path`, `status` (`added`/`modified`/`deleted`/`renamed`), `where` (`committed`/`staged`/
  `unstaged`/`untracked`), `additions`, `deletions`. One list, not two, because the user wants
  "what's in this PR"; the `where` badge carries the git detail.
- `GET  /file?path=` → `{content, baseContent, binary, size, truncated}`. `content` is the working
  tree file, `baseContent` is `git show <merge-base>:path` (null for added files). UTF-8 only:
  invalid UTF-8 → `binary: true`, no content. Size cap 1 MB (`truncated: true` beyond it, with
  the first 1 MB).
- Path safety, read side: resolve against the real worktree root (`toRealPath()` of the parent
  directory), reject `..`, absolute paths, any `.git` segment at any depth, and any path whose
  real location is outside the worktree.
- **Only changed files are readable (decided 2026-10-17).** `GET /file` serves a path only if it
  is in the current changes list (recomputed server-side on each call, not trusted from the
  client); anything else is `404`. This keeps a direct `?path=` from becoming a way to dump
  `.claude/settings*.json`, `.serena/` or `.codegraph/` (those are in `info/exclude`, so they
  never appear in the list), and it costs nothing since the panel only ever opens listed files.
  Browsing unchanged files is out of scope; if it's wanted later, the path check above is the
  floor, not this rule.
- Review sessions: `baseBranch` is the PR base, so the same code works. Nothing to block, the
  panel is read-only everywhere.
- No write endpoint.

### Frontend

- Panel: file list on top (status + where badges, `+n −m`), the diff below, a header with the
  file path and a Unified/Split toggle (auto by width, overridable).
- **Ask the agent.** Select lines in the diff (either side) → a floating "Ask about these
  lines" button (and `Enter`) opens the prompt box prefilled with

  ```
  In `src/Foo.java` lines 42–57:
  ```java
  <selected text>
  ```
  ```

  and places the caret after it, so the user types the question. Sending goes through the
  normal prompt path (queued if a turn is running). This is the "diff-line annotations"
  follow-up from Phase 16, done the simple way.
- **Keyboard.** CodeMirror is a `contenteditable`; verify `useHotkeys` ignores it so `n`, `q`,
  `y`, `d` don't fire while the panel has focus. `Esc` leaves the panel and returns focus to the
  widget. Panel hotkey: `c` (free in `useHotkeys.ts`; "changes"). `j`/`k` inside the file list
  move between files, as they do between sessions elsewhere.
- Entry points: a "Changes" button in the widget header (badge with the file count), the PR
  dialog, and `c`.
- Layout: **focus mode first**. The panel takes the right part of the focus pane and is
  resizable (persisted width). In grid mode it opens as an overlay for the focused session.
- Freshness: refresh the list on `turn_completed` and on open; an open file shows a
  "changed on disk — reload" banner rather than silently replacing the view (the user may be
  mid-selection).

### Out of scope here

In-place editing (see decision 4), LSP, go-to-definition, search, file create/delete, and
committing from the panel (the Git panel does that).

---

## Steps

### Step 1 — Sidebar (A)
Edits listed in section A. Frontend tests, `tsc` and build.

### Step 2 — Auth setting (B)
Settings field, `AuthTokenService`, `StartupGuard` → warning only, `LocalClientFilter`,
`WebSocketConfig` origins, `/api/auth/status`, Settings dialog, tests, docs.

### Step 3 — Changes panel backend (C)
`GitOpsService` name-status + merge-base content, the two endpoints, path safety, changed-files-only
rule. Tests with a temporary git repo: committed/staged/unstaged/untracked rows, renames, added
file has null base, binary and oversize handling, traversal and symlink rejection, `.git` at
depth, an unchanged tracked file → 404.

### Step 4 — Changes panel frontend (C)
CodeMirror wrapper with lazy languages, file list, unified/split diff, select-lines → prompt,
hotkeys, focus-mode layout and grid overlay.

## Out of scope for this phase

- A third sidebar state, or drag-to-resize of the sidebar.
- Multi-user accounts, OAuth, or per-user permissions.
- Editing files from the panel, LSP or IDE features, file create/delete.
- Slack — Phase 18.

## Definition of Done (whole phase)

- [x] The sidebar has only `expanded` and `rail`; a stored `hidden` migrates to `rail`.
- [x] `auth.token-required` is switchable in Settings, applied on restart, and the dialog says so
      and shows the effective state.
- [x] With auth off, requests from an untrusted client address, or with a foreign `Host` or
      `Origin`, get 403 on REST and WS. Loopback clients work with no gate.
- [x] Startup no longer refuses a non-loopback bind without a token; it warns.
- [x] The changes panel lists working-tree-vs-merge-base changes with where-badges, shows a
      highlighted diff (Java, Kotlin, TypeScript checked), and "ask about these lines" sends a
      prefilled prompt.
- [x] Path traversal, symlink escapes and `.git` paths are rejected by the file endpoint, and a
      path not in the changes list is a 404 (tested).
- [x] Backend and frontend tests and the build pass; docs and the decision log are updated.

## Manual test script

1. Sidebar: toggle with `b` and the button; reload; set `agentic-ui.sidebar=hidden` in
   localStorage and reload → rail. In rail mode, click the Needs-you badge → oldest pending session.
2. Auth on: no token → the gate appears; the right token → works. Toggle off in Settings → the
   dialog says "restart to apply"; still gated until restart.
3. Restart with auth off, `--server.address=0.0.0.0` (WSL): dashboard loads from the Windows
   browser with no gate only if the vEthernet range is in `trusted-cidrs`; otherwise 403 with the
   explanatory detail. `curl -H 'Host: evil.example' localhost:8080/api/sessions` → 403.
   `curl -H 'Origin: https://evil.example' …` → 403. Vite dev server on :5173 works.
4. Changes panel: in a session with committed + uncommitted changes, open with `c`; rows show the
   right where-badges; a Kotlin and a TypeScript file highlight; unified diff collapses
   unchanged regions; widen the pane → split view. Select lines → Ask → prompt is prefilled with
   path, range and text; send → the agent answers about those lines. `?path=../../etc/passwd`
   and a symlink out of the worktree → 400.
5. Both themes, and the large font size.

## Implementation log

### Steps

- **Step 1 — Sidebar (A): done (2026-10-17).** `SidebarFold` is `expanded | rail`; `normalizeFold`
  (in `sidebarModel.ts`, unit-tested) migrates a stored `hidden` to `rail` and `loadFold` rewrites it.
  No `sidebar-hidden` CSS existed (the grid width follows the `--sidebar-w` variable), so nothing
  to delete there. The rail's Needs-you badge already opened the oldest pending session. Hotkey
  cheatsheet, `useHotkeys` comment, topbar tooltip and the Phase 16 P1 text are updated.
- **Step 2 — Auth setting (B): done (2026-10-17).** `Settings.authTokenRequired` /
  `SettingsPatch.authTokenRequired` (`auth.token-required`), `AuthTokenService` (decided at
  construction), `AuthProperties` (`agentic-ui.auth.*`), `LocalClientFilter`, `StartupGuard` now
  only logs, `AuthController` (`GET /api/auth/status`), Settings dialog "Access" section, docs
  (`CLAUDE.md`, `DEPLOY.md`, `ARCHITECTURE.md`, `application.yaml`).

- **Step 3 — Changes panel backend (C): done (2026-10-17).** `ChangesService` (+ `ChangesController`
  at `/api/sessions/{id}/changes` and `/changes/file?path=`), `GitCommandRunner.runBytes` for raw,
  capped blob reads (the normal runner `strip()`s output, which would eat a file's trailing newline).
- **Step 4 — Changes panel frontend (C): done (2026-10-17).** `ChangesPanel` (lazy), `changes/DiffView`
  (CodeMirror 6 unified/split, read-only), `changes/language` (lazy per-extension languages, Kotlin via
  the legacy clike mode), `changes/theme` (colours from CSS tokens, new `--syn-*`/`--diff-*`/`--sel-bg`
  in all three theme blocks), select-lines → composer, `c` hotkey, header button with file-count badge,
  GitPanel "Review changes" button.

### Deviations from the plan

- **`where` is a list**, not a single value: a file committed and then edited again is both
  `committed` and `unstaged`, so the row carries every state it is in.
- **`ChangedFile.mtime`** was added (not in the plan) so the "changed on disk — Reload" banner can
  tell that a file changed even when its line counts did not.
- **Panel placement:** inside the session widget, beside its body when the widget is ≥ 900 px wide
  (always in focus mode on a normal window), and as an overlay over the widget otherwise. "Ask about
  these lines" closes an overlay so the composer is visible. Not a separate focus-mode-only pane.
- **Refresh:** the widget re-lists when a turn finishes (not while RUNNING) and every 8 s while the
  panel is open; the header badge uses the same list.
- **Unified view can only ask about the new side** (deleted chunks are widgets, not selectable
  document lines); switching to the two-sided view allows asking about old-version lines.

- **`WebSocketConfig` keeps `setAllowedOrigins("*")`** (with a comment) instead of reusing the
  allowed-origins list. With the token on, any origin must be able to connect (a LAN hostname is
  not known in advance); with it off, `LocalClientFilter` sees the handshake as a plain HTTP
  request before Spring does and enforces Host/Origin/client address, so there is still one rule.
- **An `Origin` is accepted when its host is in `allowed-hosts`**, in addition to the explicit
  `allowed-origins` list (the plan said loopback-on-any-port plus the list). For the defaults the
  two are identical; it means a reverse-proxy setup needs its hostname configured once, not twice.
- **`Settings` keeps its old 32-argument constructor** (delegating with `authTokenRequired=false`)
  so the ~10 fixed-settings test fixtures didn't need touching.
- **No frontend test for the Settings "Access" section**: the repo has no component tests (vitest
  covers pure logic only). The section is covered by `tsc` and the manual script.
- `start.sh` is unchanged: it always passes a token, so the default (token required) is what it
  gets, as before.

### Verification

- Backend: 380 unit tests pass (`mvn -DexcludedGroups=integration test`), including new
  `LocalClientFilterTest`, `AuthTokenServiceTest` and `SettingsServiceTest` cases.
- Frontend: `tsc --noEmit` clean, 66 vitest tests pass.
- Changes panel: 16 `ChangesServiceTest` cases against a real temp git repo (badges, renames,
  deletions, binary/oversize, traversal, symlinks, changed-files-only 404); 12 vitest cases for the
  selection/prompt helpers. Total after the phase: 396 backend tests, 78 frontend tests, `tsc` clean,
  `vite build` OK (the panel and every language are separate lazy chunks).
- The panel was rendered in a throwaway harness page (mocked file API) in a real browser: highlighted
  Java/Kotlin/TSX diff, unified and split layouts, light and dark themes, and Enter/button on a
  selection producing the expected prompt text (`In \`src/Foo.java\` line 5:` + fenced code).
- **Live run (2026-10-17, real Postgres, built jar, `--server.address=0.0.0.0`):** see "Manual test
  script results" below. It found one bug the unit tests could not: `AuthTokenService` had two
  constructors (a package-private test seam and the public one) and Spring refused to start
  ("No default constructor found"). Fixed with `@Autowired` on the public one.
- Full suite including the DB-backed integration tests: 431 tests pass.

### Manual test script results

| # | Result | Notes |
|---|---|---|
| 1 | Passed (live) | Stored `hidden` → `rail` and rewritten to `rail`; `b` toggled `expanded` ↔ `rail`; tooltip reads "click to toggle (b)". The rail badge → oldest pending is the existing Phase 16 behaviour and was not re-exercised (no pending permission). |
| 2 | Passed (live) | Token on: no/wrong token 401, right token 200, `/api/auth/status` open (`{"required":true,"tokenConfigured":true}`). Switching the setting off via `PATCH /api/settings` changed the stored value only: status still `required:true` and the API still gated until restart. |
| 3 | Passed (live), with a caveat | After restart, token off, bound to `0.0.0.0`: loopback client with `Host: localhost[:5173]` and `Origin: http://localhost:5173` 200 with no gate in the browser; `Host: evil.example`, `Origin: https://evil.example`, `Origin: null`, `Sec-Fetch-Site: cross-site` all 403; the same request from the WSL's own non-loopback address (172.29.79.191) 403 even with `Host: localhost`; health, `/api/auth/status` and the static page stay open; the WS handshake with a foreign Origin 403. Refusal body names the failed check. **Not tested: a Windows browser through the WSL relay** — which source address the relay presents is unknown; if it is not loopback, the 403 log line names it and it belongs in `trusted-cidrs`. |
| 4 | Passed (live) | In a real session (base `main`): rows for committed, staged, unstaged, untracked, rename (with old path) and delete with the right badges and counts; Kotlin highlighted; unified diff; binary file → "no preview". `?path=` checks: `../../etc/passwd` and absolute path 400, `.git/config` 400, a symlink 400, an unchanged tracked file and the excluded `.claude/settings.local.json` 404. Selecting a line and pressing Enter put ``In `tmp/Hello.kt` line 2:`` + fenced `kotlin` block in the composer with the caret at its end. Editing a file in the worktree: the list refreshed within the 8 s poll (+3 → +4) and the "changed on disk — Reload" banner appeared. |
| 5 | Partly | Light theme live, dark theme and split layout in the mocked harness only. Large font size and the narrow-widget overlay layout not checked live. |

Test sessions, worktrees and branches created for this were removed; the `auth.token-required` setting
the test flipped was set back to `true`.

### Follow-ups

- **A Windows-browser run against the WSL relay** (see step 3) — confirm which client address it shows
  and document the value for `trusted-cidrs`.
- Dedicated Spring-context test for the filter/auth wiring (the `@Autowired` bug was found by running
  the app; `ApplicationTests` boots the context but only passes with the default config).
- Optional: let the panel's file list show an unchanged-but-related file. Deliberately not allowed
  today (changed files only).
