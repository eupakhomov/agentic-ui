# Phase 13 — Graphify knowledge-graph MCP (code intelligence, one-of with Serena)

Status: **landed (2026-09-15, Steps 1–4)** — decisions 1–4 confirmed with the user before this
doc was written; 5, 8, 9 and 12 confirmed on review of the doc the same day; 6, 7, 10, 11
and 13 confirmed as written before Step 1. What changed on contact with the code is in the
decision log (docs/plan/README.md, 2026-09-15 "Phase 13 landed"). Verified against the
code as of the phase-11 follow-up (commit `33cab25`) and against the graphify checkout at
`/mnt/d/projects/graphify` (v0.9.62, commit `c7ec108`); every edit site named below was
checked to exist as described, and every graphify behaviour relied on was run live (Step 0).

## Background

### What graphify is

[graphify](https://github.com/Graphify-Labs/graphify) (PyPI package `graphifyy`, Apache-2.0)
turns a folder into a persistent knowledge graph: tree-sitter AST extraction of ~40
languages (deterministic, no LLM) into `graph.json`, Leiden community detection, "god
nodes", and query tools — `query_graph` (natural-language question → scoped subgraph),
`get_node`/`get_neighbors`, `shortest_path`, `get_community`, `god_nodes`, `graph_stats` —
exposed as a **stdio MCP server** (`graphify-mcp <graph.json>`, extra `mcp`). An optional
"semantic pass" sends docs/PDFs/images to an LLM backend; code never needs one.

Compared with Serena (phase 12 track B): Serena is a *live* language-server view — precise
symbol lookup, references, and symbol-level edits in the current file state. Graphify is a
*pre-built* structural map — cross-file relationships, communities, blast radius, "how does A
connect to B" — cheap to query, a few seconds behind the working tree. They overlap in
purpose ("stop grepping, ask the tool"), each spawns a process per session, and each wants
a "use me first" system-prompt nudge that would compete with the other's. Hence one-of.

### Security review (done 2026-09-15, before this plan)

Read: `SECURITY.md`, `security.py`, `serve.py` (MCP server), `hooks.py`, `install.py`, the
`llm.py`/`prs.py`/`google_workspace.py`/`detect.py` subprocess sites, `querylog.py`, the
Claude skill body (`skill.md`), `pyproject.toml`/`uv.lock`, the changelog's security
entries. Grepped the package for `shell=True`, `eval`/`exec`/`pickle`, network primitives,
telemetry, and literal URLs.

Clean for our purposes:
- **No telemetry; no network during a graph build.** Egress exists only for `add <url>`/
  `ingest` (explicit) and the semantic pass (opt-in backend). Query logging is opt-in in
  code (`querylog.py`, `GRAPHIFY_QUERY_LOG_ENABLE`) — the README "Privacy" section still
  describes it as opt-out; the code is the stricter of the two.
- **No `shell=True`, `eval`, `exec`, `pickle`.** Every subprocess is an argv list (`git`,
  `gh`, `gws`, `claude -p`, `cpp`).
- **`security.py`**: http/https only; private/loopback/link-local/CGN/cloud-metadata ranges
  blocked; resolve-once-then-connect (no DNS-rebind TOCTOU); redirects re-validated;
  50 MB/10 MB fetch caps; 512 MiB `graph.json` cap; control-char/HTML sanitisation of labels
  and metadata. `detect.py` skips credential-shaped files (`.env*`, keys, `.netrc`,
  `.aws/`…) and reports them.
- **MCP server**: read-only graph tools plus three PR tools (`list_prs`/`get_pr_impact` →
  `gh`; `triage_prs` → an LLM by ambient key). `project_path` can only ever load
  `<path>/graphify-out/graph.json` (JSON, size-capped) — fine for a local stdio server run
  as the same user. Stdio by default; HTTP is opt-in and binds `127.0.0.1`.
- Custom providers store an **env-var name**, never a key; a repo-local `.graphify/
  providers.json` is not auto-loaded (needs `GRAPHIFY_ALLOW_LOCAL_PROVIDERS=1`).
- Maintained: `uv.lock` pins 205 packages, bandit + pip-audit in the dev group,
  `tests/test_security.py` + injection-sentinel tests, ~20 explicit security fixes in the
  changelog (SSRF rewrite, stored XSS in `graph.html`, zip-bombs, XXE, hook hardening).

Posture we adopt because of what we found (these are not bugs, they are the wrong shape
for a backend-driven integration):
1. **Never the `/graphify` skill.** Its Step 1 runs `uv tool install --upgrade graphifyy`
   (or `pip install --break-system-packages`) from PyPI *inside the agent session*, and
   trusts a repo-local `graphify-out/.graphify_python` file as the interpreter to execute.
   We drive the CLI/MCP server from a checkout we chose and reviewed.
2. **Never `graphify install` / `graphify hook install`.** They rewrite
   `~/.claude/settings.json` (PreToolUse hooks), `~/.claude/CLAUDE.md`, and git hooks that
   exec a detached Python on every commit. Our own MCP layering + `extraSystemPrompt` seam
   already do what those achieve.
3. **Code-only, always.** Without `--code-only`, backend auto-detection picks *whichever
   API key is in the environment* (Gemini → Kimi → Claude → OpenAI → …) and ships doc
   contents there; sidecar/build processes inherit the backend's env.
4. `graph.html` loads `vis-network` from unpkg — never embed it in the dashboard; disable
   its generation (`GRAPHIFY_VIZ_NODE_LIMIT=0`).
5. The PR tools are reachable through MCP; they go through the normal per-session approval
   flow like any tool, and `gh` is already used by this app. Acceptable; not advertised in
   our prompt block.

### Step 0 spike — confirmed live (2026-09-15, WSL, checkout `/mnt/d/projects/graphify`)

Run with `UV_PROJECT_ENVIRONMENT` and `GRAPHIFY_OUT` pointed at the scratchpad, so neither
the checkout nor this worktree was touched (`git status` stayed clean throughout):

- `uv run --directory <root> --no-dev --extra mcp --extra sql graphify --version` → syncs the
  env (112 packages, ~4 s with a warm wheel cache; cold cache will download ~110 wheels) and
  prints `graphify 0.9.62`. `--no-dev` matters: `uv run` otherwise installs the dev group
  (nuitka, pyright, …). Package name in `pyproject.toml` is **`graphifyy`** (double-y).
- **`graphify update <path>` works from an empty output dir** — one command for the initial
  build and every refresh: AST extraction + clustering + `GRAPH_REPORT.md`, no LLM. With
  `GRAPHIFY_VIZ_NODE_LIMIT=0` no `graph.html` is written (and an existing one is removed).
  This repo: 227 code files → **2572 nodes / 7685 edges / 138 communities** (the `sql` extra
  adds the 16 Flyway migrations; without it they contribute nothing), **~80 s wall on
  `/mnt/d` at ~5 s CPU** — DrvFS latency on the tree walk; expect seconds on the macOS
  target. Output ≈ 11 MB (`graph.json` 4.6 MB, `manifest.json`, `cache/`, `GRAPH_REPORT.md`,
  `.rebuild.lock` while running). `.gitignore` is honoured (`target/`, `node_modules/`,
  `frontend/dist` skipped), so no `.graphifyignore` is needed.
- `graphify query "…" --graph <graph.json>` answers in **0.5 s**.
- **MCP server** over stdio (`graphify-mcp <graph.json>` via `uv run …`): initialize in
  **0.8 s**, 10 tools listed, `get_node`/`shortest_path` work. Every `call_tool` re-stats
  `graph.json` and reloads on mtime change (`_GraphContextCache.load`), so a background
  rebuild is picked up without restarting the server; if the graph does not exist *yet*
  the server still starts (pure multi-project mode) and a call returns a tool error
  ("graph.json not found") until it appears — so the async build + MCP-at-spawn ordering
  is safe.
- The rebuild lock is `<GRAPHIFY_OUT>/.rebuild.lock` — per output dir, so parallel
  sessions' builds never contend.
- `GRAPHIFY_OUT` accepts an absolute path and every reader honours it (`paths.py`); the
  MCP server's `project_path` resolution joins against it too, harmlessly.

## Target behavior

- Settings → "MCP servers" gains a **Code intelligence** selector — `none` / `Serena` /
  `Graphify` — next to the existing Serena root + uv path, plus a **Graphify root** (path to
  a graphify checkout; empty = unavailable). Save validates the root (directory,
  `pyproject.toml` names `graphifyy`, `uv run … graphify --version` exits 0 — which also
  performs the first env sync) and refuses selecting a tool whose root is blank, or
  blanking the root of the selected tool; failures are 400s with the reason.
- Create dialog + template: the phase-12 "Serena" checkbox becomes a **"Code intelligence"
  checkbox** whose label names the globally selected tool ("Serena — symbolic code tools…"
  / "Graphify — knowledge-graph tools…"); hidden when the selector is `none`. Default off.
  Session detail/summary carry which tool was attached (`codeIntel: null | "serena" |
  "graphify"`); the widget chip reads `serena` or `graphify`.
- A session created with the flag while the selector is `graphify`:
  - gets a `graphify` stdio MCP entry layered into its `mcpConfig` (unless it already
    declares one — same rule as Linear/memory/Serena), pointing at
    `<worktree-root>/.graphify/<sessionId>/graph.json`, and a short provider-neutral
    system-prompt block (ours) telling the agent the graph exists, what the tools are, and
    to query it before grepping; both providers get the block (Codex too — unlike Serena's
    Claude-specific override);
  - has its graph **built in the background right after the worktree exists** (the session
    is usable immediately; the widget chip pulses `graphify` while building, turns steady
    when ready, `--red` on failure with the error in the transcript);
  - has the graph **refreshed after every completed turn** (coalesced: a turn finishing
    during a build queues exactly one more run), so the agent's own edits show up a few
    seconds later on macOS;
  - loses the graph dir when closed (and orphan cleanup sweeps leftovers).
- Nothing is written into the worktree: the Git panel and the session's PR stay clean.
- A Serena session behaves exactly as after phase 12; a session with the flag off has an
  `mcpConfig` and system prompt byte-identical to phase 12's.

## Decisions (1–4, 5, 8, 9, 12 and 14 confirmed 2026-09-15; 6, 7, 10, 11, 13 to confirm before the step that needs them)

1. **Global one-of.** A single persisted selector `mcp.code-intel ∈ {none, serena,
   graphify}` picks the tool for the whole install; sessions get one on/off flag for
   whichever is selected. (Per-session one-of and "both allowed" were offered and
   declined: the conflict is two processes and two competing "use me first" prompts per
   session, and the user prefers one tool per install.)
2. **Per-session graph, built asynchronously, refreshed after turns, stored outside the
   worktree.** A shared per-service graph (never reflects a session's edits) and a
   build-once graph (stale by the second turn) were declined.
3. **Local checkout driven through `uv`, mirroring Serena** — `mcp.graphify-root` +
   the existing `mcp.uv-path`; the reviewed code is the code that runs, pinned by the
   checkout. A PyPI `uv tool install` was declined (future upgrades would be unreviewed).
4. **Code-only extraction** (`graphify update` is AST-only by construction; no semantic
   pass, no API key, nothing leaves the machine). Docs (`.md`) are therefore not in the
   graph; "code + docs via `--backend claude-cli`" is the named follow-up.
5. **The session flag generalizes** (confirmed): `session.serena_enabled` → `session.
   code_intel TEXT NULL` (`'serena'`/`'graphify'`, V17 backfills from the boolean and drops
   it), entity field `codeIntel`, session/template config key **`codeIntelEnabled`**
   resolved to the selected tool at `prepare()` time. `prepare()` keeps accepting the
   legacy `serenaEnabled` key as an alias (templates already in the DB carry it). The
   entity records *which* tool was attached because the MCP entry is baked at creation:
   flipping the selector later must not change what a live session's chip says.
6. **Graph dir = `<worktree-root>/.graphify/<sessionId>`** (proposal): next to the
   worktrees, dot-prefixed so `MaintenanceController.orphans()` (which skips dot-dirs)
   ignores it, deleted on close, swept by `orphans/clean` for ids that are not active.
   Inside the worktree + `.git/info/exclude` (the Serena precedent) was rejected: 11 MB of
   cache + JSON the agent could read/edit, and the semantic-pass files would land there
   too if ever enabled.
7. **One command for build and refresh** (proposal): `<uv> run --directory <root> --no-dev
   --extra mcp --extra sql graphify update <cwdPath>` with env `GRAPHIFY_OUT=<graph dir>`,
   `GRAPHIFY_VIZ_NODE_LIMIT=0` (no HTML), `GRAPHIFY_NO_TIPS=1`, `GRAPHIFY_QUERY_LOG_DISABLE=1`
   (belt and braces; it is off by default). `extract --code-only` was the alternative for
   the initial build; `update` from empty was confirmed equivalent and also clusters.
8. **Refresh policy: after every `turn_complete`, single-flight + coalesced, no change
   detection** (confirmed): graphify's own manifest cache makes unchanged files free; the
   fixed cost is the tree walk (seconds on macOS, ~80 s on the WSL dev box — background
   I/O wait, not CPU). If it proves noisy, the named optimization is a fingerprint of
   `HEAD` + `git status --porcelain` to skip no-op runs; not built now.
9. **Scope = the session's `cwdPath`** (confirmed; phase-12 decision 7 precedent): for a
   monorepo session that is the package folder; polyrepo = the worktree. Siblings of a
   monorepo package are not in the graph (alternative: the whole worktree, bigger and
   slower — revisit if a monorepo session actually needs cross-package paths).
10. **The prompt block is ours and provider-neutral** (proposal): a constant in
    `GraphifyService`, appended through the existing `extraSystemPrompt` seam for both
    providers (it merges into the `instructions`/system prompt on both sidecars —
    `SidecarManager.buildArgs`, line 188). Graphify's own `always_on/claude-md.md` text is
    CLI-flavoured (`graphify query …` in Bash) and assumes its hooks; not reused.
11. **Build status is in-memory + journaled, not persisted** (proposal): `GraphifyService`
    keeps `{status: BUILDING|READY|FAILED, since, nodes, edges}` per session and journals a
    `code_intel_status {tool, status, nodes?, edges?, durationMs?, message?}` event on each
    transition (drives the chip and a transcript line). After a backend restart the status
    is derived: READY iff `graph.json` exists, else a build is kicked when the session next
    spawns (resume/wake). No new column.
12. **Upgrade default for the selector** (confirmed): `mcp.code-intel` unset → `serena` when
    `mcp.serena-root` is non-blank, else `none`. So an install that already uses Serena
    keeps working with no Settings visit; nobody gets graphify by accident.
13. **Validation of the graphify root** (proposal): directory; `pyproject.toml` contains
    `name = "graphifyy"`; `<uv> run --directory <root> --no-dev --extra mcp --extra sql
    graphify --version` exits 0 with a **180 s** timeout (Serena's 60 s is too short for a
    cold first sync of ~110 wheels); the error text tells the user to run that exact
    command once by hand if it timed out.
14. **Nothing of graphify's own integration surface is used** (decision, from the review):
    no skill, no `install`, no git hooks, no `hook-guard`, no semantic backend, no
    `graph.html`. The MCP server and the `update` CLI are the whole contract; both were
    exercised live in Step 0.

## Steps

### Step 0 — Spike + prerequisite (done for WSL; macOS is a prerequisite, not code)

Done above ("Step 0 spike — confirmed live"). Remaining prerequisite on the macOS target:
`uv` on the backend's PATH (or `mcp.uv-path` set) and a `git clone https://github.com/
Graphify-Labs/graphify` checkout at the reviewed commit (`c7ec108`, v0.9.62) — `DEPLOY.md`
gets a row for it in Step 4. Re-review before moving the checkout to a newer commit.

### Step 1 — Settings + `GraphifyService`

Edit sites: `config/Settings.java` / `SettingsPatch.java` / `SettingsService.java`
(`mcp.code-intel`, `mcp.graphify-root`; the selector's computed default per decision 12
lives in `SettingsService.current()` after field resolution — the `Field` default supplier
can't see another key), `web/SettingsController.java` (validation → 400), new
`integration/GraphifyService.java`, `components/SettingsDialog.tsx` ("MCP servers"
section: a three-way segmented control or `<select>` for the selector above the two roots,
a Graphify root input with the same blur-to-save + inline error pattern as the Serena root),
`protocol.ts` (`Settings.codeIntel`, `Settings.mcpGraphifyRoot`), `api/rest.ts` if the
patch type is spelled out there.

- `GraphifyService` (follow `SerenaService`'s shape exactly — `@Autowired` constructor plus a
  package-private one taking a `ProcessRunner` seam): `configured()`, `root()`,
  `uvCommand()` (reads `mcp.uv-path`, same as Serena), `validate(root, uvPath)` per
  decision 13, `graphDir(UUID sessionId)` = `Path.of(props.worktreeRoot(), ".graphify",
  id.toString())`, `baseCommand()` = `[<uv>, run, --directory, <root>, --no-dev, --extra,
  mcp, --extra, sql]`, and `SYSTEM_PROMPT_BLOCK` (decision 10 — the text is in Step 2).
- `SettingsController.update`: validate `mcpGraphifyRoot` when present (blank always
  passes — clearing must never be blocked *unless* it is the selected tool); validate the
  selector against the roots as they would be *after* the patch (`serena` needs a
  non-blank Serena root, `graphify` a non-blank graphify root; a patch that blanks the
  selected tool's root → 400 "select none first").
- Tests: `SettingsServiceTest` round-trip of both keys + the decision-12 default (unset
  selector with/without a Serena root); `GraphifyServiceTest` (temp dir for the
  `pyproject.toml` check, fake runner for the `--version` probe — mirror
  `SerenaServiceTest`); `SettingsController` validation cases can be plain unit tests on
  the validation helper if the controller isn't currently unit-tested (check first).

DoD: Settings shows the selector + root; a wrong root is rejected with a readable message;
`graphify` can't be selected without a root; `GET /api/settings` round-trips both values;
an install with only a Serena root reads `codeIntel: "serena"` before anyone touches the
dialog.

### Step 2 — Session model: `codeIntel`, MCP layering, prompt block, frontend

Edit sites: `db/migration/V17__code_intel.sql`, `session/SessionEntity.java` (+builder),
`session/SessionRepository.java` (insert/select column), `session/SessionConfigFactory.java`
(`prepare()` resolution; `withDefaultSerenaMcp` → `withDefaultCodeIntelMcp` switching on the
resolved tool; `serenaSystemPromptBlock` → `codeIntelSystemPromptBlock`; `copyTunables`
writes `codeIntelEnabled`), `process/SidecarManager.java` (`MCP_TIMEOUT` stays Serena-only:
`"serena".equals(session.codeIntel())`), `web/SessionController.java` DTOs (`SessionSummary`
gains `codeIntel`), `session/SessionService.java` line ~607 (the `.serena/` exclude comment
references `serenaEnabled` — wording only), plus the frontend: `protocol.ts`
(`SessionEntity.codeIntel`, `SessionSummary.codeIntel`), `api/rest.ts`,
`components/CreateSessionDialog.tsx` (checkbox label from `settings.codeIntel`, hidden when
`none`; sends `codeIntelEnabled`), `components/TemplateManager.tsx` (the promoted field —
check whether phase 12 added `serenaEnabled` to `PROMOTED_KEYS`; it is not there today, so
templates carry it in the raw JSON — keep that, just rename), `components/DuplicateDialog.tsx`
(copies the flag), `components/SessionWidget.tsx` (chip text = `entity.codeIntel`; graphify
gets the status modifier from Step 3).

- V17:
  ```sql
  ALTER TABLE session ADD COLUMN code_intel TEXT;
  UPDATE session SET code_intel = 'serena' WHERE serena_enabled;
  ALTER TABLE session DROP COLUMN serena_enabled;
  ```
- `prepare()`: `boolean enabled = config.path("codeIntelEnabled").asBoolean(
  config.path("serenaEnabled").asBoolean(false))` (legacy alias, decision 5); if enabled
  and the selector is `none` → 400 "Code intelligence is not configured (Settings → MCP
  servers)"; if the selected tool's `configured()` is false → the same 400 naming the tool;
  else `codeIntel = selector`. A session created with the flag *off* never reads either
  service.
- `withDefaultCodeIntelMcp(configured, provider, codeIntel, cwdPath, sessionId)`:
  `"serena"` → the existing `serenaMcpServer(...)` unchanged; `"graphify"` →
  ```json
  {"graphify": {"command": "<uv>", "args": ["run", "--directory", "<root>", "--no-dev",
    "--extra", "mcp", "--extra", "sql", "graphify-mcp", "<graphDir>/graph.json"]}}
  ```
  (no `env` — the server needs none; both sidecars pass `{command, args}` through,
  confirmed for Codex by phase 12 B2). Own key `graphify` in the session's `mcpConfig`
  wins, as for every other default.
- Prompt block (decision 10), appended for both providers when `codeIntel == "graphify"`:
  > A graphify knowledge graph of this project's code (AST-derived: files, classes,
  > functions, calls, imports, inheritance; communities = subsystems) is available through
  > the `graphify` MCP server. For any question about structure, callers/callees, how two
  > parts connect, or where something lives, query it first — `query_graph` (question →
  > scoped subgraph), `get_node`, `get_neighbors`, `shortest_path`, `get_community`,
  > `god_nodes`, `graph_stats` — then read exactly the files it points to, instead of
  > grepping or reading files one by one. The graph is rebuilt in the background after
  > each of your turns and may lag your latest edits by a few seconds; right after session
  > start it may still be building — a tool error saying graph.json is not found means
  > wait briefly and retry. Documentation files are not in the graph.
- Tests: `SessionConfigFactoryTest` — graphify entry present iff enabled + selector
  `graphify` + root configured; Serena entry unchanged when selector `serena`; session's own
  `graphify` key wins; prompt block present for **both** providers when graphify, Serena's
  override still Claude-only; legacy `serenaEnabled: true` in a template config resolves;
  flag on with selector `none` → 400 text. `SessionRepositoryDbTest` (integration-tagged)
  covers the new column round-trip.

DoD: create a graphify session → the "sidecar pid … spawned" line's `--mcp-config` file has
the `graphify` entry pointing at `<worktree-root>/.graphify/<id>/graph.json`; the agent's
tool list includes `mcp__graphify__*`; a Serena session's mcpConfig is byte-identical to
phase 12's; a session with the flag off is byte-identical to phase 12's; the widget chip
reads the tool name.

### Step 3 — Build pipeline: async build, post-turn refresh, status, cleanup

Edit sites: `integration/GraphifyService.java` (`build(SessionEntity)`, `refreshAfterTurn
(UUID)`, `status(UUID)`, `delete(UUID)`, the executor, the journal event), `session/
SessionService.java` (`create()` after `writeMcpConfig(entity)` → `graphify.build(...)`;
`onSidecarEvent` `turn_complete` housekeeping try-block → `graphify.refreshAfterTurn(id)`;
`resume`/wake path → `graphify.ensureBuilt(...)` (decision 11); both close paths → `graphify.
delete(id)`), `web/MaintenanceController.java` (`clean()` also removes `.graphify/<id>` for
non-active ids), `docs/PROTOCOL.md` (WS backend-originated events list gains
`code_intel_status`), `components/SessionWidget.tsx` (chip modifiers), the transcript
renderer that already handles `pr_status_changed`/`warning` (one line per status change).

- Execution: `ProcessBuilder(baseCommand() + ["graphify", "update", cwdPath])`, env per
  decision 7, cwd = the session's worktree, stdout+stderr appended to
  `logs/graphify/<sessionId>.log` (same `props.logDir()` root as the sidecar logs), hard
  timeout 15 min (`destroyForcibly` → FAILED "timed out"). A bounded executor (2 threads —
  builds are I/O-bound; more parallel walks on DrvFS only slow each other down) with
  per-session single-flight: `build`/`refreshAfterTurn` on a session whose build is
  running sets a `dirty` flag and returns; the running build re-runs once when it finishes
  and the flag is set (coalescing, decision 8). Parse `Rebuilt: N nodes, M edges` from the
  output for the event; exit ≠ 0 → FAILED with the last stderr line as `message`.
- `code_intel_status` payload: `{tool: "graphify", status: "BUILDING"|"READY"|"FAILED",
  nodes?, edges?, durationMs?, message?}`; journaled (so it replays) and broadcast like
  `pr_status_changed`. A FAILED build does not fail the session — the MCP server keeps
  running and the agent's tool calls return the "not found" tool error; the next turn
  triggers a retry.
- Widget: `.chip` `graphify` with `.pulse` while BUILDING, plain when READY (title: "graph:
  N nodes / M edges, built X ago"), `--red` text when FAILED (title: the message). Colour
  = state (styles rules), no new tokens.
- Close: `delete(id)` removes the graph dir (best-effort, logged) on both the
  already-CLOSED/deleteRecursively path and the normal `removeWorktree` path; a running
  build for that session is killed first.
- Tests: `GraphifyServiceTest` with a fake runner — BUILDING→READY event sequence and
  parsed counts; exit ≠ 0 → FAILED with message; refresh during a build coalesces to
  exactly one extra run; `delete` while running kills and removes; `ensureBuilt` skips when
  `graph.json` exists. `SessionService` tests use `FakeSidecarManager` etc. — add a fake
  `GraphifyService` seam only if the constructor wiring needs it (check how
  `SerenaService` is injected into the existing fakes first).

DoD: a new graphify session on this repo shows the chip pulsing, then `READY` with counts
within the measured build time; `mcp__graphify__graph_stats` answers; after a turn that
edits a Java file, `logs/graphify/<id>.log` shows one more `update` run and `get_node` on
the new symbol finds it; two quick turns produce two runs, not three; closing the session
removes `<worktree-root>/.graphify/<id>`; `POST /api/maintenance/orphans/clean` removes a
leftover dir for a session that no longer exists.

### Step 4 — Docs + decision log

- `CLAUDE.md`: the "MCP servers" bullet under persisted settings describes the selector,
  the graphify root, the per-session `codeIntelEnabled` flag (and that `serenaEnabled` is
  its legacy alias), the graph dir, the post-turn refresh and its cost, and the review
  posture (no skill/hooks/install, code-only). `docs/ARCHITECTURE.md`: a §3c-style
  sketch of `GraphifyService` (build lifecycle, coalescing, cleanup). `docs/DEPLOY.md`:
  the checkout + `uv` prerequisite rows, `mcp.graphify-root`, the first-sync note.
  `docs/PROTOCOL.md`: `code_intel_status` (done in Step 3, checked here).
- `docs/plan/README.md`: phase 13 row (already added with this plan); decision-log rows per
  landed step, in the phase-12 style (what changed on contact with the code).

## Out of scope for this phase

- **Semantic pass (docs/PDFs/images in the graph)** via `--backend claude-cli` (`claude -p`
  on the user's subscription) — the named follow-up; needs a per-session cost/time budget
  and a decision on which docs. Everything else (`add <url>`, video, Google Workspace,
  Neo4j/FalkorDB push, wiki/Obsidian export) is not planned.
- Graphify's Claude Code hooks (`hook-guard`, strict mode) and instruction-file installs —
  decision 14; our prompt block is the nudge.
- Showing `GRAPH_REPORT.md` or `graph.html` in the dashboard (the report is reachable to
  the agent as the `graphify://report` MCP resource already).
- `graphify affected` (blast radius from a diff) wired into commit/PR drafting — a nice
  follow-up once the graph exists per session.
- Change-detection before a refresh (decision 8's named optimization), and a per-session
  "rebuild now" button (the next turn does it).
- Per-session choice of tool (declined by decision 1) and running Serena + graphify
  together.
- Whole-worktree scope for monorepo sessions (decision 9).

## Definition of Done (whole phase)

- Every step's DoD above holds.
- With `mcp.code-intel = graphify` and a valid root, a session created with the flag
  (Claude and Codex) lists `mcp__graphify__*` tools, gets the prompt block, builds its graph
  in the background (chip pulsing → READY), can answer "what calls X" via `query_graph`, and
  sees a symbol it added itself after the following turn; its Git panel and `git status`
  show nothing graph-related; closing it removes the graph dir.
- With `mcp.code-intel = serena`, phase 12's Serena behaviour is unchanged (same MCP entry,
  same Claude-only override, same `MCP_TIMEOUT`), and existing templates carrying
  `serenaEnabled: true` still create Serena sessions.
- With `none`, the checkbox is absent; a template with the flag set fails creation with a
  readable 400; a flag-off session's mcpConfig and system prompt are byte-identical to
  phase 12's.
- Settings refuses the invalid combinations (tool without root, blanking the selected
  tool's root) with readable 400s; a wrong root is rejected with its reason; a valid root's
  first save performs the env sync within the 180 s budget or tells the user what to run.
- Nothing is ever written into a worktree by this phase; no process from this phase ever
  runs without `GRAPHIFY_OUT`, `--no-dev` and the `update` (AST-only) command.
- Unit tests green (`./mvnw -Dskip.installnodenpm -Dskip.npm -DexcludedGroups=integration
  test`), `SessionRepositoryDbTest` green against the compose DB, frontend `npm test` +
  `npm run build` green, both themes checked for the chip states.

## Manual test script

1. Settings → MCP servers: selector reads `Serena` (an existing Serena root) — decision 12.
   Set Graphify root to `/nonexistent` → inline error. Set it to `/mnt/d/projects/graphify`
   (macOS: your checkout) → saves; the first save may take up to a minute (env sync) and
   the input pulses meanwhile. Select `Graphify` → saves. Try blanking the Graphify root →
   inline "select none first".
2. New Session on this repo, tick **Code intelligence (Graphify — …)**, Claude provider →
   the spawn log line's `--mcp-config` file has a `graphify` entry pointing at
   `~/claude-worktrees/.graphify/<id>/graph.json`; the widget chip `graphify` pulses; within
   ~2 min on WSL (seconds on macOS) it goes steady and the transcript shows
   "graph ready — N nodes / M edges"; `logs/graphify/<id>.log` has the `update` output.
3. Ask the agent "using the graphify tools, what does SessionConfigFactory.prepare depend
   on, and which classes call SidecarManager.spawn?" → it calls `mcp__graphify__query_graph`
   / `get_neighbors` (approval flow as usual), then reads the files it named. Git panel shows
   nothing graph-related; `git -C <worktree> status --porcelain` is empty.
4. Ask it to add a small method to a Java class. After `turn_complete`, the log shows one
   more `update` run; ask "use get_node on <that method>" → found.
5. Send two short prompts back-to-back while a build is running → the log shows exactly
   one additional run after the current one (coalescing).
6. Same as 2 with Codex → same entry, the prompt block present in the sidecar's
   instructions, a graphify tool call goes through the normal approval flow.
7. Break the build (temporarily set an invalid uv path in Settings, create a session) →
   chip turns red with the message in its title, transcript has the failure line, the
   session itself is IDLE and usable; fix Settings, send a turn → the refresh succeeds and
   the chip turns steady.
8. Duplicate a graphify session → the flag stays ticked. Close a graphify session →
   `~/claude-worktrees/.graphify/<id>` is gone. Create a stray `~/claude-worktrees/
   .graphify/00000000-0000-0000-0000-000000000000/` → `POST /api/maintenance/orphans/clean`
   removes it.
9. Switch the selector to `Serena` → a running graphify session's chip still reads
   `graphify` (baked at creation); the create dialog's checkbox label now names Serena; a
   new session with the flag gets the phase-12 Serena entry and `MCP_TIMEOUT`. Switch to
   `none` → the checkbox disappears; a template with the flag on fails with the 400 text.
10. Restart the backend with a graphify session open → after wake the chip reads READY
    (graph.json exists, no rebuild); delete `graph.json` by hand, wake the session → a
    build is kicked.
11. Light theme pass over the chip's three states and the transcript status lines.
