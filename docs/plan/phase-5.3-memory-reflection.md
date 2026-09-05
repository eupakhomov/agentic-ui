# Phase 5.3 — Layered memory & session reflection

Long-term memory for the whole dashboard: sessions can opt into a **reflection** pass
that retrospects the finished conversation and distills it into a layered, durable
memory store; every session (both providers) gets read access to that store through
hierarchical memory tools plus an automatic recent-history window. This is the item
the pgvector image was chosen for (decision log 2026-08-23) and the consumer the
`EmbeddingClient` abstraction was built for (decision log 2026-08-29).

## Why this doc exists

`phase-5-extensions.md` §5.3 and `ARCHITECTURE.md` §4 sketched a single
`memory_chunk` table fed per-turn and injected top-k at session create. Design
discussion (2026-09-05) replaced that sketch with a layered model — episodic vs
semantic memory, ecosystem vs service scope, files as the semantic source of truth,
hybrid retrieval, agent-driven paging instead of blind injection — enough divergence
that this earns its own doc, per the 5.13/Phase-6 pattern.

## What changed vs. the original sketch

- **Per-turn ingestion → end-of-session reflection.** Summarizing every turn is the
  wrong granularity: most turns carry no durable learning, and per-turn chunks pile
  up noise the retrieval side then has to fight. One structured retrospective at
  session close (plus a manual trigger) extracts *what happened* once and *what was
  learned* explicitly, and can merge against existing memory instead of appending
  blindly.
- **One `memory_chunk` table → two layers with different lifecycles.**
  *Episodic* ("what the agent did") is append-only, DB-native, retrieved
  automatically as a recent window. *Semantic* ("what the agent learned" — rules,
  decisions, facts, preferences) lives as human-editable Markdown files with a DB
  index, retrieved explicitly via tools. Never mixed.
- **Static top-k injection at create → agent-driven hierarchical paging.** Injecting
  retrieved chunks into every session causes context distraction and injects stale or
  irrelevant content the agent never asked for. Instead the agent gets composable
  tools (search → summary → paged read) via an injected MCP server and pulls memory
  on demand. The only automatic injection is the small, bounded episodic window.
- **Embedding provider decision → resolved.** Phase 6 already settled it: Voyage API
  (`voyage-3.5-lite`, `vector(1024)`) behind `EmbeddingClient`, graceful degradation
  without the key. Memory reuses that client as-is.
- **Dense-only search → hybrid.** Pure vector search is a known failure mode for
  coding content — embeddings blur exact tokens like `ERR_AUTH_401` or
  `usr_ctx_id`. Retrieval runs dense (pgvector cosine) and sparse (Postgres FTS +
  trigram) concurrently and fuses with Reciprocal Rank Fusion.
- **`V3__memory.sql` → `V9__memory.sql`** (V3–V8 are long taken).

## Decisions (2026-09-05)

1. **Reflection trigger: session close + a manual "Reflect now" action.** Close is
   the natural retrospective point; the manual action covers long-lived sessions.
   Parking stays transparent and never reflects (a session that parks/wakes
   repeatedly would reflect repeatedly and burn turns). A reflection is skipped when
   no completed turns exist since the last one (`reflected_seq` watermark).
2. **Executor: one structured system-session turn** (`runSystemTurn` — the ticket
   import / AI-fill / git-assist pattern): transcript digest + semantic-memory index
   in, JSON ops out, ~~backend applies them~~ **backend holds them for approval by
   default — see decision 14**, which supersedes the "applied immediately" half of
   this decision (the executor/prompt/JSON-shape half is unchanged). Deterministic,
   cheap, testable. Not an agentic reflection session with write tools — slower,
   costlier, writes harder to validate.
3. **Consumption: memory MCP tools + automatic episodic window.** No static top-k
   semantic injection (can be revisited later as an opt-in).
4. **Sparse search: Postgres FTS + `pg_trgm`.** Both ship in the `pgvector/pg17`
   image already in use. Not true BM25, but `websearch_to_tsquery` + `ts_rank_cd`
   is close enough at single-user corpus size, and trigram similarity covers exact
   identifiers. No deployment change; ParadeDB/pg_search stays a swap-in option if
   ranking quality ever disappoints.
5. **Scope dimension: `ecosystem` | `service`.** Service identity is the session's
   `repo_path` (matching how sessions already carry it); the on-disk folder for a
   service uses a slug (basename, `-<sha256[:8]>` suffix on collision). Sessions see
   ecosystem-scope memory plus their own service's — never another service's, except
   through the explicit UI search.
6. **Semantic source of truth is the filesystem; the DB is an index.** Files live in
   a managed memory root (persisted setting `memory.root`, env default
   `CLAUDE_UI_MEMORY_ROOT` → `~/claude-memory`), Markdown with YAML frontmatter. A
   background sync tick (LibrarySyncService shape) detects human edits by content
   hash and re-indexes/re-embeds — the human-in-the-loop is "edit the file", no
   special UI required (though the UI gets a browser/editor too).
7. **Reflection model is a persisted setting** (`memory.reflection-model`, default
   `haiku`). `runSystemTurn` gains an optional model override implemented as a
   `set_model` round-trip (set → run turn → set back to `haiku`), safe under the
   existing system-session lock. Raise to `sonnet` in Settings if haiku's
   extraction quality disappoints — no restart.
8. **Journal retention lands here, default off.** `session_event` rows of CLOSED
   sessions become prunable once reflected, controlled by `memory.retention-days`
   (0 = keep forever, the default — single user, disk is cheap; the *policy* is
   what this item owes, not aggressive deletion).
9. **Semantic memories interlink with Obsidian-style wikilinks** (2026-09-05
   follow-up): `[[slug]]` / `[[slug|alias]]` in the Markdown **body** (not
   frontmatter — body links are what Obsidian's graph view reads). Filenames are
   already slugs, so the memory root is an **Obsidian-compatible vault** by
   construction — opening it in Obsidian gives graph view + backlinks with zero
   conversion; Logseq reads the same link syntax (best-effort, its
   property/frontmatter conventions differ). Links are extracted at index time into
   `memory_link`, giving backlinks and a one-hop "related memories" neighborhood on
   every read. **Dangling links are allowed** (Obsidian semantics): a link to a
   not-yet-written memory marks a known gap and auto-resolves when a doc with that
   slug later appears. Resolution order for a slug that exists in more than one
   scope: same scope as the linking doc first, then ecosystem, then (UI only) other
   services. Slugs are immutable after create — no rename cascade to maintain.
10. **Claude sessions pre-approve the memory tools; Codex sessions don't.** The
   tools are read-only, so `mcp__memory__memory_tags`/`__memory_search`/`__memory_read`
   are appended to `allowedTools` at spawn for Claude sessions (same rationale as the
   system session's `mcp__linear` pre-approval) — named individually rather than the
   blanket `mcp__memory` server-level grant since phase 7.4 added orchestration tools
   to this same server that must NOT be pre-approved. Codex sessions reject
   `allowedTools` by design (5.13), so memory
   tool calls there go through the normal approval flow — mildly noisier, correct.
11. **The memory MCP entry authenticates with the existing `CLAUDE_UI_TOKEN`
    — no new secret** (2026-09-05 follow-up). This app has exactly one shared
    secret and one trust tier (`AuthTokenService`/`AuthTokenFilter`: a single
    configured bearer token gates all of `/api/**`, constant-time compared); there
    is no scoped/per-purpose token infrastructure to plug into, and building one
    for a single-user LAN deployment would be new complexity with no threat model
    behind it. As of decision 12a the mechanism is even simpler than first drafted:
    the memory MCP entry is `{type: "http", headers: {Authorization: "Bearer
    <token>"}}`, identical in shape to the Linear entry `linearMcpServer()` already
    embeds in-cleartext in the per-session `<worktree-root>/.mcp/<id>.json` — the
    exact same exposure class, not a new one, and the exact same code path (an
    inline header on an http-type server), not a new "env var on a spawned child"
    mechanism at all. `SessionService` reads `props.authToken()` (the same value
    `AuthTokenService` checks requests against) when building the `memory` MCP
    entry; unset (loopback/no-auth deployments) → the entry carries no
    Authorization header and `/api/mcp/memory` skips the check accordingly
    (matching `AuthTokenService.required()==false`). Revoking access means
    rotating `CLAUDE_UI_TOKEN`, same as revoking dashboard access — one knob, not
    two.
12a. **No separate `memory-mcp` process — an in-process Spring AI MCP server instead**
    (2026-09-05 follow-up, superseding the `memory-mcp/` package below wherever it's
    still mentioned). Linear's MCP entry is already `{type: "http", url, headers}`,
    not stdio, and `sidecar-codex/src/mcp.ts` confirms Codex's app-server also
    translates HTTP-type MCP configs (bearer → a named env var on its own child) —
    so HTTP MCP already works end-to-end for both providers with no new transport
    to build. This project is on Spring Boot 4.1.1 / Spring Framework 7; Spring AI
    2.0.0 (GA June 2026) requires exactly that baseline and ships
    `spring-ai-starter-mcp-server-webmvc` — an in-process Streamable-HTTP MCP
    server via `@McpTool`-annotated beans, transport/protocol layer only (no
    AI-provider autoconfiguration, no API key needed to start). One endpoint,
    `/api/mcp/memory`, added to the existing backend; falling under `/api/**`
    means the existing `AuthTokenFilter` bearer-token gate already covers it with
    zero new auth code (Spring AI's own default is **no auth at all** on the MCP
    endpoint, so this placement is load-bearing, not incidental — verify at
    implementation time that the auto-configured path actually lands under
    `/api/` and adjust `AuthTokenFilter.shouldNotFilter` explicitly if not).
    Eliminates: a new npm package, a build step, per-session child-process
    spawning, and env-var plumbing for the token/scope.
12b. **Session scoping is an explicit `sessionId` tool argument, not transport
    context.** The annotation API's tool-context types (`McpTransportContext` /
    `McpSyncRequestContext`) don't cleanly expose inbound HTTP headers to a tool
    method for the WebMVC transport, and threading a custom header through a
    `ThreadLocal` filter is possible but unverified/fragile — not worth the risk
    when a plain tool parameter does the job. Every memory tool (`memory_tags`,
    `memory_search`, `memory_read`) takes a required `sessionId` argument; the
    backend resolves it against `SessionRepository` for `repoPath` (the scope
    filter) server-side, so a session can't be scoped by a client-supplied string
    it doesn't actually hold. The agent learns its own session id for free from
    the episodic-window system-prompt block (added regardless, per the
    "Automatic episodic window" section) — one more fact in text already being
    injected, not new plumbing. Consequence: every session's `mcpConfig.memory`
    entry is now **identical** (same URL, same static Authorization header) —
    even simpler than decision 11 assumed; `withDefaultMemoryMcp()` needs no
    per-session customization at all, unlike `linearMcpServer()`'s bearer header
    which at least varies by whether OAuth vs API key is configured.
    Security note: any caller holding `CLAUDE_UI_TOKEN` can pass any session id
    and read that session's service-scoped memory — no new capability though,
    since the same token already grants full read access to that session's
    entire transcript via `GET /api/sessions/{id}/events`.
12. **The auth token is now also printed unmasked at startup** (2026-09-05,
    implemented ahead of the rest of this doc — `Application.logConfig`): a
    dedicated `claude-ui auth token (dashboard login): <value>` line, so both the
    dashboard-login token and (once wired) `memory-mcp`'s credential are visible
    straight from `tail -f /tmp/claude-ui.log` or `logs/claude-ui.log`, no separate
    `cat /tmp/claude-ui.token` step. The existing masked summary line (`authToken=
    ****`) stays as-is as a config-presence check. Trade-off worth naming: the
    token now also lands in the rolling structured log (`logs/claude-ui.log`, 14
    days kept) in addition to the already-plaintext `/tmp/claude-ui.token` — same
    exposure class as decision 11 above, not a new one, but now with a longer
    retention window than the token file has. Acceptable for this app's posture
    (single user, LAN, one secret already handled this way); worth remembering if
    a log bundle is ever shared for debugging.
14. **Reflection requires explicit human approval before writing, by default**
    (2026-09-06 follow-up, supersedes decision 2's "applied immediately" —
    superseded language struck through there). Semantic memory is a durable store
    future sessions read from and trust; the original design's review loop was
    after-the-fact ("editing or deleting the file is the veto"), which is weaker
    than how this app treats every other consequential action (permission modes
    default to *asking*, not auto-applying). A reflection now computes the
    episode + semantic ops exactly as before, but — when
    `memory.reflection-approval-required` (persisted setting, **default true**) —
    holds them as a `memory_proposal` row (PENDING/APPROVED/DISCARDED) and
    journals `reflection_proposed` instead of applying anything; a human then
    approves (optionally editing the episode text or any op's
    description/content/tags first — mirrors `permission_response`'s
    `updatedInput`, editing a tool call before allowing it) or discards from the
    🧠 dashboard dialog's new "Pending" tab (topbar badge count, same pattern as
    the 📚 library's discovery badge — a proposal must survive independent of any
    single session's widget, since it's typically created right as that session
    closes). Approval is all-or-nothing per *reflection*, not per op, but each op
    can be individually dropped (a checkbox) without discarding the whole
    proposal. `reflectedSeq` is only set at actual-apply time (approval, or
    immediately in the auto-apply-off case) — never at proposal time — so a
    pending proposal doesn't block a later reflection from being *computed*, but
    a **second concurrent proposal for the same session is refused**, enforced by
    a partial unique index (`memory_proposal(session_id) WHERE status='PENDING'`),
    checked fast in `ReflectionService.reflect()` before spending a system turn
    and again atomically by the index itself. Auto-apply (the original decision 2
    behavior) is still available by turning the setting off — a straight
    `applyReflection()` call with no intermediate row, identical output.

## Storage model

```
<memory-root>/                      # persisted setting memory.root
├─ ecosystem/                       # scope: ecosystem — cross-service learnings
│  └─ postgres-conventions.md
└─ services/
   └─ claude-ui/                    # scope: service — slug of repo basename
      └─ flyway-only-schema.md
```

Each semantic memory file:

```markdown
---
name: flyway-only-schema            # slug, unique within scope dir == filename
description: Schema changes go through Flyway migrations only, never manual DDL
tags: [database, conventions]
scope: service
service: /mnt/d/projects/claude-ui  # canonical repo path; absent for ecosystem scope
sessions: [<uuid>, ...]             # sessions that created/updated this memory
updated: 2026-09-05
---

<the fact/rule/decision, markdown body — this is what memory_read pages through.
Related memories are wikilinked inline: "unlike [[postgres-conventions]], this
service uses …" — see decision 9. A link may point at a memory that doesn't exist
yet (dangling, rendered as such in the UI; resolves automatically once written).>
```

The vault-compatibility contract: filename == `name` slug, links by slug in the
body, frontmatter limited to keys Obsidian tolerates (`tags` is even natively
understood). Pointing Obsidian at `<memory-root>` as a vault gives the human graph
view, backlinks, and full-text search over the agent's memory for free — no export
step, no sync, same files.

### Schema — `V9__memory.sql`

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;   -- vector already enabled by V7

CREATE TABLE memory_doc (
    id            UUID PRIMARY KEY,
    scope         TEXT NOT NULL CHECK (scope IN ('ecosystem','service')),
    service_path  TEXT,                   -- null iff scope = 'ecosystem'
    rel_path      TEXT NOT NULL UNIQUE,   -- relative to memory root; file is truth
    name          TEXT NOT NULL,          -- slug
    description   TEXT NOT NULL,
    tags          TEXT[] NOT NULL DEFAULT '{}',
    content       TEXT NOT NULL,          -- indexed copy of the file body
    content_hash  TEXT NOT NULL,          -- SHA-256, drives human-edit detection
    status        TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ARCHIVED')),
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    embedding     vector(1024),           -- null when Voyage unconfigured
    tsv           tsvector GENERATED ALWAYS AS (
                    setweight(to_tsvector('english', name || ' ' || description), 'A')
                    || setweight(to_tsvector('english', content), 'B')) STORED
);
CREATE INDEX ON memory_doc USING hnsw (embedding vector_cosine_ops);
CREATE INDEX ON memory_doc USING gin (tsv);
CREATE INDEX ON memory_doc USING gin (content gin_trgm_ops);
CREATE INDEX ON memory_doc (scope, service_path) WHERE status = 'ACTIVE';

-- wikilinks extracted from doc bodies at index time; to_doc_id null = dangling
CREATE TABLE memory_link (
    from_doc_id   UUID NOT NULL REFERENCES memory_doc(id) ON DELETE CASCADE,
    to_slug       TEXT NOT NULL,          -- as written in [[...]], resolution target
    to_doc_id     UUID REFERENCES memory_doc(id) ON DELETE SET NULL,
    PRIMARY KEY (from_doc_id, to_slug)
);
CREATE INDEX ON memory_link (to_doc_id);  -- backlinks
CREATE INDEX ON memory_link (to_slug) WHERE to_doc_id IS NULL;  -- dangling, for re-resolution

CREATE TABLE memory_episode (
    id            UUID PRIMARY KEY,
    session_id    UUID NOT NULL,          -- no FK CASCADE: episodes outlive sessions
    session_name  TEXT NOT NULL,
    service_path  TEXT NOT NULL,
    ts            TIMESTAMPTZ NOT NULL,
    summary       TEXT NOT NULL,          -- "what happened": outcome, failures, cost
    embedding     vector(1024),
    tsv           tsvector GENERATED ALWAYS AS (to_tsvector('english', summary)) STORED
);
CREATE INDEX ON memory_episode USING hnsw (embedding vector_cosine_ops);
CREATE INDEX ON memory_episode USING gin (tsv);
CREATE INDEX ON memory_episode (service_path, ts DESC);

ALTER TABLE session ADD COLUMN reflection_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE session ADD COLUMN reflected_seq BIGINT;   -- last seq covered by reflection
```

The episodic *raw* layer already exists — `session_event` is the immutable
append-only log. `memory_episode` is its distillate; retention (decision 8) prunes
the former only after the latter exists.

### Schema — `V10__memory_proposal.sql` (decision 14 follow-up)

```sql
CREATE TABLE memory_proposal (
    id            UUID PRIMARY KEY,
    session_id    UUID NOT NULL,
    session_name  TEXT NOT NULL,
    service_path  TEXT NOT NULL,
    reflected_seq BIGINT NOT NULL,   -- applied to the session only on approval
    episode       TEXT NOT NULL,
    ops           JSONB NOT NULL,    -- editable at approval time
    status        TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','DISCARDED')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at    TIMESTAMPTZ
);
-- at most one pending proposal per session — a concurrent second reflect is refused, not queued
CREATE UNIQUE INDEX idx_memory_proposal_one_pending_per_session ON memory_proposal (session_id)
    WHERE status = 'PENDING';
```

## Reflection flow (`ReflectionService`)

1. **Trigger**: `close()` on a `reflection_enabled` session publishes a
   `ReflectionRequested` Spring event — not a direct call, so `SessionService`
   never depends on `ReflectionService` (which itself depends on
   `SessionService.runSystemTurn`; a direct call the other way would be
   circular) — consumed by an `@EventListener` that spawns a virtual thread
   (close does not block on it; the transcript comes from the journal, so the
   already-removed worktree is irrelevant). Manual: `POST
   /api/sessions/{id}/reflect`, routed straight to `ReflectionService` from
   `SessionController` (synchronous — same precedent as ticket import/git-assist
   blocking on `runSystemTurn`), skipped when `max(seq) <= reflected_seq`, and
   refused (409) if a proposal is already pending for this session (decision 14)
   or a reflection is already in flight.
2. **Digest**: render the journal into a compact transcript — user/assistant text,
   tool *names* + one-line arg summaries (not full outputs), errors, permission
   denials, final git/PR state, total cost. Cap ~100 KB, keeping head + tail when
   over (the opening framing and the closing state carry the most learning).
   This renderer (`TranscriptDigest.render()`, `de.pamir.claude.ui.journal` — moved
   out of this feature's own package once it gained a second, non-memory consumer)
   is the capped half of the pair that also backs 5.9's transcript export
   (`renderMarkdown()`, uncapped Markdown) — same per-event-type logic, shipped
   2026-09-06.
3. **Context**: fetch the semantic-memory *index* for the session's two scopes —
   name + description + tags per ACTIVE doc, not content (cheap; this is what lets
   the model `update` instead of duplicating).
4. **One system turn** (model per decision 7), prompt returning strict JSON:

   ```json
   {
     "episode": "3–6 sentence summary of what happened: goal, approach, outcome, failures, dead ends",
     "semantic": [
       {"op": "create|update|archive", "scope": "ecosystem|service",
        "name": "kebab-slug", "description": "...", "tags": ["..."],
        "content": "markdown body", "reason": "why this is worth remembering"}
     ]
   }
   ```

   Prompt rules: only durable, non-obvious learnings (not restatements of the repo's
   own docs); prefer `update` of an existing indexed doc over `create`; `ecosystem`
   scope only for genuinely cross-service facts; ≤ 8 ops; **link related memories
   with `[[slug]]` wikilinks in the body**, liberally — the provided index lists
   every existing slug, and a link to a not-yet-existing slug is legal (marks a
   gap, resolves later). Backend validates ops
   (unknown `update`/`archive` names → skipped with a `warning` journal event, same
   mechanism as provisioning warnings) and caps at 10 regardless.
5. **Propose or apply** (decision 14): with `memory.reflection-approval-required`
   (default true), insert a `memory_proposal` row (PENDING) holding the episode +
   raw ops and journal `reflection_proposed {proposalId, episode, ops}` — nothing
   is written to the vault yet. With it off, skip straight to **Apply** below.
   A pending proposal is reviewed in the 🧠 dialog's "Pending" tab: approve
   (optionally editing the episode text or any op's description/content/tags —
   dropping an op entirely is legal, mirrors `permission_response`'s
   `updatedInput`) or discard, all-or-nothing per reflection.
6. **Apply** (immediately if approval is off; on approval otherwise): insert
   `memory_episode` (+ embed best-effort), write/update/archive semantic files +
   `memory_doc` rows (+ embed), extract wikilinks into `memory_link` (and
   re-resolve any dangling links elsewhere that point at a slug created by this
   reflection), set `reflected_seq`, journal a `reflection_complete` event
   `{episode, created: [...], updated: [...], archived: [...], warnings: [...]}`
   — visible in the transcript like any other event. A discarded proposal instead
   journals `reflection_discarded {episode}` and writes nothing; `reflectedSeq`
   stays unset either way until something is actually applied, so a discarded
   reflection can be tried again later. Editing the file after the fact remains a
   second, independent veto (decision 6) — this step adds a *first* one, before
   the write happens at all.

Failure posture: reflection is best-effort — a failed turn / invalid JSON journals a
`warning` and leaves `reflected_seq` untouched (retry via the manual action); it
never blocks or fails `close()`.

## Retrieval

### Hybrid search (one engine, all consumers)

`MemoryRepository.search(query, scopeFilter, kind, tags, limit)`:

- **Dense**: `embedding <=> :queryEmbedding` top-50 (skipped when Voyage
  unconfigured — same posture as the library).
- **Sparse**: `tsv @@ websearch_to_tsquery(:q)` ranked by `ts_rank_cd`, top-50,
  plus a trigram arm (`content % :q` by `similarity()`) that catches exact
  identifiers FTS stems away.
- **Fusion**: RRF in SQL — `score = Σ 1/(60 + rank_i)` across the arms, single
  query with CTEs. Episodes and docs are searched by the same shape (`kind`
  selects `semantic` | `episodic` | `all`).

### Agent-facing: in-process Spring AI MCP server (decisions 12a/12b)

No new process. `@McpTool`-annotated beans (`spring-ai-starter-mcp-server-webmvc`,
Streamable-HTTP) served from the existing backend at `/api/mcp/memory`, gated by
the existing `AuthTokenFilter`. Every session's `mcpConfig.memory` entry is the
same static block — `{type: "http", url: "http://127.0.0.1:8080/api/mcp/memory",
headers: {Authorization: "Bearer <CLAUDE_UI_TOKEN>"}}` — the exact shape
`linearMcpServer()` already produces, just pointed at ourselves. Codex passes HTTP
MCP configs through with the bearer extracted to a named env var on its child
(confirmed in 5.13's `mcp.ts`), so this works there with zero adapter changes.

Three tools, the drawer-label-before-folder hierarchy, each taking a required
`sessionId` (decision 12b — resolved server-side to that session's `repoPath` for
the scope filter; a session learns its own id from the episodic-window
system-prompt block below):

| Tool | Args | Returns |
|---|---|---|
| `memory_tags` | `sessionId` | tag → count for the session's visible scopes ("what drawers exist") |
| `memory_search` | `sessionId`, `query`, `tags?` | ranked hits: name, scope, description, tags — **never content** |
| `memory_read` | `sessionId`, `name`, `page?` | one ~4 KB page of the doc body + `totalPages` + `related`: the one-hop link neighborhood — outgoing wikilinks and backlinks, each as name + description (+ `dangling: true` for unresolved targets), never content — so the agent sees the drawer labels of adjacent memories and can page onward |

Injection: `withDefaultMemoryMcp()` alongside `withDefaultLinearMcp()` — layered
into every session's `mcpConfig` when `memory.enabled` (persisted setting, default
on); a session's own `memory` entry wins, same rule as `linear`. Claude sessions
also get the three read-only memory tools appended to `allowedTools` at spawn
(decision 10; phase 7.4's orchestration tools live on this same server unapproved).

### Automatic episodic window

At spawn (`buildArgs`), when memory is enabled and the session's service has
episodes: append to the `--append-system-prompt` payload a bounded block — the last
5 `memory_episode` summaries for this `service_path` plus one line announcing the
memory tools ("consult memory before re-deriving decisions"). ≤ ~2 KB, additive to
the session's own `instructions`. Because args are rebuilt on every spawn, a
park→wake picks up episodes reflected meanwhile for free.

### Human-facing

- `GET /api/memory/search?q=&kind=&scope=&servicePath=&tags=` — the UI's
  cross-session "where did I solve X?" search (unscoped by default — the human sees
  everything).
- `GET /api/memory/docs` / `GET /api/memory/docs/{id}` (includes `links` +
  `backlinks`, resolved and dangling) / `PUT` (edit → rewrite file + reindex +
  re-extract links) / `DELETE` (archive; file kept, library-style) ·
  `GET /api/memory/episodes?servicePath=`.
- Dashboard: topbar 🧠 opens the memory browser — search box, doc list grouped by
  scope with tag chips, view/edit/archive, per-service episode timeline. The doc
  view renders `[[wikilinks]]` as navigation (dangling ones visibly muted) and
  shows a backlinks panel — a lightweight Obsidian-ish browse experience; for the
  full graph view, open the memory root in Obsidian itself (it's a valid vault).
- Settings dialog → new "Memory" section: root, enabled, reflection default,
  reflection model, retention days.

### Sync (human edits)

`MemorySyncService`, LibrarySyncService shape: periodic tick walks the memory root;
changed hash → re-parse frontmatter + reindex + re-embed + re-extract wikilinks;
new file → index it and re-resolve dangling links pointing at its slug (a human
creating a memory in Obsidian heals gaps the reflection model left); vanished file
→ ARCHIVED row (restored if it reappears; its inbound links go dangling, not
deleted — `ON DELETE SET NULL` shape, so restoration re-resolves them). Interval reuses
`memory.sync-interval-minutes` (default 5 — cheap, it's one local directory walk).

## Per-session & settings surface

- **Session/template**: `reflectionEnabled` boolean — create dialog checkbox +
  template field + `PATCH /api/sessions/{id}`; widget kebab gains "Reflect now".
  Default from persisted setting `memory.reflection-default` (off). Works for both
  providers — reflection reads the journal, which is provider-neutral.
- **Persisted settings** (all new, Settings dialog → "Memory"): `memory.root`,
  `memory.enabled` (default on), `memory.reflection-default` (off),
  `memory.reflection-model` (`haiku`), `memory.sync-interval-minutes` (5),
  `memory.retention-days` (0 = never prune), `memory.reflection-approval-required`
  (**default true** — decision 14; off restores straight auto-apply).
- **Env**: `CLAUDE_UI_MEMORY_ROOT` — default for `memory.root` only, per the
  skills-root precedent.

## Out of scope for this pass

- Static top-k semantic injection at create (revisit as opt-in if tools prove
  under-used).
- Reflection on park / every N turns.
- Memory write tools for live sessions (`memory_write`) — reflection is the only
  writer besides the human; keeps quality control in one reviewed path.
- Cross-service memory visibility for agents (UI search already has it).
- True BM25 (ParadeDB) and non-English FTS configs.
- Importing existing `CLAUDE.md`/docs into memory — memory is for *learned* facts;
  repo docs are already in context via the repo.

## Tasks

**Status (2026-09-06): backend + core frontend implemented and runtime-verified,
including the decision-14 approval gate** (Postgres migrations applied, full REST
+ MCP round trip tested live with curl — doc create/read/edit/archive,
dangling-link auto-resolution, backlinks, hybrid search sparse-arm,
`tools/list`/`tools/call` over the real Streamable-HTTP MCP handshake; the
proposal flow verified via a fixture-inserted `memory_proposal` row exercising
`GET /api/memory/proposals`, approve-with-edits (edited description/content/tags
landed, not the originals), discard-writes-nothing, the partial-unique-index
refusing a second concurrent pending proposal, and a 409 on re-deciding an
already-decided proposal — plus the `ApplicationTests` full-context-load test).
**Not yet verified**: an actual live Claude/Codex session calling the memory
tools end-to-end, or a real reflection (system-turn LLM call) producing a
proposal, rather than a SQL fixture standing in for one — both need a real
sidecar run, not just curl against the endpoints. The TemplateManager only gets
`reflectionEnabled` via its existing "Advanced raw JSON" field rather than a
dedicated checkbox (same mechanism every other not-yet-promoted template field
already uses).

1. **Schema** — `V9__memory.sql` as above; entity/repository records.
2. **Doc store** — `MemoryDocService`: frontmatter parse/serialize, slugging,
   scope-dir layout, write/update/archive with hash + best-effort embed; wikilink
   extraction (`[[slug]]` / `[[slug|alias]]` regex over the body, code fences
   excluded), `memory_link` upkeep + dangling re-resolution; `MemorySyncService`
   tick.
3. **Transcript digest renderer** — journal → capped markdown digest; later moved
   to `de.pamir.claude.ui.journal` and given an uncapped sibling method when 5.9
   (transcript export) shipped and became its second consumer.
4. **Reflection** — `ReflectionService` (digest + index → system turn → validate +
   apply ops), `runSystemTurn` model-override via `set_model` round-trip, close
   hook, `POST /api/sessions/{id}/reflect`, `reflection_complete` +
   PROTOCOL.md entry.
5. **Hybrid search** — RRF query (dense + FTS + trigram CTEs), kind/scope/tag
   filters, Voyage-less degradation.
6. **Memory REST API** — `MemoryController`: search/docs/episodes CRUD per above.
7. **Memory MCP server** — add `spring-ai-bom` + `spring-ai-starter-mcp-server-
   webmvc` (2.0.0), three `@McpTool` beans at `/api/mcp/memory` (each taking
   `sessionId`, decision 12b), verify the endpoint's actual path lands under
   `/api/**` for `AuthTokenFilter` coverage (widen `shouldNotFilter` explicitly if
   not — see decision 12a).
8. **Session wiring** — `withDefaultMemoryMcp()` (static `{type:"http", headers:
   {Authorization}}` block, mirroring `linearMcpServer()`), `allowedTools` append
   (Claude only), episodic window in `buildArgs` (carries the session's own id for
   tool calls per decision 12b), `reflection_enabled` through
   create/template/PATCH/duplicate.
9. **Frontend** — create dialog + template checkbox, kebab "Reflect now", Settings
   "Memory" section, 🧠 memory browser (search, edit, archive, episode timeline,
   clickable wikilinks + backlinks panel), `reflection_complete` rendering in the
   transcript.
10. **Retention + docs** — CLOSED-and-reflected journal pruning behind
    `memory.retention-days`; CLAUDE.md (settings table, env var, 🧠), 
    ARCHITECTURE.md §4 sketch → as-built section, PROTOCOL.md, phase-5 index line.
11. **Approval gate (decision 14)** — `V10__memory_proposal.sql`;
    `MemoryProposalRepository`; `ReflectionService` split into propose-vs-apply
    (`applyReflection()` shared by auto-apply and `approveProposal()`),
    `discardProposal()`, the pending-proposal fast-fail in `reflect()`;
    `memory.reflection-approval-required` setting (default true);
    `MemoryController` proposal endpoints; `reflection_proposed`/
    `reflection_discarded` PROTOCOL.md entries; 🧠 dialog "Pending" tab
    (per-op include/edit, Approve/Discard) + topbar badge (📚-badge pattern).

**Already shipped ahead of the rest of this doc** (2026-09-05): `Application.java`'s
startup `CommandLineRunner` now also logs the auth token unmasked (decision 12) —
no dependency on the tasks above, done independently since Task 7/8 will need the
same token to build the memory MCP entry's Authorization header, and this makes it
easy to grab by hand for manual testing (`curl` against `/api/mcp/memory`) in the
meantime.

## Definition of Done

- Enable reflection on a session, make a real decision in it (e.g. pick approach A
  over B for a stated reason), close it → with approval required (the default),
  the transcript shows `reflection_proposed` and the 🧠 dialog's Pending tab shows
  it with a badge count; approving it (optionally editing the description first)
  produces a `memory_episode` row, a semantic `.md` file under the correct scope
  dir with sane frontmatter, and `reflection_complete` in the transcript listing
  exactly what was written. Discarding instead writes nothing and journals
  `reflection_discarded`. Turning `memory.reflection-approval-required` off
  reproduces the original one-step auto-apply behavior.
- Open a **fresh session on the same service**, ask about that decision → the agent
  calls `memory_search`/`memory_read` (visible in the transcript) and answers
  correctly from memory. The original 5.3 DoD, now via tools.
- The fresh session's system prompt carries the episodic window (episode summary of
  the closed session visible in `system_init`/behavior).
- A **Codex** session gets the same tools and can read the same memory.
- UI search returns ranked hits for a conceptual query *and* for an exact
  identifier that appears only verbatim (e.g. a made-up error code) — proves both
  hybrid arms. With `CLAUDE_UI_VOYAGE_API_KEY` unset, everything still works
  sparse-only.
- Hand-edit a memory file on disk → within the sync interval the change is
  re-indexed and served by `memory_read`; deleting the file archives the doc.
- **Knowledge graph**: a reflection that touches related topics produces docs
  linked by `[[wikilinks]]`; `memory_read` on one returns the other under
  `related` with its description (and backlinks in the reverse direction). A
  dangling link resolves automatically once a doc with that slug is created —
  whether by a later reflection or by a human dropping a file into the root.
  Opening the memory root in Obsidian shows the docs as a connected graph with
  working links — no conversion step.
- "Reflect now" on a live session works; a second immediate "Reflect now" with no
  new turns is refused/no-ops; reflection failure never breaks `close()`.
- A session with reflection off writes nothing; `memory.enabled` off injects no
  MCP server and no episodic window.
- **Approval gate**: while a proposal is pending for a session, a second
  "Reflect now" on it is refused (409); approving/discarding an
  already-decided proposal is refused (409); an edited op's content in the
  approve payload is what actually gets written, not the model's original.

## Manual test script

1. `docker compose up -d`, build, start backend with a Voyage key set. Settings →
   Memory: verify defaults (enabled on, reflection default off, model haiku).
2. Create session A on this repo, reflection **on**, prompt it to investigate
   something and make an explicit decision containing a unique fake token (e.g.
   "record that ERR_MEMTEST_42 means X"). Let it finish; close (commit/discard as
   needed).
3. Watch session A's transcript: `reflection_proposed` appears within ~a minute
   (approval required by default); open 🧠 → Pending, review the episode + ops,
   edit a description, uncheck one op if there's more than one, Approve. Now
   `reflection_complete` appears in the transcript; inspect
   `<memory-root>/services/claude-ui/*.md` — file exists, frontmatter sane, and
   the edited description (not the model's original) is what's there. `psql`:
   `memory_episode` has one row; `memory_doc` embedding is non-null;
   `memory_proposal` shows the row as APPROVED with `decided_at` set.
4. Create session B, same repo, reflection off. Ask "what does ERR_MEMTEST_42
   mean?" → agent uses memory tools, answers correctly. Check `system_init` /
   first-turn context for the episodic window.
5. Create a **Codex** session C, same repo, ask the same question → tools appear,
   approval prompt (not pre-approved), correct answer after approval.
6. 🧠 browser: search "ERR_MEMTEST_42" (exact arm) and a paraphrase of the decision
   (dense arm) — both return the doc, ranked sensibly. Edit the doc in the UI,
   verify the file changed on disk.
7. Hand-edit the file on disk (change a word); after the sync interval the browser
   shows the edit. Delete the file; doc shows ARCHIVED; `memory_read` for it no
   longer returns content.
7a. Graph: edit one memory to add a wikilink to the other and a second link to a
    made-up slug `[[not-written-yet]]`. After sync: the doc view renders the first
    as navigation and the second muted/dangling; the linked doc's backlinks panel
    shows the referrer; `memory_read` returns both under `related` (dangling
    flagged). Create `not-written-yet.md` by hand in the same scope dir → after
    sync the dangling link is resolved everywhere. Open `<memory-root>` in
    Obsidian: vault loads, graph view shows the links, clicking through works.
8. "Reflect now" on a live session mid-work → a proposal appears; immediately
   "Reflect now" again → refused 409 ("already pending approval"), before it
   burns another system turn. Discard the proposal → nothing written,
   `reflection_discarded` in the transcript, `memory_proposal` row DISCARDED;
   "Reflect now" a third time now succeeds (discarding didn't set
   `reflectedSeq`). Turn `memory.reflection-approval-required` off in Settings,
   "Reflect now" once more → applies immediately, no proposal, straight
   `reflection_complete` — the original one-step behavior. Kill the Voyage key,
   restart, repeat step 6's exact-token search → still found (FTS/trigram);
   dense-only paraphrase may degrade — expected.
9. Set `memory.retention-days=1`, verify pruning only touches CLOSED sessions with
   `reflected_seq` set (spot-check `session_event` counts).
