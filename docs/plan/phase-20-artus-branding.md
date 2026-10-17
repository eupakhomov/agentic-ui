# Phase 20 — Artus: product rename and bear logo

Status: **in progress (2026-10-17)** — Steps 1–4 implemented; Step 5 is the owner's manual migration (DEPLOY §11). Mostly mechanical rename plus one design asset. No schema change;
one manual data migration for the Postgres container (Step 5).

## Background

The system has been called `agentic-ui` (before 2026-09: `claude-ui`). It gets a product name, **Artus**,
and a logo: a geometric (low-poly), front-facing, symmetric bear head. Unlike the 2026-09 rename, there is no
compatibility layer: the only operator is the owner, who re-points their own setup (`~/.bashrc`, Slack app,
Docker volume) once.

## Confirmed decisions (2026-10-17)

1. **Rename everything user- and operator-facing**: UI strings, docs, env vars (`AGENTIC_UI_*` → `ARTUS_*`),
   YAML prefix (`agentic-ui.*` → `artus.*`), DB/user/password default (`agentic_ui` → `artus`), localStorage keys
   (`agentic-ui.*` → `artus.*`, migrated by the existing `storageMigration.ts`), WS subprotocol
   (`agentic-ui.v1` → `artus.v1`), MCP server name (`mcp__agentic-ui__*` → `mcp__artus__*`), `/tmp/artus.*`,
   `logs/artus.log`, Slack app/bot name and Keychain item names, launchd label. **No `AGENTIC_UI_*`/`CLAUDE_UI_*`
   fallbacks**: the old fallback chain in `application.yaml` is removed, not extended.
2. **Keep the Java package `de.pamir.agentic.ui`.** Package renames are pure churn. (Amended during implementation: the Maven
   `artifactId` was changed to `artus` by the owner, so the jar is `target/artus-0.0.1-SNAPSHOT.jar`; `start.sh`/CLAUDE.md updated.)
3. **Rename the repository** (GitHub `eupakhomov/agentic-ui` → `eupakhomov/artus`; local folder
   `/mnt/d/projects/agentic-ui` → `/mnt/d/projects/artus`). GitHub redirects old clone URLs; the rename itself is
   done by the owner (outward-facing), the in-repo paths/badges/URLs are updated in this phase.
4. **Logo**: original SVG, front-facing symmetric polygonal bear head in the style of the two references (not a
   copy; both are licensed/for-sale artwork). Tones come from the existing theme palette so it works dark and light.
5. **Display name "Artus" with tagline** "Multi-session manager for Claude Code and Codex" (README header, login
   screen, `<meta description>`, Slack app description). Top bar shows logo + "Artus" only.

## Shape

### Asset set (`frontend/public/`, copied to `static/` by the existing Vite build)
- `logo.svg` — full mark, ~12 facets, 3–4 tones (accent blue family + a neutral), symmetric about the vertical axis.
- `favicon.svg` — simplified mark (fewer facets, thicker edges) legible at 16 px; `<link rel="icon" type="image/svg+xml">`.
- `favicon.ico` (16/32/48), `apple-touch-icon.png` (180), `icon-192.png`, `icon-512.png`, `site.webmanifest`.
- Raster files are generated from the SVG by a script in `scripts/` (kept so the set is reproducible), not hand-exported.
- `docs/images/artus-logo.svg` for the README header.
- Design check: render variants, review at 16/32/180 px in both themes in the browser before committing to one.

### Rename map
| Area | Change |
|---|---|
| Frontend display | `Dashboard.tsx` h1 (logo + "Artus"), `App.tsx` login h2 + tagline, `index.html` title/description/icons, `notify.ts` `BASE_TITLE` |
| Frontend identifiers | all `agentic-ui.*` storage keys, `[agentic-ui]` console prefixes, WS subprotocol, `package.json` names (`artus-frontend`, `artus-sidecar`, `artus-sidecar-codex`) |
| `storageMigration.ts` | add `agentic-ui.` → `artus.` copy-then-delete alongside the existing `claude-ui.` rule (keeps the stored auth token and layouts) |
| Backend | `@ConfigurationProperties(prefix = "artus")`, `@ConditionalOnProperty` prefixes, `SUBPROTOCOL`, Slack notice label, log messages naming env vars, `logback-spring.xml` file names and `ARTUS_LOG_DIR`, `application.yaml` (drop `CLAUDE_UI_*` fallbacks), default repo path, `~/artus-worktrees|skills|memory` defaults |
| Sidecars | env var names, hook denial text, MCP server registration name, `package.json` |
| Ops | `start.sh`, `install.sh`, `docker-compose.yaml`, `/tmp/artus.{pid,token,log}` |
| Docs | README (header image + tagline), CLAUDE.md, ARCHITECTURE, DEPLOY, SLACK, PROTOCOL prose and env-var tables; plan docs keep their historical text, only this phase's rows and a one-line "formerly agentic-ui" note in `plan/README.md` |

### Findings during implementation (corrections to the plan above)
- **Checkpoint refs** (`refs/agentic-ui/...`) exist only as a design note in ARCHITECTURE.md; no code writes them. Nothing to migrate.
- **MCP tool names**: sessions' in-process server is keyed `memory` (`mcp__memory__*`), not `agentic-ui`; only the Spring
  server name `agentic-ui-memory` → `artus-memory` changes. `toolLabel` is prefix-agnostic; a test pins an old-prefix label anyway.
- **Default data dirs keep their old names** (`~/agentic-worktrees|skills|memory`): existing sessions' worktrees live there and
  skills/memory are persisted settings. Only the env var names changed.
- **`.agentic-ui.pid` → `.artus.pid`** (per-worktree pid file); old worktrees need the exclude line added (DEPLOY §11).
- **docker-compose** pins `name: artus`, so the volume is `artus_pgdata` regardless of folder name (migration in DEPLOY §11).

## Steps

1. **Logo and icon set** — draw `logo.svg`/`favicon.svg`, generator script, wire into `index.html`, top bar, login
   screen, README. Verify in browser at 16/32/180 px, dark and light.
2. **Frontend rename** — display strings, storage keys + migration rule, subprotocol, console prefixes, package names,
   test update (`permissions.test.ts`).
3. **Backend + sidecars rename** — config prefix, env vars, subprotocol, logback, MCP name, messages; update tests.
4. **Ops scripts, compose, docs** — scripts, compose, all six docs, plan README rows.
5. **Data migration (manual, owner)** — documented in DEPLOY: (a) stop the backend, (b) rename the Postgres DB and
   role (`ALTER DATABASE agentic_ui RENAME TO artus; ALTER ROLE agentic_ui RENAME TO artus; ALTER ROLE artus PASSWORD …`),
   (c) the compose project name follows the folder, so the volume `agentic-ui_pgdata` becomes `artus_pgdata` after the
   folder rename: either pin `name: agentic-ui_pgdata` on the volume or copy it over, (d) rename env exports in `~/.bashrc`,
   (e) rename the Slack app/bot display name and Keychain items, (f) rename the GitHub repo and local folder, update the
   remote, (g) restart.
6. **Sweep** — grep for `agentic-ui`, `agentic_ui`, `AGENTIC_UI` outside the allowed leftovers (Java package, Maven
   coordinates, historical plan text, migration notes).

## Out of scope
- Java package / Maven artifact rename.
- Renaming on-disk user data folders (skills, memory, worktrees).
- Animated logo, dark/light logo variants beyond the shared palette, marketing site.
- A compatibility shim for old env vars or the old WS subprotocol.

## Definition of Done
- [ ] Browser tab shows the bear favicon and "Artus"; the `(n)` pending prefix still works.
- [ ] Top bar and login screen show logo, name and tagline; legible in dark and light.
- [ ] Favicon readable at 16 px; apple-touch/manifest icons present.
- [ ] Stored token/layout survive the storage-key migration in a browser that had the old keys.
- [ ] Backend starts with only `ARTUS_*` env vars; old ones are ignored; WS connects over `artus.v1`.
- [ ] New sessions expose `mcp__artus__*` tools; a pre-existing session still renders its old tool labels.
- [ ] Slack notices and the sidecar hook denial say Artus.
- [ ] Backend, frontend and sidecar test suites pass; grep sweep shows only the allowed leftovers.
- [ ] DEPLOY has the migration checklist; README has the logo and tagline.

## Manual test script
1. `grep -rIn "agentic-ui\|agentic_ui\|AGENTIC_UI" --exclude-dir={node_modules,target,.git,logs,dist} .` → only allowed leftovers.
2. Set `ARTUS_TOKEN`, run `./start.sh`, open the dashboard: logo, "Artus", favicon, tab title.
3. Open it in a browser profile with old `agentic-ui.*` keys: token and layout carried over, old keys gone.
4. Start a session, trigger a permission prompt for the report tool: label reads `report_result`.
5. Resize the tab strip / zoom to 16 px favicon size; toggle the theme.
6. With Slack enabled: start a session; the parent message and notices say Artus.

## Implementation log
### 2026-10-17 — Steps 1–4
- Logo: `frontend/public/{logo,favicon}.svg` (hand-built symmetric facets; `docs/images/artus-logo.svg` is a copy), raster set via
  `node scripts/gen-icons.mjs` (resvg, frontend devDependency). Checked in the browser on the login gate (light theme).
- Frontend, backend, sidecars, scripts, compose and the six docs renamed; `grep` sweep for the old names is clean outside
  DEPLOY §11, the storage-migration code and this folder's history.
- Tests: frontend 80/80, sidecar typechecks, protocol-sync ok. Backend 640 run, 0 failures, 35 errors — all in the 7 DB-backed
  test classes (`ApplicationTests`, `*RepositoryDbTest`), `password authentication failed for user "artus"`: the dev Postgres
  still has the old `agentic_ui` role until Step 5 is done. Re-run after the DB migration.
- Not yet done: Step 5 (owner), dark-theme/topbar check against a running backend, Slack icon upload.
- Follow-up check (same day): Step 5's DB part is done (`artus` DB/role in `artus_pgdata`); backend suite re-run 640/640 green
  including the DB-backed classes. Sweep caught a missed `claudeui.sessions.*` Micrometer gauge name → `artus.sessions.*`.
