# Deploying agentic-ui on macOS

How to get the dashboard running on a Mac: install the prerequisites, clone, configure
a few paths, build, run. Optionally reach it from other devices on your LAN and set up
the integrations in §8.

## 1. Prerequisites

| What | Version | Install | Why |
|---|---|---|---|
| JDK | 25 | `brew install --cask temurin` (or SDKMAN: `sdk install java 25-tem`) | backend targets Java 25 |
| Maven | 3.9+ | `brew install maven` (or SDKMAN: `sdk install maven`) | build |
| Node.js | ≥ 22 LTS | `brew install node` | **runtime** for the session sidecars — the backend spawns `node` from `PATH` |
| Docker Desktop | any recent | docker.com | Postgres via `docker compose` (alternative: native `postgresql@17` + pgvector, then point the datasource at it) |
| git | ≥ 2.40 | ships with Xcode CLT | worktrees, all git ops |
| Claude Code CLI | latest | `curl -fsSL https://claude.ai/install.sh \| bash` (or `npm i -g @anthropic-ai/claude-code`) | **run `claude` once and log in** — sessions authenticate via `~/.claude` |
| Codex CLI | latest | follow Codex CLI's own install instructions, then `codex login` once | optional — only for `provider: codex` sessions |
| gh CLI | latest | `brew install gh`, then `gh auth login` | optional — PR creation from the Git panel, PR-check polling, review sessions, GitHub imports in the skill library |

Verify:

```bash
java --version        # 25.x
mvn --version         # Java 25 listed
node --version        # v22+
docker compose version
claude --version      # and `claude` opens logged-in
```

## 2. Get the code

```bash
git clone https://github.com/eupakhomov/agentic-ui.git
cd agentic-ui
```

## 3. Configure

Add these to `~/.zshrc` (then open a new terminal):

```bash
# REQUIRED — the repo shown as the default service when creating a session.
# The built-in default points at a path that doesn't exist on your machine.
# Any git checkout works; you can pick a different repo per session in the UI.
export AGENTIC_UI_REPO="$HOME/projects/<some-repo>"

# Optional — defaults shown; the folders are created on demand.
export AGENTIC_UI_WORKTREE_ROOT="$HOME/agentic-worktrees"   # one git worktree per session
export AGENTIC_UI_SKILLS_ROOT="$HOME/agentic-skills"        # managed skill library
export AGENTIC_UI_MEMORY_ROOT="$HOME/agentic-memory"        # long-term memory vault (Markdown)

# Optional — a fixed dashboard token. Unset = a new random token on every start.
export AGENTIC_UI_TOKEN="<pick-a-long-random-string>"
```

`AGENTIC_UI_SKILLS_ROOT` and `AGENTIC_UI_MEMORY_ROOT` only seed the first boot — after
that both are editable in the Settings dialog ("Skill library" / "Memory").

The **ecosystem root** (the parent folder of your repos, used for the service picker and
read-only cross-repo context) is not an env var — set it in Settings → "Sessions" after
first login (§7).

The full list of env vars is in CLAUDE.md "Limits & caps"; none of the others need
changing for a default install.

## 4. Database

```bash
docker compose up -d          # pgvector/pg17; DB/user/password agentic_ui, port 127.0.0.1:5432
```

Data persists in the `agentic-ui_pgdata` Docker volume. To use a non-default DB
password, set `AGENTIC_UI_DB_PASSWORD` in `~/.zshrc` — both compose and the backend
read it.

## 5. Build the sidecars

The session engines are separate Node packages, built once (and again after every
`git pull`). `sidecar-codex/` is only used by `provider: codex` sessions, but it's cheap
to build, so build both:

```bash
(cd sidecar && npm install && npm run build)
(cd sidecar-codex && npm install && npm run build)
```

The backend jar itself is built by the run script in the next step.

## 6. Run

```bash
./restart.sh --full
```

This starts Postgres if needed, builds the jar (frontend included — the first build
takes a few minutes while it downloads Node and runs `npm install`), starts the backend
in the background, waits until it's healthy, and prints the URL and token:

```
UI:    http://localhost:8080
Token: <token>
```

Day to day:

| | |
|---|---|
| `./start.sh` | restart without rebuilding |
| `./restart.sh` | rebuild backend only (fast), restart |
| `./restart.sh --full` | rebuild backend + frontend, restart — use after `git pull` |
| `kill "$(cat /tmp/agentic-ui.pid)"` | stop (graceful; shuts sessions down) |
| `cat /tmp/agentic-ui.token` | show the current token |

Logs: `/tmp/agentic-ui.log` (raw stdout), `logs/agentic-ui.log` (application),
`logs/error.log`, `logs/sidecar/<session-id>.log` (one per session).

If `./mvnw` complains "permission denied", ignore it — the scripts use the system `mvn`
on macOS; `./mvnw` sometimes loses its executable bit in git checkouts.

**Token**: with `AGENTIC_UI_TOKEN` exported (§3) the same token is reused on every
restart, so the browser stays logged in. Unset, a fresh one is generated each start.

**LAN access** (phone, tablet, another laptop): the scripts already bind all interfaces,
so open `http://<mac-name>.local:8080` from the other device and enter the token. macOS
will ask once whether `java` may accept incoming connections — allow it. For anything
beyond a trusted home network put TLS in front (e.g. Tailscale, or a Caddy reverse proxy
in front of a loopback-only backend).

**Local-only, no token**: run the jar directly instead of via the scripts —
`java -jar target/agentic.ui-*.jar` binds `127.0.0.1` and allows tokenless login
there (and only there).

### Start at login (optional)

Create a `launchd` agent (`~/Library/LaunchAgents/de.pamir.agentic-ui.plist`) whose
program is `<repo>/start.sh`, with `RunAtLoad` and your env vars from §3 in
`EnvironmentVariables` — or simply add `start.sh` to Login Items. Set Docker Desktop to
start at login too, so Postgres is up first (the backend fails fast without it and
starts cleanly once the DB is there).

## 7. First use checklist

1. Open the URL, enter the token (remembered by the browser afterwards).
2. Click the bell in the top bar to enable desktop notifications (finished / needs
   input / crashed).
3. Open **Settings** (gear icon, or `,`). None of these need a restart:
   - **Sessions** → *Ecosystem root*: the parent folder of your repos. Enables the
     service picker in the create dialog and read-only cross-repo context. Leave
     *Monorepo detection* off unless a folder underneath really is a monorepo.
   - **Linear integration** → ticket import (§8).
   - **PR checks** → background CI polling for open PRs (on by default; needs `gh`).
   - **Skill library** → skills/agents roots, optional semantic search (§8a).
   - **Memory** → long-term-memory vault, reflection defaults, optional semantic
     search (§8a).
   - **MCP servers** → code-intelligence tools (§8b–§8d).
4. **+ New Session** → pick a service (from the ecosystem root), branch, model,
   permissions — go. `q` opens the quick-session shortcut (service + ticket only,
   everything else copied from your last session) once Linear import is set up.
5. Each session widget has a git panel (`g`): status / diff / commit / push / PR. The
   PR button needs `gh auth login`.

## 8. Optional: Linear ticket import

Lets the "New Session" dialog fetch a Linear ticket and prefill the branch name and
initial prompt. Pick one auth mode:

**Personal API key** — simplest; works unless your Linear account is SSO-only:

```bash
export AGENTIC_UI_LINEAR_API_KEY="lin_api_..."   # Linear → Settings → Security & Access
```

**SSO-gated Linear account** (e.g. Google identity) — the API key path won't work, so
authorize once interactively:

1. On the Mac running the backend, in a real terminal:
   `claude mcp add --transport http linear https://mcp.linear.app/mcp`
   (the `--scope` flag doesn't matter — only the OAuth consent it records is used).
2. Complete the browser OAuth flow through your org's SSO login.
3. In the dashboard: **Settings → Linear integration**, turn on "use the ambient
   `claude` CLI's cached OAuth credential". Leave `AGENTIC_UI_LINEAR_API_KEY` unset —
   an explicit key always wins over OAuth.
4. Try an import from the create-session dialog. No Linear token is stored in
   agentic-ui; it reuses the `claude` CLI's cached credential.

**Branch-naming guidance** (either mode): the same Settings panel has a free-text field
that steers generated branch names, e.g. "keep the ticket number uppercase" or "format
as feat(TICKET)-description / fix(TICKET)-description".

## 8a. Optional: semantic search (Voyage embeddings)

One key adds semantic (embedding-based) search to the skill library, long-term memory,
and service discovery. Without it all three still work with Postgres full-text search:

```bash
export AGENTIC_UI_VOYAGE_API_KEY="pa-..."   # Voyage AI dashboard → API keys
```

- **Skill library**: also turn on "vectorize" in Settings → "Skill library" (off by
  default).
- **Memory** and **service discovery**: no toggle — semantic search is used
  automatically once the key is set.

## 8b. Optional: Serena (symbolic code tools)

Gives sessions `find_symbol`/references/etc. via an MCP server, opt-in per session.
Prerequisites:

```bash
curl -LsSf https://astral.sh/uv/install.sh | sh          # uv
git clone https://github.com/oraios/serena.git ~/serena   # or wherever you keep checkouts
```

Then in Settings → "MCP servers": set **Serena root** to the checkout path (saving
validates it and checks that `uv --version` runs) and, only if `uv` isn't on the
backend's `PATH`, set **uv path** to its full path. Pick `Serena` in the **Code
intelligence** selector. The create dialog now shows a "Code intelligence" checkbox —
off by default, since each enabled session runs its own Python process plus a language
server.

## 8c. Optional: graphify (knowledge-graph code tools)

A per-session knowledge graph of the session's code, queried via MCP tools
(`query_graph`, `get_neighbors`, `shortest_path`, …). The **Code intelligence** selector
allows one tool per install — Serena, graphify or CodeGraph, never more than one.
Prerequisites: `uv` (as in §8b) plus a checkout at the reviewed commit:

```bash
git clone https://github.com/Graphify-Labs/graphify.git ~/graphify
git -C ~/graphify checkout c7ec108        # v0.9.62
```

Then in Settings → "MCP servers": set **Graphify root** to the checkout path and pick
`Graphify` in the selector. The first save also installs graphify's Python environment
(~110 packages on a cold cache; the field pulses meanwhile). If that times out, run the
command the error message shows once by hand, then save again.

Do **not** run graphify's own `graphify install`, `hook install` or the `/graphify`
skill on this Mac — they rewrite `~/.claude/settings.json` and git hooks. agentic-ui
only uses the checkout's CLI and MCP server; graphs live under
`<worktree-root>/.graphify/<session-id>/`, never inside a worktree.

## 8d. Optional: CodeGraph (code graph, self-refreshing)

A per-session code graph (symbols, calls, imports, inheritance) with one MCP tool,
`codegraph_explore`, that answers a question with line-numbered source, call paths and
a blast-radius summary. It keeps its own index current (file watcher), so nothing to
refresh. Prerequisites: Node ≥ 22.5 (already installed, §1 — no `uv`) plus a checkout
at the reviewed commit, built once:

```bash
git clone https://github.com/colbymchenry/codegraph.git ~/codegraph
git -C ~/codegraph checkout 1f0cbbd      # v1.6.0
cd ~/codegraph && npm ci --ignore-scripts && npx tsc && npm run copy-assets
```

Then in Settings → "MCP servers": set **CodeGraph root** to the checkout path (saving
runs a version probe; nothing is installed) and pick `CodeGraph` in the selector. A
session created with the checkbox on gets its index built while it's provisioning
(a few seconds for a typical repo; the session still starts, with a warning, if that
fails).

Do **not** run codegraph's own `codegraph install`, `upgrade`, `codegraph ui` or its
git hooks on this Mac — they rewrite `~/.claude/settings.json`, `~/.claude.json` and
`~/.claude/CLAUDE.md`. agentic-ui only runs `init`, `serve --mcp` and `version` from the
checkout, with telemetry and update checks off. The index (`.codegraph/`) lives inside
the session's worktree, git-excluded, and disappears with it.

## 9. Updating

```bash
git pull
(cd sidecar && npm install && npm run build)
(cd sidecar-codex && npm install && npm run build)
./restart.sh --full        # stops the running backend, rebuilds, starts; DB migrates on boot
```

## 10. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Startup: "refusing to bind … without an auth token" | The backend was started on a non-loopback address without `AGENTIC_UI_TOKEN`. Use `./start.sh`/`./restart.sh` (they always pass a token) or export `AGENTIC_UI_TOKEN` (§3) |
| Session stuck in STARTING, then CRASHED | `node` not on the backend's `PATH`, sidecar not built (§5), or `claude` never logged in — check `logs/sidecar/<id>.log` |
| Same, only for `provider: codex` sessions | `sidecar-codex` not built (§5), or `codex login` never done — check `logs/sidecar/<id>.log` |
| Create fails 409 "already used by worktree" | That branch is checked out by another (possibly orphaned) session worktree — see `GET /api/maintenance/orphans`, clean via `POST …/clean` |
| Health DOWN / boot fails on datasource | Postgres not up yet — `docker compose up -d`, wait for healthy |
| PR button → 409 | `gh` missing or not authenticated, or repo has no GitHub remote — the message says which |
| Widgets empty after update | Hard-refresh the browser (cached JS) |
| Ticket import: "needs auth" / "cannot run the OAuth flow" | OAuth mode only: the `claude mcp add` step in §8 wasn't done on this Mac — check `logs/sidecar/<system-session-id>.log` |
