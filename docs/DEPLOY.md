# Deploying claude-ui on another machine (macOS)

How to get the dashboard running on a Mac laptop — the primary deployment target.
Everything is platform-neutral by design; unlike the WSL dev box there are no
localhost-relay tricks: loopback binding just works, and LAN access is a plain
`0.0.0.0` bind plus the token.

## 1. Prerequisites

| What | Version | Install (macOS) | Why |
|---|---|---|---|
| JDK | 25 | `brew install --cask temurin` (or SDKMAN: `sdk install java 25-tem`) | backend targets Java 25 |
| Node.js | ≥ 22 LTS | `brew install node` | **runtime** for the sidecar (`node sidecar/dist/index.js`); the Maven build downloads its own copy for the frontend, but the running backend spawns `node` from PATH |
| Docker Desktop | any recent | docker.com | Postgres via `docker compose` (alternative: native `postgresql@17` + pgvector, then point the datasource at it) |
| git | ≥ 2.40 | ships with Xcode CLT | worktrees, all git ops |
| Claude Code CLI | latest | `curl -fsSL https://claude.ai/install.sh \| bash` (or `npm i -g @anthropic-ai/claude-code`) | **log in once with `claude`** — sidecars authenticate via `~/.claude`, and auto-titling shells out to `claude -p` |
| gh CLI | latest | `brew install gh`, then `gh auth login` | optional — only for the widget "Open PR" button |

Verify before building:

```bash
java --version        # 25.x
node --version        # v22+
docker compose version
claude --version      # and `claude` opens logged-in (run once interactively)
```

## 2. Get the code

The repo currently lives only on the dev machine. Either:

- **Recommended**: create a private GitHub repo once, push from the dev box
  (`git remote add origin git@github.com:<you>/claude-ui.git && git push -u origin main`),
  then on the Mac: `git clone git@github.com:<you>/claude-ui.git && cd claude-ui`.
  (Also lets claude-ui's own PR button work on itself.)
- Or copy the directory (rsync/AirDrop) — make sure `.git` comes along; skip
  `target/`, `*/node_modules/`, `*/dist/`, `logs/`.

## 3. Configure for the Mac

Most settings are env vars (see CLAUDE.md "Limits & caps" for the full table).
The three that must change from the WSL defaults are paths:

```bash
# ~/.zshrc (or a run script)
export CLAUDE_UI_REPO="$HOME/projects/<default-repo>"  # default service (per-session selectable anyway)
export CLAUDE_UI_WORKTREE_ROOT="$HOME/claude-worktrees"
export CLAUDE_UI_SKILLS_ROOT="$HOME/claude-skills"     # optional; create + drop SKILL.md dirs in
```

Alternatively keep a gitignored `application-local.yaml` next to the jar and run
with `--spring.config.additional-location=file:./application-local.yaml`.

The ecosystem root (parent folder of your services, used for read-only session
context + the service picker) is not an env var — set it once in the Settings
dialog → "Sessions" after first login; it's persisted in the database.

## 4. Database

```bash
docker compose up -d          # pgvector/pg17, DB/user/pass claude_ui, port 127.0.0.1:5432
```

Data persists in the `claude-ui_pgdata` Docker volume. Non-default DB password:
set `CLAUDE_UI_DB_PASSWORD` for both compose and the backend.

## 5. Build

`mvnw` sometimes loses its executable bit across git checkouts (`git ls-files -s mvnw`
shows `100644`, not `100755`) — if `./mvnw` fails with "permission denied", either
`chmod +x mvnw` or, if JDK 25 + Maven are already on `PATH` (e.g. via sdkman/brew), just
use the system `mvn`:

```bash
mvn package -DskipTests        # or: ./mvnw package -DskipTests
```

First build is slow: it downloads a Node distro into `target/` and npm-installs the
frontend (native APFS is far faster than the WSL/DrvFS dev box — expect ~2–3 min,
not 10+). Then build the sidecar once:

```bash
cd sidecar && npm install && npm run build && cd ..
```

Rebuilds that don't touch the frontend: `mvn package -DskipTests -Dskip.installnodenpm -Dskip.npm`.

## 6. Run

```bash
TOKEN=$(head -c 24 /dev/urandom | base64 | tr -dc 'a-zA-Z0-9' | head -c 20)
echo "$TOKEN" > /tmp/claude-ui.token
CLAUDE_UI_TOKEN="$TOKEN" nohup java -jar target/claude.ui-0.0.1-SNAPSHOT.jar \
  --server.address=0.0.0.0 > /tmp/claude-ui.out 2>&1 &
echo $! > /tmp/claude-ui.pid
echo "http://localhost:8080  token: $TOKEN"
```

- **Local-only use**: drop `--server.address=0.0.0.0` and `CLAUDE_UI_TOKEN` — the
  startup guard allows tokenless operation on `127.0.0.1` only.
- **LAN use (phone/tablet/second laptop)**: keep the `0.0.0.0` bind + token; open
  `http://<mac-hostname>.local:8080` from the other device and enter the token.
  macOS will ask once to allow `java` to accept incoming connections — allow it.
  For anything beyond a trusted home LAN, put real TLS in front (e.g. Tailscale,
  or a Caddy reverse proxy with `server.address=127.0.0.1`).
- Stop: `kill "$(cat /tmp/claude-ui.pid)"` (graceful; shuts sidecars down).
  Structured logs: `logs/claude-ui.log`, `logs/error.log`, `logs/sidecar/<id>.log`.

### Start at login (optional)

Wrap the run block in a script and add it as a `launchd` agent
(`~/Library/LaunchAgents/de.pamir.claude-ui.plist` with `RunAtLoad` + the env vars in
`EnvironmentVariables`), or simply add the script to Login Items. Make sure Docker
Desktop is also set to start at login so Postgres is up first (the backend fails fast
without it — just restarts cleanly once the DB is there).

## 7. First use checklist

1. Open the URL, enter the token (stored in the browser afterwards).
2. Click **🔔** to enable desktop notifications (finished / needs input / crashed).
3. **+ New Session** → pick a service (auto-discovered from the ecosystem root set in
   Settings), branch, model, permissions — go.
4. The **⎇** button per widget: status/diff/commit/push/PR.
   PR button needs `gh auth login` done once.

## 8. Optional: Linear ticket import

Lets the "New Session" dialog fetch a Linear ticket and prefill the branch name +
initial prompt (via a cheap Haiku call on a hidden system session — see CLAUDE.md /
`docs/plan/phase-5-extensions.md` 5.15). Pick one of two auth modes:

**Personal API key** (simplest — works unless your Linear account is SSO-only):

```bash
export CLAUDE_UI_LINEAR_API_KEY="lin_api_..."   # Linear → Settings → Security & Access
```

**SSO-gated Linear account (e.g. Google identity)** — the API key path won't work if
your org requires SSO login, so authorize once interactively instead:

1. On the machine running this backend, run interactively (a real terminal, not
   through the app): `claude mcp add --transport http linear https://mcp.linear.app/mcp`
2. Complete the browser OAuth flow through your org's SSO login screen.
3. In the dashboard: **⚙️ Settings → Linear integration**, toggle "use the ambient
   `claude` CLI's cached OAuth credential" on (leave `CLAUDE_UI_LINEAR_API_KEY` unset —
   an explicit key always takes priority over OAuth if both are set). This is a
   persisted setting (`app_setting` table, `GET`/`PATCH /api/settings`) — no restart
   needed, it takes effect on the next ticket import.
4. Try an import from the create-session dialog — the backend reuses the `claude`
   CLI's own cached OAuth credential for `mcp.linear.app` (same `~/.claude` identity
   sidecars already authenticate with), no token stored in claude-ui itself.

**The `--scope` flag in step 1 doesn't matter and can be left at its default.**
claude-ui never inherits your `claude mcp add`/`~/.claude` MCP server *declarations*
at any scope — every sidecar process (including the system session) is spawned with
`settingSources: ['project']` (`sidecar/src/session.ts`), which deliberately excludes
user- and local-scope settings/MCP config. Step 1 exists **only** to get the
interactive OAuth consent recorded once; claude-ui builds and passes its own
`--mcp-config` for the Linear server independently once the OAuth toggle is enabled
in Settings, and that's what actually attaches Linear's tools to the system session — the OAuth
*token cache* for `mcp.linear.app` is what's being reused, not the server declaration.

If step 4 still reports "needs auth", the CLI's OAuth cache is scoped more narrowly
than assumed (e.g. per-project rather than per-user) — the fallback is a first-party
OAuth flow built into claude-ui itself (not yet built; see `docs/plan/phase-5-extensions.md` 5.15).

**Branch-naming guidance** (optional, either auth mode): the same Settings panel has a
free-text field appended to the Haiku prompt used to generate a ticket's `branchName`/
`prompt`, e.g. "keep the ticket number uppercase" or "format as
feat(TICKET)-description / fix(TICKET)-description".

## 9. Updating

```bash
kill "$(cat /tmp/claude-ui.pid)"       # a running JVM blocks jar repackaging
git pull
(cd sidecar && npm install && npm run build)
mvn package -DskipTests                # or: ./mvnw package -DskipTests
# start again (section 6); Flyway migrates the DB automatically on boot
```

## 10. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Startup: "refusing to bind … without an auth token" | You bound non-loopback without `CLAUDE_UI_TOKEN` — set it (this is the security model, not a bug) |
| Session stuck in STARTING, then CRASHED | `node` not on the backend's PATH, or `claude` never logged in — check `logs/sidecar/<id>.log` |
| Create fails 409 "already used by worktree" | That branch is checked out by another (possibly orphaned) worktree — see `GET /api/maintenance/orphans`, clean via `POST …/clean` |
| Health DOWN / boot fails on datasource | Postgres not up yet — `docker compose up -d`, wait for healthy |
| PR button → 409 | `gh` missing or not authenticated, or repo has no GitHub remote — message says which |
| Widgets empty after update | Hard-refresh the browser (cached JS) |
| Ticket import: "needs auth" / "cannot run the OAuth flow" | `CLAUDE_UI_LINEAR_OAUTH` mode only: the interactive `claude mcp add` setup (section 8) wasn't done on this host, or its cached credential isn't visible to headless sessions — check `logs/sidecar/<system-session-id>.log` |
