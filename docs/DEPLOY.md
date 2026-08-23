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

All settings are env vars (see CLAUDE.md "Limits & caps" for the full table).
The four that must change from the WSL defaults are paths:

```bash
# ~/.zshrc (or a run script)
export CLAUDE_UI_ECOSYSTEM_ROOT="$HOME/projects"       # parent folder of your services
export CLAUDE_UI_REPO="$HOME/projects/<default-repo>"  # default service (per-session selectable anyway)
export CLAUDE_UI_WORKTREE_ROOT="$HOME/claude-worktrees"
export CLAUDE_UI_SKILLS_ROOT="$HOME/claude-skills"     # optional; create + drop SKILL.md dirs in
```

Alternatively keep a gitignored `application-local.yaml` next to the jar and run
with `--spring.config.additional-location=file:./application-local.yaml`.

## 4. Database

```bash
docker compose up -d          # pgvector/pg17, DB/user/pass claude_ui, port 127.0.0.1:5432
```

Data persists in the `claude-ui_pgdata` Docker volume. Non-default DB password:
set `CLAUDE_UI_DB_PASSWORD` for both compose and the backend.

## 5. Build

```bash
./mvnw package -DskipTests
```

First build is slow: it downloads a Node distro into `target/` and npm-installs the
frontend (native APFS is far faster than the WSL/DrvFS dev box — expect ~2–3 min,
not 10+). Then build the sidecar once:

```bash
cd sidecar && npm install && npm run build && cd ..
```

Rebuilds that don't touch the frontend: `./mvnw package -DskipTests -Dskip.installnodenpm -Dskip.npm`.

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
3. **+ New Session** → pick a service (auto-discovered from `CLAUDE_UI_ECOSYSTEM_ROOT`),
   branch, model, permissions — go.
4. The **⎇** button per widget: status/diff/commit/push/PR.
   PR button needs `gh auth login` done once.

## 8. Updating

```bash
kill "$(cat /tmp/claude-ui.pid)"       # a running JVM blocks jar repackaging
git pull
(cd sidecar && npm install && npm run build)
./mvnw package -DskipTests
# start again (section 6); Flyway migrates the DB automatically on boot
```

## 9. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Startup: "refusing to bind … without an auth token" | You bound non-loopback without `CLAUDE_UI_TOKEN` — set it (this is the security model, not a bug) |
| Session stuck in STARTING, then CRASHED | `node` not on the backend's PATH, or `claude` never logged in — check `logs/sidecar/<id>.log` |
| Create fails 409 "already used by worktree" | That branch is checked out by another (possibly orphaned) worktree — see `GET /api/maintenance/orphans`, clean via `POST …/clean` |
| Health DOWN / boot fails on datasource | Postgres not up yet — `docker compose up -d`, wait for healthy |
| PR button → 409 | `gh` missing or not authenticated, or repo has no GitHub remote — message says which |
| Widgets empty after update | Hard-refresh the browser (cached JS) |
