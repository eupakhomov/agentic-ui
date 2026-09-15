# Phase 14 — CodeGraph MCP (third code-intelligence tool, one-of with Serena/graphify)

Status: **proposed (2026-09-16)** — decisions 1 and 2 confirmed with the user before this doc
was written; the rest are proposals to confirm before the step that needs them. Verified
against the code as of phase 13 (commit `731cb31`) and against the codegraph checkout at
`/mnt/d/projects/codegraph` (v1.6.0, commit `1f0cbbd`, 2026-09-15); every edit site named
below was checked to exist as described, and every codegraph behaviour relied on was run
live (Step 0).

## Background

### What codegraph is

[CodeGraph](https://github.com/colbymchenry/codegraph) (npm `@colbymchenry/codegraph`,
MIT) indexes a project into a local SQLite knowledge graph (`<project>/.codegraph/
codegraph.db`: symbols, edges, files, FTS5) — tree-sitter extraction of 30+ languages,
deterministic, no LLM — and serves it as a **stdio MCP server** (`codegraph serve --mcp`)
whose default surface is **one tool**, `codegraph_explore`: a natural-language question or
a bag of symbol/file names → the relevant symbols' verbatim line-numbered source grouped by
file, the call paths among them (including dynamic-dispatch hops), and a blast-radius
summary. Seven narrower tools (`node`/`search`/`callers`/`callees`/`impact`/`files`/
`status`) exist but are unlisted unless `CODEGRAPH_MCP_TOOLS` names them. The server runs
its **own file watcher** (native OS events, 2 s debounce) and a catch-up sync at startup,
so the index tracks the working tree without any external refresh.

Compared with the two tools we have: Serena (phase 12 B) is a *live* language-server view —
precise symbol lookup and symbol-level edits; graphify (phase 13) is a *pre-built* structural
map we rebuild ourselves after every turn. CodeGraph sits between them — a pre-built graph
that keeps itself fresh — and, like both, spawns a process per session and wants a strong
"use me instead of Read/Grep" system prompt. Same overlap, same conflict ⇒ same **one-of**.

### Security review (done 2026-09-16, before this plan)

Read: `TELEMETRY.md`, `src/telemetry/index.ts`, `src/upgrade/update-check.ts` +
`upgrade/index.ts`, `src/installer/beta-signup.ts` + `installer/targets/{claude,shared}.ts`,
`src/mcp/{index,daemon,daemon-paths,writer-lock,liveness-watchdog,server-instructions}.ts`,
the `projectPath`/file-read paths of `src/mcp/tools.ts`, `src/utils.ts`
(`validatePathWithinRoot`, `validateProjectPath`), `src/directory.ts`, `src/sync/
{git-hooks,worktree,watch-policy}.ts`, `src/extraction/{index,wasm-runtime-flags}.ts`,
`src/extraction/kernel/loader.ts`, `BUNDLING.md`, `install.sh`, `package.json` +
`package-lock.json`. Grepped `src/` for every `fetch`/URL, `child_process` call site,
`eval`/`new Function`/`vm`, and writes outside the project dir.

Clean for our purposes:
- **All egress is enumerable and switchable.** (1) Telemetry — first-party endpoint,
  **default on**; payload = random machine UUID, version, os/arch, Node major, `CI` flag,
  tool/CLI-command names with daily counts, the MCP client's name/version, indexed language
  names and bucketed file counts/durations. Never paths, code, symbols or queries (the
  client matches `TELEMETRY.md`; `config-secret-redaction.test.ts` also keeps config
  *values* out of tool output). Off via `CODEGRAPH_TELEMETRY=0` or `DO_NOT_TRACK=1`, and
  off means no record, no socket, no config file written. (2) Update check — a GitHub
  release tag, at most daily, validated as version-shaped before being echoed into the MCP
  `instructions`/`codegraph_status`; off via `CODEGRAPH_NO_UPDATE_CHECK=1`/`DO_NOT_TRACK`.
  (3) Beta-waitlist signup — only from the interactive installer, TTY-gated, needs a typed
  email. (4) `codegraph upgrade` — downloads and replaces the install. Nothing else opens
  a connection; the `fetch` hits in `resolution/` are symbol names, not calls.
- **Every subprocess is a fixed argv, no shell**: `execFileSync('git', […])` for ls-files/
  rev-parse/check-ignore/config, `process.execPath` self-spawns (the WASM-heap relaunch,
  parse workers, the `-e` liveness watchdog), `/usr/bin/vm_stat` on macOS. The only
  `execSync` string commands live in the installer (`npm install -g`, `command -v`).
- **MCP surface is read-only** and file reads are confined to the index root by lexical +
  realpath containment (`validatePathWithinRoot`). `projectPath` can address any
  *initialized* project on the machine (sensitive dirs blocked) — a cross-session read of
  another worktree's source, acceptable for a single-user box and no wider than the
  ecosystem read-only context we already hand out.
- **Filesystem writes** are `<project>/.codegraph/` (db + WAL, `writer.pid` 0600, `daemon.
  pid`/`daemon.sock` 0600 in daemon mode, a self-`.gitignore`), `~/.codegraph/` (telemetry
  config, update cache, daemon registry, beta choice — none written in our posture, confirmed
  in Step 0), and a tmpdir socket fallback. Git hooks are opt-in via the installer only. No
  project source file is ever written.
- **Supply chain**: 10 runtime deps (commander, picomatch, ignore, jsonc-parser, clack,
  web-tree-sitter, tree-sitter-wasms, …), 255 packages in the lock, install scripts only
  in esbuild/fsevents (dev). From a source checkout there is **no native code**: SQLite is
  Node's built-in `node:sqlite`, parsing runs on the 29 vendored tree-sitter `.wasm`
  grammars (opaque blobs, but WASM-sandboxed), and the optional Rust kernel is only loaded
  if someone builds it. npm releases carry provenance attestations; `install.sh` does not
  verify checksums (not our path).

Posture we adopt because of what we found (the wrong shape for a backend-driven
integration, not bugs):
1. **Never `codegraph install`/`uninstall`/`upgrade`, nor npm's `preuninstall`.** They
   rewrite `~/.claude/settings.json` (a `UserPromptSubmit` "front-load" hook, an
   `mcp__codegraph__*` allow-all), `~/.claude.json`, `~/.claude/CLAUDE.md`, Codex's
   `config.toml`, git hooks — or replace the binary. We write our own MCP entry, our own
   prompt block, and go through our own approval flow.
2. **Telemetry, update check and the shared daemon are off on every process we spawn**:
   `CODEGRAPH_TELEMETRY=0`, `DO_NOT_TRACK=1`, `CODEGRAPH_NO_UPDATE_CHECK=1`,
   `CODEGRAPH_NO_DAEMON=1`. Direct mode ties the MCP server to the sidecar's lifetime via
   its PPID watchdog; a detached daemon would outlive worktree deletion and keep a watcher
   on a vanished directory for up to 30 min.
3. **Reviewed local checkout, built with `npm ci --ignore-scripts` + `tsc` only** — no UI
   workspace build, no kernel, no lifecycle scripts (decision 1). Re-review before moving
   the checkout to a newer commit.
4. **`.codegraph/` cannot leave the project root** (`CODEGRAPH_DIR` accepts a bare name
   only), so unlike graphify it lands inside the worktree: excluded from git via the same
   `info/exclude` line as `.serena/` (Step 0 confirmed `git status` shows `?? .codegraph/`
   despite its own `.gitignore` — the directory itself is what needs excluding).

### Step 0 spike — confirmed live (2026-09-16, WSL, checkout `/mnt/d/projects/codegraph`)

Built the checkout (`npm ci --ignore-scripts` 22 s, `npx tsc && npm run copy-assets` 13 s,
`dist/` 76 MB with source maps; `node dist/bin/codegraph.js version` → `1.6.0`), then ran
everything against a throwaway clone of this repo in the scratchpad (ext4, like
`~/claude-worktrees`) with the posture env above. Node 24.19 (the CLI gates `>=20 <25`;
`node:sqlite` needs ≥ 22.5 when run from source).

- `codegraph init <dir> --yes` (non-interactive): **209 files → 5,466 nodes / 11,788 edges
  in 0.8 s, 6 s wall** including Node startup and the WASM-flag relaunch. Output dir
  `.codegraph/` = 18 MB (`codegraph.db` + `.gitignore`); `git status` → `?? .codegraph/`.
  `~/.codegraph/` was **not created** (telemetry off ⇒ no config write). `codegraph status`
  prints per-kind and per-language counts.
- `serve --mcp --path <dir>` over stdio (`CODEGRAPH_NO_DAEMON=1`): initialize **2.3 s**,
  `instructions` = the full playbook, stderr `File watcher active — graph will auto-sync on
  changes` (the WSL watch policy only disables `/mnt/*`; ext4 under WSL watches fine),
  `tools/list` = `[codegraph_explore]`. `codegraph_explore("which classes call
  SidecarManager.spawn and what does SessionConfigFactory.prepare depend on")` → **372 ms**,
  22 KB: dynamic-dispatch links, blast radius with callers + tests, verbatim line-numbered
  source of 57 symbols in 5 files.
- **Late index is picked up live**: server started on an un-indexed clone (`No .codegraph/
  at or above …: no default project, live sync disabled`, the weaker `SERVER_INSTRUCTIONS_
  NO_ROOT_INDEX` variant), then `init` → `File watcher active` appears on the running server
  and `explore` answers 750 ms later. So the ordering is safe either way; decision 2 still
  indexes first so the connection gets the full instructions.
- The server does a **catch-up sync at startup** (`engine.ts` `catchUpSync`), so a PARKED
  session's index is brought up to date when the sidecar wakes; a stale `writer.pid` from a
  killed server is pid-verified and cleared (`writer-lock.ts`).
- `.codegraph/` after a server run: `codegraph.db`, `-shm`, `-wal`, `writer.pid`; nothing
  outside it.

## Target behavior

- Settings → "MCP servers": the **Code intelligence** selector gains `CodeGraph` beside
  `none`/`Serena`/`Graphify`, plus a **CodeGraph root** input (path to a built codegraph
  checkout; empty = unavailable). Save validates the root (directory, `package.json` names
  `@colbymchenry/codegraph`, `dist/bin/codegraph.js` exists, `node <root>/dist/bin/
  codegraph.js version` exits 0 within 30 s) and applies the phase-13 one-of rules
  (selecting a tool without its root, or blanking the selected tool's root → 400).
- Create dialog + template: the existing **Code intelligence** checkbox names CodeGraph when
  selected ("CodeGraph — code graph with verbatim source, call paths and blast radius…");
  `codeIntelEnabled` resolves to `codeIntel = "codegraph"` at creation; the widget chip reads
  `codegraph`.
- A session created with the flag while the selector is `codegraph`:
  - has its index **built synchronously during PROVISIONING**, right after the worktree and
    provisioned assets exist and before the sidecar spawns (`codegraph init <cwdPath> --yes`,
    a few seconds for a repo this size; hard cap 5 min → the session still starts, unindexed,
    with a warning in the transcript and a `--red` chip);
  - gets a `codegraph` stdio MCP entry layered into its `mcpConfig` (unless it already
    declares one — same rule as Linear/memory/Serena/graphify): `node <root>/dist/bin/
    codegraph.js serve --mcp --path <cwdPath>` with the posture env, plus a short
    provider-neutral prompt block (ours) for both providers;
  - needs **no refresh pipeline**: the server's own watcher and startup catch-up keep the
    index current (the `code_intel_status` chip goes BUILDING → READY once, at creation);
  - keeps `.codegraph/` out of `git status` through `info/exclude`; the directory disappears
    with the worktree on close (no orphan sweep needed).
- Serena and graphify sessions behave exactly as after phase 13; a session with the flag off
  has an `mcpConfig` and system prompt byte-identical to phase 13's.

## Decisions (1–2 confirmed 2026-09-16; 3–10 proposals to confirm before the step that needs them)

1. **Reviewed local checkout, run on the backend's Node** (confirmed): `mcp.codegraph-root`
   → `node <root>/dist/bin/codegraph.js …`. `node` is resolved from the backend's PATH
   exactly as the provider launch commands (`["node", "sidecar/dist/index.js"]`) already
   are — no new `mcp.node-path` setting (offered; the providers make PATH `node` a
   prerequisite anyway). The official bundle (`npm i -g`, vendored Node + prebuilt Rust
   kernel, self-upgrading) was declined: unreviewed binaries. The WASM parser path produces
   the same graph, just slower — 0.8 s for this repo, so it does not matter.
2. **Initial index is synchronous, inside PROVISIONING** (confirmed): `SessionService.create`
   runs `codegraph init` between `assets.provision` and `writeMcpConfig`/`spawn`. Graphify's
   async executor + coalesced post-turn refresh is not reused — codegraph refreshes itself.
   Failure/timeout does **not** fail the session (`warning` journal line + `code_intel_status
   FAILED`; the server runs in per-project mode and the prompt block tells the agent to use
   Read/Grep); `resume`/wake re-run `ensureIndexed` only when `.codegraph/codegraph.db` is
   missing.
3. **Index root = the session's `cwdPath`** (proposal; phase-12 decision 7 / phase-13
   decision 9 precedent): for a monorepo session the package folder — codegraph's file
   listing is `git ls-files` from that cwd, so siblings are out of the graph; polyrepo = the
   worktree. `serve --mcp --path <cwdPath>` pins the server root explicitly instead of
   relying on the sidecar's cwd.
4. **`.codegraph/` in the worktree + `info/exclude`** (proposal; forced by codegraph — see
   posture 4): `SessionService.excludeProvisionedAssets` gains a `<prefix>.codegraph/` line
   next to `.serena/`, unconditionally like the others (harmless when absent). The agent
   *can* read/edit the 18 MB SQLite file — a `readOnlyDenial` for `.codegraph/**` was
   considered and dropped: it lives in `canUseTool` (Claude only, not in
   `bypassPermissions`), and there is nothing secret in an index of the agent's own worktree.
5. **Posture env on every process** (proposal): `CODEGRAPH_TELEMETRY=0`, `DO_NOT_TRACK=1`,
   `CODEGRAPH_NO_UPDATE_CHECK=1`, `CODEGRAPH_NO_DAEMON=1`, `NO_COLOR=1`, `CI=1` (non-interactive
   output) — as the MCP entry's `env` (both sidecars pass `{command, args, env}` through:
   `sidecar-codex/src/mcp.ts` line 44) and on the backend's `init`/`version` processes. Set
   explicitly on the entry rather than relying on the backend's environment.
6. **Default tool surface only** (proposal): `codegraph_explore` alone, as codegraph ships it
   (their measured finding: one strong tool steers agents better than eight). No
   `CODEGRAPH_MCP_TOOLS`; if a session ever needs `callers`/`impact` as separate tools the
   env knob is a one-line follow-up on the entry.
7. **Prompt block is ours, short, provider-neutral** (proposal): codegraph's own
   `SERVER_INSTRUCTIONS` already reach the agent through the MCP `initialize` response
   (Claude Code surfaces server instructions; Codex is unverified), so our block only says
   what the server cannot know — that the index was built by us at session start, that a
   FAILED chip means "index unavailable, use Read/Grep", and not to run `codegraph init`/
   `sync`/`install` itself. Appended through the existing `extraSystemPrompt` seam for both
   providers, like graphify's.
8. **Status event reuse** (proposal): the existing `code_intel_status {tool, status, nodes?,
   edges?, durationMs?, message?}` event with `tool: "codegraph"`, emitted BUILDING at the
   start of `init` and READY/FAILED at its end (counts parsed from init's `N nodes, M edges`
   line, thousands separators stripped; absent when unparseable). The widget's existing
   chip class/title logic needs no change beyond the READY title wording ("index N nodes /
   M edges, built X ago — kept fresh by codegraph's watcher"). No new column.
9. **Validation of the root** (proposal): directory; `package.json` has `"name":
   "@colbymchenry/codegraph"`; `dist/bin/codegraph.js` is a regular file (else the error
   names the exact build commands to run in the checkout: `npm ci --ignore-scripts && npx tsc
   && npm run copy-assets`); `node <root>/dist/bin/codegraph.js version` exits 0 within
   **30 s** printing a version (the WASM-flag relaunch spawns a second Node — budget for two
   startups). Unlike graphify, the probe installs nothing.
10. **Nothing of codegraph's own integration surface is used** (decision, from the review):
    no installer, no prompt hook, no CLAUDE.md block, no `alwaysLoad`, no git hooks, no
    `codegraph ui`, no daemon, no `upgrade`. `init`, `serve --mcp` and `version` are the whole
    contract; all three were exercised live in Step 0.

## Steps

### Step 0 — Spike + prerequisite (done for WSL; macOS is a prerequisite, not code)

Done above ("Step 0 spike — confirmed live"). Remaining prerequisite on the macOS target:
Node ≥ 22.5 (< 25) on the backend's PATH (already required by the providers) and a
`git clone https://github.com/colbymchenry/codegraph` checkout at the reviewed commit
(`1f0cbbd`, v1.6.0) built once with `npm ci --ignore-scripts && npx tsc && npm run
copy-assets` — `DEPLOY.md` gets a §8d for it in Step 3. Re-review before moving the
checkout to a newer commit.

### Step 1 — Settings + `CodegraphService`

Edit sites: `config/Settings.java` (`mcpCodegraphRoot`), `config/SettingsPatch.java` (field +
builder), `config/SettingsService.java` (`MCP_CODEGRAPH_ROOT_KEY = "mcp.codegraph-root"`,
`CODE_INTEL_CODEGRAPH = "codegraph"` added to `CODE_INTEL_VALUES`, `validateCodeIntel` gains
the codegraph-root arm; decision-12 default of phase 13 unchanged — nobody gets codegraph by
accident), `web/SettingsController.java` (validate `mcpCodegraphRoot` when present, blank
passes; pass the would-be codegraph root into `validateCodeIntel`), new
`integration/CodegraphService.java`, `components/SettingsDialog.tsx` (a fourth `<option
value="codegraph" disabled={!settings.mcpCodegraphRoot}>` and a CodeGraph root input with the
same blur-to-save + inline-error pattern; the save is quick, so no pulse state needed unlike
graphify's), `protocol.ts` (`Settings.mcpCodegraphRoot`, `CodeIntel` union gains
`'codegraph'`), `api/rest.ts` if the patch type is spelled out there.

- `CodegraphService` (follow `SerenaService`'s shape — `@Autowired` constructor plus a
  package-private one taking a `ProcessRunner` seam; **not** `GraphifyService`'s executor
  shape): `configured()`, `root()`, `validate(root)` per decision 9, `baseCommand()` =
  `["node", "<root>/dist/bin/codegraph.js"]`, `postureEnv()` per decision 5, `indexDir(cwd)` =
  `<cwd>/.codegraph`, `SYSTEM_PROMPT_BLOCK` (decision 7 — text in Step 2), and the Step 2
  index methods.
- Tests: `SettingsServiceTest` round-trip of the new key + selector value; `CodegraphServiceTest`
  (temp dir with a fake `package.json`/`dist/bin/codegraph.js`, fake runner for the `version`
  probe — mirror `SerenaServiceTest`); `validateCodeIntel` cases for the new arm.

DoD: Settings shows the fourth option + root input; a wrong root (no `package.json`, wrong
name, unbuilt checkout) is rejected with a readable message naming the fix; `codegraph`
can't be selected without a root; `GET /api/settings` round-trips the root; Serena/graphify
settings behave as before.

### Step 2 — Session model: index-at-create, MCP layering, prompt block, exclude, frontend

Edit sites: `session/SessionConfigFactory.java` (`resolveCodeIntel` switch gains
`CODE_INTEL_CODEGRAPH -> codegraph.configured()`; `withDefaultCodeIntelMcp` gains the
`"codegraph"` arm → `withDefaultServer(configured, "codegraph", codegraphMcpServer(cwdPath))`;
`codeIntelSystemPromptBlock` returns `CodegraphService.SYSTEM_PROMPT_BLOCK` for both
providers), `session/SessionService.java` (`create()`: after `assets.provision` and before
`writeMcpConfig` → `codegraph.index(entity)` when `codeIntel == "codegraph"`; `resume` and
the wake path next to the two `graphify.ensureBuilt` calls → `codegraph.ensureIndexed`;
`excludeProvisionedAssets` → the `.codegraph/` line, and its javadoc's "Graphify needs no
line" sentence gains the codegraph contrast; no close/turn_complete hooks), `process/
SidecarManager.java` (nothing: `MCP_TIMEOUT` stays Serena-only — initialize is 2.3 s),
`integration/CodegraphService.java` (`index(SessionEntity)`, `ensureIndexed(SessionEntity)`),
`docs/PROTOCOL.md` (`code_intel_status` paragraph: also emitted by `CodegraphService`, once,
during PROVISIONING), plus the frontend: `components/CreateSessionDialog.tsx` (label branch
for `codegraph`), `components/SessionWidget.tsx` (`codeIntelChipTitle` READY wording per
decision 8), `protocol.ts` (`SessionEntity.codeIntel`/`SessionSummary.codeIntel` unions).
`V17`'s `code_intel TEXT` column is free text — **no migration**.

- `codegraphMcpServer(cwdPath)`:
  ```json
  {"codegraph": {"command": "node",
    "args": ["<root>/dist/bin/codegraph.js", "serve", "--mcp", "--path", "<cwdPath>"],
    "env": {"CODEGRAPH_TELEMETRY": "0", "DO_NOT_TRACK": "1",
            "CODEGRAPH_NO_UPDATE_CHECK": "1", "CODEGRAPH_NO_DAEMON": "1"}}}
  ```
  Own key `codegraph` in the session's `mcpConfig` wins, as for every other default.
- `index(entity)`: `ProcessBuilder(baseCommand() + ["init", cwdPath, "--yes"])`, env =
  posture + `NO_COLOR=1` + `CI=1`, cwd = `cwdPath`, stdout+stderr appended to
  `logs/codegraph/<sessionId>.log` (same `props.logDir()` root as sidecar logs), hard timeout
  **5 min** (`destroyForcibly` → FAILED "timed out after 5 min"); journals `code_intel_status
  BUILDING` before and READY/FAILED after (decision 8), plus a `warning` line on FAILED so
  the transcript says why. Exit ≠ 0 → FAILED with the last non-blank output line as
  `message`. Never throws into `create()`.
- `ensureIndexed(entity)`: READY (re-journaled with no counts) iff `<cwd>/.codegraph/
  codegraph.db` exists, else `index(entity)` — covers a session whose first index failed or
  timed out, and a backend restart (the chip replays from the journal anyway).
- Prompt block (decision 7), both providers when `codeIntel == "codegraph"`:
  > A CodeGraph index of this project's code (symbols, calls, imports, inheritance; not
  > docs or configs) was built at session start and is served by the `codegraph` MCP server;
  > its own watcher keeps it current with your edits (about a second behind). Use
  > `codegraph_explore` first for any question about structure, callers/callees, how two
  > parts connect, or to read the source of a symbol you can name — it returns verbatim
  > line-numbered source with call paths and blast radius, so read only the files it points
  > to. If it reports the project isn't indexed, the index build failed on our side: use
  > Read/Grep for the rest of the session. Never run `codegraph init`, `sync`, `install` or
  > `upgrade` yourself.
- Tests: `SessionConfigFactoryTest` — codegraph entry present iff enabled + selector
  `codegraph` + root configured, with the posture env and `--path <cwdPath>`; Serena and
  graphify entries unchanged under their selectors; session's own `codegraph` key wins; prompt
  block for **both** providers; flag on with selector `codegraph` but no root → 400 naming
  the tool. `CodegraphServiceTest` — BUILDING→READY sequence with parsed counts from a
  scripted `5,466 nodes, 11,788 edges` line; exit ≠ 0 → FAILED with message; timeout → FAILED;
  `ensureIndexed` skips when the db exists. `SessionService` tests: the constructor gains a
  `CodegraphService` parameter — null in unit tests like `graphify` (check `FakeSidecarManager`-
  style wiring first; the `if (graphify != null)` guard is the precedent).

DoD: create a codegraph session → state stays PROVISIONING while `logs/codegraph/<id>.log`
fills with the init output, the chip pulses `codegraph` then goes steady with counts in its
title, the "sidecar pid … spawned" line's `--mcp-config` file has the `codegraph` entry with
the posture env, the agent's tool list includes `mcp__codegraph__codegraph_explore`;
`git -C <worktree> status --porcelain` is empty; a Serena/graphify/flag-off session's
mcpConfig and prompt are byte-identical to phase 13's.

### Step 3 — Docs + decision log

- `CLAUDE.md`: the "MCP servers / code intelligence" bullet gains CodeGraph — the selector
  value, `mcp.codegraph-root`, the checkout build commands, the synchronous index during
  PROVISIONING (cost measured), the self-refreshing watcher (no post-turn pipeline), the
  in-worktree `.codegraph/` + exclude line, and the posture (no installer/hooks/upgrade,
  telemetry + update check + daemon off). `docs/ARCHITECTURE.md` §3g: CodeGraph as the third
  one-of tool with a `CodegraphService` sketch (index-at-create, `ensureIndexed`, no cleanup).
  `docs/DEPLOY.md` §8d: the checkout + build + Node-version prerequisite, the root setting,
  the re-review-before-upgrade note. `docs/PROTOCOL.md`: checked in Step 2.
- `docs/plan/README.md`: phase 14 row (added with this plan); decision-log rows per landed
  step in the phase-13 style (what changed on contact with the code).

## Out of scope for this phase

- Codegraph's `UserPromptSubmit` "front-load" hook (runs `codegraph explore` on every prompt
  and injects the result before the turn) — our prompt block is the nudge; a backend-side
  equivalent (explore on the kickoff prompt, prepended to it) is a possible follow-up.
- Exposing the unlisted tools (`CODEGRAPH_MCP_TOOLS`) — decision 6; one env line if wanted.
- `codegraph affected` (test files affected by a diff) wired into commit/PR drafting — a
  natural follow-up, like graphify's `affected`.
- `codegraph ui` (the browser viewer on `127.0.0.1:4747`) in the dashboard.
- A post-turn `codegraph sync` fallback for a worktree root on a filesystem where the
  watcher is disabled (WSL `/mnt/*`) — `CLAUDE_UI_WORKTREE_ROOT` is `~/claude-worktrees`
  by default and the target is macOS; the server's banner tells the agent when auto-sync is
  off.
- Building the Rust kernel from the checkout (`npm run build:kernel`, needs cargo) — the
  WASM path is fast enough for our repo sizes; revisit if a service's index takes minutes.
- Per-session choice of tool and running two tools together (phase-13 decision 1 stands).

## Definition of Done (whole phase)

- Every step's DoD above holds.
- With `mcp.code-intel = codegraph` and a valid root, a session created with the flag (Claude
  and Codex) is indexed before its sidecar spawns, lists `mcp__codegraph__codegraph_explore`,
  gets the prompt block, answers "what calls X" through `codegraph_explore` with verbatim
  source, and sees a symbol it added itself on the next question without any backend action;
  its Git panel and `git status` show nothing index-related; closing it leaves nothing behind
  (the worktree removal takes `.codegraph/` with it); no `~/.codegraph/` appears on the
  backend host.
- With `serena` or `graphify` selected, phase 12/13 behaviour is unchanged (same entries,
  same overrides, same `MCP_TIMEOUT`, graphify's build pipeline untouched).
- With `none`, the checkbox is absent; a template with the flag set fails creation with a
  readable 400; a flag-off session's mcpConfig and system prompt are byte-identical to
  phase 13's.
- Settings refuses the invalid combinations with readable 400s; an unbuilt checkout is
  rejected with the build commands in the message.
- No process from this phase ever runs without the posture env (decision 5); nothing from
  codegraph's installer/hook/upgrade surface is ever invoked (decision 10).
- Unit tests green (`./mvnw -Dskip.installnodenpm -Dskip.npm -DexcludedGroups=integration
  test`), frontend `npm test` + `npm run build` green, both themes checked for the chip
  states and the new Settings row.

## Manual test script

1. Settings → MCP servers: set CodeGraph root to `/nonexistent` → inline error. Point it at
   an un-built clone → error naming `npm ci --ignore-scripts && npx tsc && npm run
   copy-assets`. Set `/mnt/d/projects/codegraph` (macOS: your checkout) → saves within a
   couple of seconds. Select `CodeGraph` → saves. Try blanking the root → "select none
   first".
2. New Session on this repo, tick **Code intelligence (CodeGraph — …)**, Claude provider →
   the session sits in PROVISIONING a few seconds longer than usual; `logs/codegraph/<id>.log`
   ends with `N nodes, M edges in …ms`; the widget chip `codegraph` is steady with those
   counts in its title; the spawn log line's `--mcp-config` file has the `codegraph` entry
   with `--path <worktree>` and the four posture env vars; `ls ~/.codegraph` → no such
   directory.
3. Ask the agent "using codegraph, which classes call SidecarManager.spawn and what does
   SessionConfigFactory.prepare depend on?" → one `mcp__codegraph__codegraph_explore` call
   (approval flow as usual) returning verbatim source + blast radius, then at most a couple
   of Reads. Git panel shows nothing index-related; `git -C <worktree> status --porcelain`
   is empty.
4. Ask it to add a small method to a Java class, then "explore <that method>" → found,
   with no backend log activity in between (the server's watcher did it; its stderr in
   `logs/sidecar/<id>.log` shows the sync).
5. Same as 2 with Codex → same entry, the prompt block present in the sidecar's
   instructions, the tool call goes through the normal approval flow.
6. Break the index (temporarily point the root at a clone whose `dist/` was deleted after
   the Settings save, create a session) → PROVISIONING ends with a `warning` line, the chip
   is `--red` with the message in its title, the session is IDLE and usable with Read/Grep;
   restore `dist/`, park + wake (or crash + resume) → `ensureIndexed` builds it, chip steady.
7. Duplicate a codegraph session → the flag stays ticked. Close one → `~/claude-worktrees/
   <id>` is gone entirely; no stray `codegraph` Node process (`pgrep -f "codegraph.js serve"`).
8. Switch the selector to `Graphify`/`Serena` → a running codegraph session's chip still reads
   `codegraph` (baked at creation); new sessions get the phase-12/13 entries. Switch to
   `none` → the checkbox disappears.
9. Restart the backend with a codegraph session open → after wake the chip reads READY (db
   exists, no re-index); delete `<worktree>/.codegraph` by hand, wake → a rebuild runs.
10. Light theme pass over the chip states and the Settings row.
