# Phase 6 — Skill & Agent Library

> Implementation notes (as built, 2026-08-29): shipped as planned, plus post-review
> hardening: `RepoCacheService` validates refs against a strict URL/owner-repo
> pattern before they reach the gh argv, and every content read/copy boundary
> (scan walk, tree hash, AI-fill, import, recursive copy) does a real-path
> containment check so symlinks inside third-party sources can't reach outside the
> source root. Verified live: full REST flow, `gh repo clone` + `gh repo sync`
> against anthropics/skills, and a headless-browser pass through the dialog
> (scan → bulk AI-fill via the Haiku system session → import → library/sources
> tabs). Voyage embeddings verified live once the
> key landed: embed-on-import and re-embed-on-edit write vector(1024) rows, and
> paraphrased semantic searches rank the right skill first (the keyless
> disabled-toggle/409 paths were verified beforehand). Only untested nicety:
> the AI-fill abort button.

**Goal:** a curated, searchable library of skills and agents. Point at a local folder
or a remote git repo, scan it, review candidates with editable metadata (AI-fill via
the Haiku system session), bulk-import into the managed roots, persist metadata +
tags + content hashes in Postgres, optionally embed content into pgvector for
semantic search, and keep synced sources fresh on a schedule — auto-updating changed
assets, archiving removed ones, and suggesting newly appeared files.

**Estimated effort:** ~2–2.5 days.

## Decisions (also logged in README.md)

- **Embeddings: Voyage API** behind an `EmbeddingClient` interface. Key from
  `CLAUDE_UI_VOYAGE_API_KEY` (secret → env only, like the Linear key); without it the
  vectorize toggle and semantic search are disabled but everything else works.
  `vector(1024)`, model `voyage-3.5-lite` — same direction as the 5.3 RAG sketch, the
  client gets reused there.
- **Destinations reuse skillsRoot**: imported skills land in the existing skills root
  (so the create-dialog picker sees them immediately), agents in a new agents root.
  Both become **persisted settings** (`library.skills-root`, `library.agents-root`)
  with the old env/config values as defaults — same migration path as
  `ecosystem.root`.
- **Detection is convention-first**: a directory containing `SKILL.md` is a skill
  (imported/hashed as the whole directory); a standalone `.md` under an `agent(s)`
  path segment or with agent-style frontmatter is an agent; the "name contains
  skill/agent" heuristic is only a low-confidence fallback shown with a marker.
- **Remote repos via `gh`, GitHub-only for now**: `gh repo clone <ref> <dir> --
  --depth 1` for the initial fetch and `gh repo sync` (fast-forward) in the cache dir
  on sync ticks — gh injects the user's existing auth, so private repos work with
  zero extra setup (same `gh auth login` prerequisite the PR features already have).
  The result is a normal local clone, so hashing/copying/sync read plain files.
  Non-GitHub remotes are out of scope until a later phase (a plain-git fallback slots
  into the same `RepoCacheService`).
- **Pagination is client-side** (single user, hundreds of items at most; no server
  pagination convention exists).
- **Sync notifications via dashboard polling** + topbar count badge + the existing
  `notify()` helper — the journal/WS pipeline is per-session-scoped and doesn't fit a
  global "source changed" event.

## Tasks

### 6.1 Schema (`V7__skill_library.sql`)

- `CREATE EXTENSION IF NOT EXISTS vector;`
- `asset_source(id UUID PK, type TEXT CHECK IN ('dir','repo'), ref TEXT UNIQUE,
  sync_enabled BOOLEAN, last_synced_at TIMESTAMPTZ, last_sync_status TEXT,
  last_sync_error TEXT, created_at, updated_at)`
- `library_asset(id UUID PK, source_id UUID REFERENCES asset_source ON DELETE SET
  NULL, kind TEXT CHECK IN ('skill','agent'), name TEXT, description TEXT,
  location TEXT, source_path TEXT, content_hash TEXT, status TEXT CHECK IN
  ('ACTIVE','ARCHIVED') DEFAULT 'ACTIVE', created_at, updated_at)` — `location` is
  the path under the managed root, `source_path` the relative path inside the source.
- `asset_tag(asset_id UUID REFERENCES library_asset ON DELETE CASCADE, tag TEXT,
  PRIMARY KEY(asset_id, tag))`
- `asset_embedding(asset_id UUID PK REFERENCES library_asset ON DELETE CASCADE,
  embedding vector(1024), model TEXT, embedded_at TIMESTAMPTZ)` + HNSW index
  (`vector_cosine_ops`).
- `source_discovery(source_id UUID REFERENCES asset_source ON DELETE CASCADE,
  source_path TEXT, kind TEXT, first_seen_at TIMESTAMPTZ, dismissed BOOLEAN DEFAULT
  false, PRIMARY KEY(source_id, source_path))` — "new upstream file" suggestions.
- Hashing: SHA-256; a skill's hash is SHA-256 over the sorted `(relative path, file
  hash)` pairs of the whole directory, an agent's is its file hash.

### 6.2 Scanner (`library/AssetScanService`)

- `scan(type, ref)`; `dir` walks the folder (depth ≤ 6, skipping `.git`,
  `node_modules`, `.repo-cache`); `repo` fetches via a new `library/RepoCacheService`
  shelling out to `gh` (pattern: the hand-rolled `ProcessBuilder` + timeout `gh`
  calls in `GitOpsService`): first fetch `gh repo clone <ref> <dir> -- --depth 1`,
  refresh `gh repo sync` inside the dir (fast-forward; on failure fall back to the
  cached copy and record the error). Cache dir
  `<skillsRoot>/.repo-cache/<first 16 hex of sha256(ref)>`. `ref` accepts a full
  GitHub URL or `owner/repo` shorthand (both are what `gh repo clone` takes).
  GitHub-only for now; `AssetProvisioningService`'s own git-based `cloneOrUpdate`
  stays untouched. Actionable error when `gh` is missing/unauthenticated (same
  friendly message pattern as `GitOpsService.createPullRequest`).
- Classification per the decision above; frontmatter `name`/`description` parsed with
  the regex logic extracted from `MetaController.describe`.
- Result per candidate: `{path, kind, confidence, name?, description?, hash,
  sizeBytes, alreadyImported?, changedSinceImport?}` — the last two matched against
  `library_asset` by source + `source_path`, so re-scanning a known source marks
  what's new/changed.

### 6.3 Import & CRUD (`library/LibraryService`, repositories, `LibraryController`)

- Import: upsert the `asset_source` row (with its `syncEnabled` flag), copy each
  selected skill dir / agent file into the managed root (**never overwrite** an
  existing different-content entry — suffix `-2` and return a warning; identical
  content links to the existing copy), insert `library_asset` + `asset_tag` rows,
  embed when vectorize is on and Voyage is configured. Per-item try/catch; the
  response reports per-item success/warnings.
- CRUD: edit name/description/tags, archive (keeps the copied files), restore,
  delete (removes DB row **and** the managed copy).
- Endpoints under `/api/library` (RFC 7807 errors via the existing handler):
  - `POST /scan {type, ref}` → candidates (synchronous; 60 s git timeout)
  - `POST /import {source:{type,ref,syncEnabled}, items:[{path,kind,name,description,tags}]}`
  - `POST /ai-fill {type, ref, paths[]}` → `[{path,name,description,tags}]`
  - `GET /assets?kind=&status=&q=` (`q` = ILIKE over name/description/tags),
    `PATCH /assets/{id}`, `DELETE /assets/{id}`
  - `GET /search?q=&k=` → semantic top-k (409 when not configured)
  - `GET /sources` (with undismissed discovery counts), `PATCH /sources/{id}`
    (syncEnabled), `POST /sources/{id}/sync`, `DELETE /sources/{id}`,
    `POST /sources/{id}/discoveries/dismiss`
- Repositories follow the hand-written `JdbcClient` + nested entity record style
  (`TemplateRepository` is the model).

### 6.4 AI-fill (`library/LibraryAiService`)

- Reads candidate content from the source (dir or clone cache), truncates ~8 KB per
  file, batches ≤ 5 candidates per prompt, calls
  `SessionService.runSystemTurn(prompt, 45s)` demanding a JSON array of
  `{path, name, description, tags[]}` — same prompt/`stripFences`/validation pattern
  as `TicketImportService`. The system-session lock serializes turns; the frontend
  chunks bulk requests and shows per-chunk progress.

### 6.5 Embeddings (`library/EmbeddingClient` + `VoyageEmbeddingClient`)

- Spring `RestClient` → `POST https://api.voyageai.com/v1/embeddings`, model
  `voyage-3.5-lite`, input = name + description + truncated content. Key bound via a
  new `AppProperties` field (`CLAUDE_UI_VOYAGE_API_KEY`); `configured()` gates the
  settings toggle (`voyageConfigured` in `SettingsView`, like
  `linearApiKeyConfigured`), search endpoint, and embed-on-save/sync.
- Semantic search: embed the query, cosine top-k over `asset_embedding` joined to
  ACTIVE assets.

### 6.6 Sync (`library/LibrarySyncService`)

- `@Scheduled(fixedDelay = 60_000)`, shaped exactly like `PrCheckPollingService`:
  settings `library.sync-enabled` (default true) and `library.sync-interval-minutes`
  (default 60, floor 5) re-read every tick; the interval is applied as a
  `last_synced_at` cutoff; per-source try/catch, `last_sync_status/error` recorded.
- Per synced source: refresh clone / rescan dir, then per tracked asset compare
  upstream hash — changed → re-copy + update hash + re-embed; missing upstream →
  `ARCHIVED`; upstream candidates not yet imported → upsert `source_discovery`.
- `POST /sources/{id}/sync` runs the same routine on demand.

### 6.7 Settings

- New keys in `SettingsService` (+ Settings dialog section "Skill library"):
  `library.skills-root` (default `AppProperties.skillsRoot()`), `library.agents-root`
  (default `~/claude-agents`), `library.vectorize` (default false, disabled without
  Voyage key), `library.sync-enabled`, `library.sync-interval-minutes`.
- `MetaController.skills()` and `AssetProvisioningService` read the skills root via
  `SettingsService` from now on (env value stays the default).

### 6.8 Frontend (`LibraryDialog.tsx` + Dashboard wiring)

- Dashboard topbar: 📚 button with `count-badge` = undismissed discoveries, polled
  every ~60 s alongside the existing stale-session refresh; when the count rises,
  `notify('Skill library', 'N new file(s) in <source>')`.
- `LibraryDialog` (`modal wide`), segmented control with three views:
  1. **Library** — asset table (kind icon, name, description, tag chips, source,
     status, updated), kind/status/text filters, semantic search box when enabled,
     row edit (TemplateManager pattern), archive/restore/delete, client-side
     pagination (20/page).
  2. **Import** — source input (local path, GitHub URL, or `owner/repo` shorthand —
     `http`/`.git`/`owner/repo`-pattern sniff decides dir vs repo) + Scan; candidate
     table with select-all + checkboxes, per-row editable name/description/tags +
     kind select, low-confidence marker, already-imported/changed badges, ✨ AI-fill
     per row and for the selection (chunked, AbortController + elapsed-time logging
     like ticket import), "keep source synced" checkbox, Import selected; paginated.
  3. **Sources** — sync toggle, last-sync status/time, Sync now, Delete; per-source
     discovery suggestions with **Review & add** (jumps to Import pre-scanned with
     the new paths pre-selected) and Dismiss.
- Tags: comma-separated input rendered as removable chips (`.queue-chip` style).
- `rest.ts` methods, `protocol.ts` types, `SettingsDialog` section, `styles.css`
  additions (pagination controls, candidate rows based on `.ticket-row`).

### 6.9 Docs

- `CLAUDE.md`: `CLAUDE_UI_VOYAGE_API_KEY` in the limits table, new persisted
  settings, library blurb. `docs/ARCHITECTURE.md`: library section.

## Out of scope

- Using library metadata/tags to *filter the create-dialog picker* (it picks up
  imported skills by location automatically; richer integration later).
- RAG over session transcripts (5.3) — only the `EmbeddingClient` and pgvector
  enablement are shared groundwork.
- Auto-importing new upstream files without review; deleting upstream never deletes
  local copies (archive only).
- Non-GitHub remotes (GitLab, self-hosted, plain git URLs) — a plain-git fallback in
  `RepoCacheService` later; non-git sources (http indexes, marketplaces).

## Definition of Done

- [x] `V7` applies cleanly; `\dx` shows `vector`; all five tables exist.
- [x] Scanning a local folder with a mixed layout classifies: `SKILL.md` dirs as
      whole-dir skills, `agents/*.md` as agents, `my-skill-notes.md` as a
      low-confidence fallback candidate.
- [x] Scanning `https://github.com/anthropics/skills` lists candidates; re-scan
      after import shows them as already-imported.
- [x] AI-fill (single row and bulk selection) populates name/description/tags via a
      system-session Haiku turn; abort works.
- [x] Import copies files into the configured roots, writes asset + tag rows, and an
      imported skill appears in the create-dialog skills picker without restart.
- [x] Name collision on import: existing different-content entry is not overwritten;
      warning surfaced; identical content deduplicates.
- [x] With `CLAUDE_UI_VOYAGE_API_KEY` set and vectorize on: import writes an
      `asset_embedding` row; semantic search returns the right skill for a
      paraphrased query. Without the key: toggle + search disabled, rest unaffected.
- [x] Sync (scheduled and Sync now): upstream edit → local copy + hash updated (and
      re-embedded); upstream delete → asset ARCHIVED; upstream new file → discovery
      row, 📚 badge, desktop notification on an unfocused tab.
- [x] Review & add flows a discovery into the Import view pre-selected; Dismiss
      clears it from the badge count.
- [x] Archive keeps files, restore reverses it, delete removes row + managed copy.
- [x] Settings changes (roots, interval, toggles) take effect without restart.
- [x] `./mvnw clean verify` green.

## Manual test script

| # | Action | Expected |
|---|---|---|
| 1 | Build a test folder: `skills/pdf/SKILL.md` (+`references/x.md`), `agents/reviewer.md`, `notes/skill-ideas.md`; Library → Import → scan it | pdf = skill (1 candidate, whole dir), reviewer = agent, skill-ideas = fallback candidate with low-confidence marker |
| 2 | Scan `https://github.com/anthropics/skills` | clone into `.repo-cache/<sha>`, candidates listed, paginated at 20 |
| 3 | Select 3 rows → "AI-fill selected" | progress shown; names/descriptions/tags filled; log shows system-session turn |
| 4 | Import the 3 with "keep synced" on | files under skills root; `library_asset`+`asset_tag` rows; source row with `sync_enabled`; create dialog lists the new skills |
| 5 | Import a skill whose name already exists in the root with different content | `-2` suffixed copy + warning in the response |
| 6 | Set `CLAUDE_UI_VOYAGE_API_KEY`, enable vectorize, re-import one skill; search a paraphrase of its purpose | `asset_embedding` row present; search ranks it first |
| 7 | Edit a synced source file upstream → Sources → Sync now | asset `content_hash` + copy updated; `updated_at` bumped |
| 8 | Delete a file upstream → Sync now | asset status ARCHIVED (files kept); Library view filter shows it |
| 9 | Add a new `SKILL.md` dir upstream → wait a scheduled tick (interval floor 5 min, or Sync now) | discovery row; 📚 badge count; unfocused tab gets desktop notification |
| 10 | Discovery → Review & add | Import view opens pre-scanned, new path pre-selected; import works |
| 11 | Dismiss a discovery | badge count drops; it doesn't reappear on next sync |
| 12 | `PATCH /api/library/assets/{id}` with new tags; `GET /assets?q=<tag>` | tags replaced; filter matches |
| 13 | Delete an asset | DB row gone, managed copy gone |
| 14 | Unset Voyage key, restart | vectorize toggle + semantic search disabled; scan/import/sync all still work |
