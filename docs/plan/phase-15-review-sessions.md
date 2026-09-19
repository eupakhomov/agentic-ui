# Phase 15 — Review sessions (session specialization)

Status: **confirmed (2026-09-19)** — decisions 1–4 confirmed with the user before this doc was
written; proposals 5–15 confirmed as written the same day. Verified against the code as of
phase 14's plan (commit `902bb7e`); every edit site named below was checked to exist as
described.

## Background

Every session today is development-shaped: a new branch is created off a base branch, the
agent develops on it, and the Git panel's commit → push → create-PR flow carries the result
out. Reviewing someone's work is the inverse motion — check out an **existing** branch,
read it against its base, apply review skills/agents, and leave the findings **on the PR**,
never touching the branch — and today it can only be approximated by hand (create a session
"on" the branch, which works only because `createWorktree` checks an existing branch out
as-is, then talk the agent through `gh`).

This phase makes the session's role an explicit, first-class property:

- **`sessionType ∈ {development, review}`**, default `development`, chosen at creation in
  both dialogs (and via templates). `development` is byte-identical to today.
- A **review session** targets an open PR (or, as a fallback, a plain branch): its worktree
  is a checkout of the *reviewed branch's tip*, its `baseBranch` is the PR's target, and its
  job is to produce a review using whatever skills/agents are attached — the existing
  `skillSources`/`agentSources`/template machinery, nothing new — and submit it as PR review
  comments.
- A review session **cannot commit, push, or create PRs**: the Git panel hides those
  controls, the backend refuses the endpoints, and a review system-prompt block tells the
  agent its role. The worktree itself stays writable (scratch notes, running builds/tests as
  part of a review), and the reviewed branch ref is never moved by us.
- Review comments reach GitHub through a **new human-gated MCP tool** on the existing
  in-process server (`submit_pr_review`, posting a review with summary + inline comments via
  `gh api` in one call). It is *not* pre-approved in `allowedTools`, so every submission
  passes the normal tool-permission prompt — editable before allow, like any other tool —
  and it is provider-neutral (Codex sessions get the same prompt-gated tool).

No sidecar/protocol change anywhere in this phase: the review block rides the existing
`extraSystemPrompt` seam, the tool rides the existing in-process MCP server, and the write
posture is unchanged (`--writable-root` as today).

## Confirmed decisions (2026-09-19)

1. **Target picking is PR-first.** With type = review, the create dialogs list the repo's
   open PRs (new `GET /api/meta/repo/prs`, via `gh pr list`); picking one derives
   `branch` (headRefName) + `baseBranch` (baseRefName) and attaches `prUrl` at creation, so
   comments always have a destination. A plain existing-branch pick stays possible as a
   fallback (the PR is then resolved lazily from the branch when the tool first needs it —
   `gh pr view <branch>`; no PR = the tool errors with a readable message).
2. **Comments go through a human-gated MCP tool**, not agent-driven raw `gh` and not a
   draft-panel UI. One structured `submit_pr_review` call posts the whole review (summary +
   inline file/line comments) atomically via `gh api`; the normal permission prompt is the
   human gate on both providers.
3. **The worktree stays writable; git-mutating operations are blocked.** Full sidecar-level
   read-only was declined (blocks scratch files and builds). The reviewed code's integrity
   is guaranteed by never moving the branch ref (proposal 5's detached checkout), not by
   denying writes.
4. **Enforcement is UI + REST + prompt** — matching the app's LAN/single-trusted-user
   posture. No auto-appended `disallowedTools` patterns (asymmetric: Codex rejects them), no
   sidecar command-pattern matching (fragile). The agent's own `git push` via Bash still
   hits the normal Bash permission prompt, where the user denies it; the review prompt block
   makes the agent not try in the first place.

## Proposals (confirmed as written 2026-09-19)

5. **Review worktrees are detached checkouts of the reviewed branch's tip.** `git worktree
   add` fails outright if the branch is checked out anywhere else — and the most likely
   reviewer scenario is exactly that: a live development session in this app owns the
   branch. A detached checkout sidesteps the conflict *and* makes "we never move the branch
   ref" structural rather than promised. Concretely, a new
   `GitWorktreeService.createReviewWorktree(repo, worktreePath, branch)`:
   `git fetch origin <branch>` (best-effort — a local-only branch skips it), then
   `git worktree add --detach <worktreePath> <tip>` where `<tip>` is `origin/<branch>` when
   the fetch succeeded, else the local `<branch>`. `session.branch` stores the reviewed
   branch name for display/PR resolution as usual. `syncBaseBranch` runs as today (the
   dialog's existing checkbox), so `diffVsBase`/`aheadOfBase` (`baseBranch..HEAD`) work
   unchanged on the detached HEAD.
6. **V18: `session.session_type TEXT NOT NULL DEFAULT 'development'`** — every existing row
   becomes a development session, no data migration. `SessionEntity`/builder/`toBuilder`/
   `SessionRepository` follow the `kind` column's trail; `SessionSummary` gains the field
   (the dashboard/Exposé chip shouldn't need the full entity).
7. **`sessionType` is identity, not tunable config**: like name/branch/repo it is *not*
   copied by `configOverridesFrom` — otherwise a quick session created after a review
   session would silently inherit `review` through `lastSessionConfig`. The dialogs always
   send it explicitly; `duplicate()` copies it explicitly (a duplicate of a review session
   is another review of the same branch/PR); templates may set it in their config
   (`sessionType` key, merged like any other), and the dialog's toggle reflects a picked
   template's value.
8. **The review system-prompt block** (a new arm in `SessionConfigFactory.extraSystemPrompt`,
   next to the memory/orchestration/code-intel blocks, both providers):
   > You are a code-review session. Your task is to review the branch `<branch>` against
   > `<baseBranch>`<, for PR <prUrl> ("<title>")| — no PR is attached yet>. Read and analyze;
   > run builds or tests if useful. Do NOT modify the reviewed code, do not commit, push,
   > or create pull requests — commit/push are disabled for this session. Use the review
   > skills and agents available to you. When your review is complete, submit it with the
   > `submit_pr_review` tool (summary + inline file/line comments); pass this as `sessionId`:
   > `<id>`.
9. **`submit_pr_review` tool shape** (new `session/ReviewMcpTools`, `@McpTool` component on
   the shared in-process server, same registration as `OrchestrationMcpTools`; gated on the
   calling session being `sessionType = review` — a development session calling it gets a
   tool error):
   - Params: `sessionId` (required); `event` ∈ `COMMENT`/`REQUEST_CHANGES`/`APPROVE`
     (default `COMMENT`); `body` (the summary, required); `comments` — optional array of
     `{path, line, side?, startLine?, body}` (modern `line`+`side` API params; `side`
     defaults to `RIGHT`).
   - Resolves the PR: `session.prUrl` if set, else `gh pr view <branch> --json url,...` in
     the worktree (attaching the result via `sessions.attachPr` so the CI poller picks it
     up too); no PR found → tool error telling the agent to report findings in the
     transcript instead.
   - Posts once via `gh api repos/{owner}/{repo}/pulls/{number}/reviews` (owner/repo/number
     parsed from the PR URL) with the whole payload — atomic: GitHub validates every inline
     comment's path/line against the diff and a 422 rejects the entire review; the error
     body is surfaced as the tool error so the agent can fix its line anchors and retry.
   - Journals a `pr_review_submitted` event (payload: event, comment count, PR URL) so the
     outcome is visible in the transcript and after the fact — the same "no silent 45 s
     action" rule as everything else.
   - **Not** added to `allowedTools` pre-approval (that block in `SessionConfigFactory`
     stays memory/discovery-only) — the permission prompt IS the human gate (decision 2).
10. **Blocked surface for review sessions**: `POST /git/commit`, `/git/push`, `/git/pr`
    return 409 (`IllegalStateException`, existing handler) naming the session type;
    `close(id, dirtyMode: "commit", …)` likewise refuses (stash/discard stay). The two
    suggest endpoints (`/commit-message/suggest`, `/pr/suggest`) aren't blocked — pointless
    but harmless — just unreachable from the UI. `resetPrCheckPending` on push can't
    trigger (push is blocked); the PR check poller itself keeps running on an attached
    `prUrl` — seeing the reviewed PR's CI state on the widget is a feature, not a leak.
11. **`GET /api/meta/repo/prs?repo=<gitRoot>`** → `gh pr list --json
    number,title,headRefName,baseRefName,url,author,isDraft` (30 s timeout), mapped to a
    small `PrInfo` record list. Since this is the third and fourth `gh` shell-out,
    `GitOpsService`'s inline `ProcessBuilder("gh", …)` blocks get extracted into one
    package-private `runGh(Path cwd, Duration timeout, String... args)` helper first
    (`createPullRequest`/`checkPrStatus` migrate onto it; pure refactor, same behavior) —
    `listOpenPrs` and `resolvePr(branch)` build on it. `gh` missing/unauthenticated
    surfaces as the same readable IllegalStateException the PR-create path already throws.
12. **Dialog UX**: a two-chip type row (`Development` | `Review`, capability-neutral, same
    chip-row pattern as permission modes) near the top of both dialogs, development
    preselected. Flipping to Review in either dialog:
    - swaps the new-branch text input for a **PR picker** (fetched from proposal 11's
      endpoint when the toggle flips; rows: `#N title (head → base)`, drafts marked) with
      an "or review a branch" fallback `<select>` of existing branches (the `branches()`
      endpoint — see proposal 13);
    - hides the ticket-import row (branch-name generation is development-shaped);
    - derives `name` = `review: <branch>` (editable, CreateSessionDialog only);
    - base-branch select shows the PR's base (read-only when a PR is picked);
    - QuickSessionDialog's prompt placeholder becomes "what to focus the review on —
      optional"; the typed prompt still lands as an unsent draft, never auto-fires.
    The create request carries `sessionType` at the top level (next to `servicePath` in
    `CreateSessionRequest`/`CreateOptions`), plus `prUrl`/`prTitle` when a PR was picked.
13. **`branches()` gains remote branches for the fallback picker**: the endpoint keeps
    returning local branches first, then `origin/*` refs (deduped against local, prefix
    stripped) — review targets usually exist only on the remote. Development flows ignore
    the additions (datalist suggestions at worst). Alternative, if this feels risky for the
    dev dialogs: a `?remote=true` param only the review picker sends.
14. **Widget/UI marking**: a `review` chip on the session widget header (neutral chip like
    the service chip — colour stays reserved for state), the same chip in Exposé cards and
    the continuation picker. `GitPanel` for a review session: status/diff/log stay (they
    ARE the review surface), the commit row + Push + Create-PR buttons are replaced by one
    muted line "review session — commit/push disabled" plus the PR link when attached.
15. **Interactions left unchanged, checked deliberately**: continuation/handoff (a review
    session can be continued like any other; the new session's type follows the dialog, not
    the source); orchestration (`spawn_child_session` spawns development children only —
    review children are out of scope, the tool doc says so); reflection/memory/auto-title/
    parking/context chip/compact — all type-agnostic; `MAX_SESSIONS` counts review sessions
    normally; Codex review sessions work (skills yes, `agentSources` still rejected by the
    existing capability check — a review template carrying agents fails creation on Codex
    exactly like a development one does today).

## Steps

### Step 1 — Backend model: type column, review worktree, blocked git ops

Edit sites: new `V18__session_type.sql` (proposal 6); `session/SessionEntity.java` (field +
builder + `toBuilder`), `session/SessionRepository.java` (column in insert/mapRow/summary
query), `web/SessionController.java` (`CreateSessionRequest.sessionType` + `prUrl`/`prTitle`,
`SessionSummary.sessionType`), `session/SessionService.java` (`CreateOptions.sessionType`/
`prUrl` + a `withSessionType`-style wither or extended canonical constructor; `create()`
branches worktree creation on type — `createWorktree` vs `createReviewWorktree` — and calls
`sessions.attachPr` when a PR was picked; `close()` refuses `dirtyMode: "commit"` for review;
`duplicate()` carries the type), `session/SessionConfigFactory.java` (`sessionType`
resolution — top-level option wins over a template's `sessionType` config key, validated to
the two values; the review prompt block per proposal 8; NOT added to `configOverridesFrom`,
proposal 7), `git/GitWorktreeService.java` (`createReviewWorktree`, proposal 5),
`web/GitSessionController.java` (409s per proposal 10 — extend the existing `worktree(id,
forWrite)` guard with a type check so all three write paths share it).

- Tests: `GitWorktreeServiceTest` — real `git init` fixtures: review worktree on a branch
  already checked out elsewhere succeeds detached at the right tip; local-only branch (no
  remote) reviewed at local tip; fetched branch reviewed at `origin/<branch>` even when the
  local ref lags. `SessionStateMachineTest`/`SessionConfigFactoryTest` — type persisted and
  defaulted; template `sessionType` honored and overridden; review prompt block present with
  and without PR; `configOverridesFrom` never emits it; development sessions byte-identical
  (existing tests unchanged). `GitSessionController`-level: commit/push/pr on a review
  session → 409 (via whatever pattern the existing IllegalState tests use). `close` with
  `dirtyMode=commit` on review → refused.

DoD: a review session on a branch held by a live dev session provisions successfully
(detached), `git -C <worktree> status` clean, `session.branch` shows the reviewed name;
commit/push/pr REST calls return 409 with a readable message; every development-session test
and code path is untouched (the type column defaulted everywhere).

### Step 2 — PR listing + `submit_pr_review` MCP tool

Edit sites: `git/GitOpsService.java` (the `runGh` extraction + `listOpenPrs(Path repo)` +
`resolvePr(Path worktree, String branch)`, proposal 11), `web/MetaController.java`
(`GET /repo/prs`), new `session/ReviewMcpTools.java` (proposal 9; constructor deps:
`SessionRepository`, `GitOpsService`, `EventJournal`+`JournalPublisher`, `ObjectMapper` —
mirror `OrchestrationMcpTools`), `docs/PROTOCOL.md` (`pr_review_submitted` journal event).

- The gh review post: `gh api repos/{owner}/{repo}/pulls/{n}/reviews -f event=... -f body=...`
  with the comments array passed as `--input -` JSON on stdin (the flag form can't express
  arrays of objects) — one more `runGh` variant taking stdin. URL parsing:
  `https://github.com/{owner}/{repo}/pull/{n}` with a readable error on anything else
  (GitHub Enterprise URLs are out of scope, same as the rest of the gh integration).
- Tests: `ReviewMcpToolsTest` (new file; fake `GitOpsService` seam or a package-private
  gh-runner injection, matching how `SerenaServiceTest` fakes its process runner) — happy
  path journals the event; development session → error; no PR + unresolvable branch →
  readable error; lazy resolution attaches `prUrl`; 422 body surfaced verbatim. A
  `GitOpsService` unit test for the URL parser (pure static).

DoD: from a live review session, "submit your review" produces one `submit_pr_review`
permission prompt showing the full payload; allow → the review (summary + inline comments)
appears on the GitHub PR in one submission, a `pr_review_submitted` line lands in the
transcript, and `session.prUrl` is set even when the session was created branch-only.

### Step 3 — Frontend

Edit sites: `protocol.ts` (`SessionEntity`/`SessionSummary.sessionType`, `PrInfo`, the
`SessionType` union), `api/rest.ts` (`listPrs(repo)`), `components/CreateSessionDialog.tsx` +
`components/QuickSessionDialog.tsx` (type chip row, PR picker + branch fallback, hidden
ticket import, derived name/base — proposal 12), `components/GitPanel.tsx` (proposal 14's
gating; it already receives the session, else fetch the summary), `components/
SessionWidget.tsx`/`ExposeOverlay.tsx`/`ContinuationPickerDialog.tsx` (the `review` chip),
`components/CloseDialog.tsx` (hide the commit option for review sessions),
`components/TemplateManager.tsx` (nothing — `sessionType` rides the generic config JSON,
like `reflectionEnabled` always has).

- Tests: store/protocol vitest for the new types; the dialogs stay untested like today
  (no component-test precedent) — the manual script covers them.

DoD: both dialogs create both types; flipping the toggle live swaps the branch input for the
PR picker and back with no stale state; a review session's Git panel shows no commit/push/PR
controls in both themes; the widget/Exposé/continuation chips read `review`.

### Step 4 — Docs + decision log

`CLAUDE.md` (a "Session types" bullet under per-session limits: what review blocks, the tool,
the detached checkout), `docs/ARCHITECTURE.md` (session-type paragraph + `ReviewMcpTools` in
the MCP-server section), `docs/PROTOCOL.md` (checked in Step 2), `docs/DEPLOY.md` (nothing —
`gh` is already a prerequisite), `docs/plan/README.md` (decision-log rows per landed step,
phase table row added with this plan).

## Out of scope for this phase

- **Review-session children / review fan-out** (`spawn_child_session` stays
  development-only).
- **Replying to existing PR review threads / re-review rounds** — the tool posts new
  reviews; reacting to the author's follow-up commits is "create another review session"
  (or a manual turn: the agent can read the PR through the Linear-style ambient gh access
  it already has via Bash).
- **A local review-findings panel in the dashboard** (the "draft in UI" option from the
  decision round) — the transcript + the GitHub PR are the record.
- **Hard enforcement** (disallowedTools auto-append, sidecar command filtering) — revisit
  if this app ever leaves the single-trusted-user posture.
- **GitHub Enterprise / non-github.com remotes** for the PR URL parser.
- **Other specializations** (e.g. a "research" type with a read-only worktree) — the
  `sessionType` column is TEXT precisely so a third value is a follow-up, not a migration.

## Definition of Done (whole phase)

- Every step's DoD above holds.
- A review session created PR-first checks out the PR head detached, shows the `review`
  chip, gets the review prompt block, reviews with attached skills/agents, and lands its
  summary + inline comments on the PR through one human-approved `submit_pr_review` call —
  on Claude and on Codex (skills-only there).
- The same works branch-first, with the PR resolved and attached at submit time.
- A review session cannot commit/push/create-PR through any UI control or REST endpoint
  (409s), its close dialog offers stash/discard only when dirty, and the reviewed branch
  ref is provably unmoved after the session closes.
- Development sessions are byte-identical to phase 14: same worktree commands, same
  mcpConfig, same prompts, same dialogs when the toggle is untouched.
- Unit tests green (`./mvnw -Dskip.installnodenpm -Dskip.npm -DexcludedGroups=integration
  test`), frontend `npm test` + `tsc --noEmit` + `npm run build` green; no sidecar package
  touched (`check-protocol-sync.mjs` trivially green).

## Manual test script

1. Create a development session on a test repo, have it commit a change on branch
   `feat/review-me`, push, and open a PR via the Git panel — leave the session open (its
   worktree holds the branch).
2. New Session → type **Review** → the PR picker lists the PR with `feat/review-me → main`;
   pick it → branch/base fill in read-only, name defaults to `review: feat/review-me`.
   Create → the session provisions (no "already checked out" error), widget shows the
   `review` chip, `git -C <worktree> rev-parse HEAD` equals the PR head,
   `git -C <worktree> symbolic-ref -q HEAD` exits non-zero (detached).
3. Git panel on the review session: status/diff/log render, no commit row, no Push, no
   Create PR; the PR link is shown. `curl -X POST .../git/push` → 409 problem+json.
4. Attach a review skill (library or template), prompt "review this PR" → the agent reads
   the diff, then calls `submit_pr_review`; the permission card shows summary + inline
   comments; **edit** one comment body in the card, allow → the edited review appears on
   GitHub as one review with inline comments on the right lines; `pr_review_submitted` in
   the transcript.
5. Repeat creation branch-first (fallback select, no PR picked) on a branch that has a PR →
   after submit, the session's PR chip/status appear (lazy attach). On a branch with no PR →
   the tool errors readably and the agent reports findings in the transcript instead.
6. Ask the agent to `git push` → the normal Bash permission prompt appears (deny it); ask it
   to write a scratch file → works (worktree writable).
7. Dirty the review worktree, close → the dialog offers stash/discard only; after close,
   `git -C <repo> rev-parse feat/review-me` is unchanged and the dev session from step 1
   still works.
8. Quick session with type Review → same PR picker flow, typed prompt lands as a draft.
   Quick session with type Development created *after* the review session → it is a
   development session (no type leak through `lastSessionConfig`).
9. Codex provider review session → same prompt block, same tool through the normal Codex
   approval flow; a review template with `agentSources` on Codex → 400 at creation (existing
   rule, unchanged).
10. Duplicate the review session → another review session on the same PR. Light + dark
    theme pass over the type chips, PR picker, and gated Git panel.
