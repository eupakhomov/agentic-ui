# Phase 18 — Slack integration (session = thread)

Status: **landed (2026-10-17)**, Steps 1-4 done; see each step's implementation log below for what was
built, what the live runs found, and what is still unverified. Split out of the Phase 17 draft so
Phase 17's DoD didn't wait on an external app setup. Depends on nothing in Phase 17.

## Background

The dashboard is the only way to see and steer sessions. Slack is where a good part of the
working day happens, on the laptop and on the phone. This phase mirrors each session into one
Slack thread — a top-level message that stays current, and thread replies for the moments that
need a person — and accepts the same decisions back from Slack: tool permissions, plan approval,
`AskUserQuestion` answers, and new prompts.

It's a second remote-control surface. With Phase 17's auth-off mode (local clients only), Slack
becomes the practical way to steer a session from outside the machine, so its own gate — the
Slack user allowlist — matters as much as the dashboard's token does.

## Confirmed decisions (2026-10-17)

1. **Socket Mode**, no public URL. Fits the LAN deployment.
2. **One channel**, fixed in config. A session is one top-level message plus its thread.
3. **Inbound control in v1**: Allow/Deny on permissions, **plan approval**, `AskUserQuestion`
   answers, and thread replies as prompts.
4. **Control via buttons, not typed commands.** Every thread reply is a prompt. Stop/Status/Open
   are buttons on the parent message. Typed `stop` would collide with a real prompt ("stop using
   that library").
5. **Library: none — Socket Mode on `java.net.http.WebSocket` and the Web API on `RestClient`**
   (decided in Step 1, 2026-10-17; this was the planned fallback). `slack-api-client` ships its
   Socket Mode WebSocket backends (`tyrus-standalone-client`, `Java-WebSocket`) as `provided`
   dependencies we would have to add ourselves, the Tyrus one speaks the pre-Jakarta
   `javax.websocket` API beside Boot 4's Tomcat, and the rest of the SDK (OkHttp, Gson, Kotlin
   stdlib) would be pulled in for about six POST calls. The protocol is one `apps.connections.open`
   call, one WebSocket, envelopes in, `{envelope_id}` acks out; Block Kit is JSON we build as
   `JsonNode` anyway. No Bolt, no new dependency.
6. **Session close posts a final message and adds a ✅ reaction.**
7. **Off by default, opt-in per session**, with a global default setting.

## Shape

### Outbound

- **Session created** → top-level message: name, provider, branch, session type, initial prompt
  excerpt, and an **Open** link (see `public-url`). `slack_channel` and `slack_thread_ts` are
  stored on the session (Flyway migration). If the first post fails, the parent is created
  **lazily on the first successful post**, so a Slack outage at creation doesn't leave the
  session threadless forever.
- **Parent message updates** (`chat.update`) on state change and PR status change only, coalesced
  to at most one update per 10 s per session. Not on cost — cost goes in the final message.
  Buttons on the parent: **Stop**, **Status**, **Open**.
- **Thread replies** are a curated set, not the transcript:
  - permission request (tool name + one-line summary) with **Allow** / **Deny**;
  - **plan approval**: the plan text (chunked into ≤3,000-char section blocks, or uploaded as a
    snippet via `files.uploadV2` when longer than ~12k chars, with the first chunk inline) with
    **Approve plan** / **Keep planning**;
  - `AskUserQuestion`: the question, one button per option (a static select beyond 5), and a hint
    that a free-text thread reply also answers it;
  - turn finished (short summary from `TranscriptDigest`), error, PR opened / status changed,
    session closed (final message with cost, then ✅).
  - Anything long is truncated with an **Open** link.
- **Children** from `spawn_child_session` post into the parent's thread, prefixed with the child's
  name; they get no top-level message. **The system session never posts.**

### Inbound

- **Permission buttons** resolve the request through the same path as the sidebar and the
  `y`/`d` hotkeys. The response is **conditional**: only if the session's pending request ID
  equals the button's `value`; otherwise the message is edited to "already decided on the
  dashboard". A decision made on the dashboard edits the Slack message the same way. Both
  directions, or the thread lies.
- **Plan approval** sends the same `permission_response` the dashboard's plan card sends
  (`allow`, or `deny` with "Keep planning; do not execute yet."). Edit-before-allow is
  dashboard-only.
- **`AskUserQuestion`**: a button sends that option; a free-text reply while a question is
  pending answers it with the text. Otherwise a free-text reply is a prompt.
- **Thread reply** → user prompt, queued if a turn is running, as the dashboard does.
- **Stop** → `interrupt`. **Status** → ephemeral reply with state, time in state, cost, PR.

### Security

- **Allowlist** of Slack user IDs, `agentic-ui.slack.allowed-users`, **default empty = nothing is
  accepted**. Applies to **messages and `block_actions` alike** (the action payload has its own
  `user.id`). Anyone else is ignored and logged at WARN with the user ID.
- **Message filtering**: only plain user messages in a known session thread. Ignore every
  `subtype` (`message_changed`, `message_deleted`, `thread_broadcast`, `bot_message`), anything
  with `bot_id`, and anything whose `thread_ts` maps to no session. Editing a prompt in Slack
  does not re-send it.
- **At-least-once delivery.** Socket Mode redelivers envelopes not acked within ~3 s and after
  reconnects. Ack immediately, process asynchronously, and **dedupe** by `event_id` (events) and
  `action_ts` + `trigger_id` (block actions) in a bounded LRU. Without this a reply becomes two
  prompts and an Allow is sent twice.
- **Audit.** Journal `permission_response` and user prompts with `source: "slack"` and
  `slackUser: "U…"`; the transcript shows where a decision came from. Permission messages are
  edited to show who decided.
- **Secrets in tool inputs.** A Bash permission request would post the command, which may embed
  tokens (`curl -H "Authorization: …"`, `export TOKEN=…`). **Redaction is on by default**: known
  secret patterns are masked, `envVars` are never posted, file contents are never posted.
  `slack.verbose: true` opts out of masking.
- **Privacy.** Content leaves the machine. Off by default, per-session opt-in (create-dialog
  checkbox, template key, `slack.default-enabled` setting).
- **Threat model note.** The socket is authenticated by the app token; the allowlist decides who
  can act. Anyone allowlisted is a trusted operator — the same trust as the dashboard. There is
  no second factor; don't allowlist a shared account.

### Resilience

- Slack failures never block or fail a session. Outbound goes through a bounded per-session queue
  with retry/backoff on `429` (`Retry-After`), ≤1 `postMessage`/s/channel, `chat.update` under
  its Tier-3 budget. Under pressure the queue **drops parent updates first and never drops
  permission, plan or question messages** — those are what someone is waiting on.
- Reconnect: the Socket Mode client reconnects; the thread mapping survives in the DB. Missed
  inbound events during a disconnect are not replayed (Slack doesn't replay them either); the
  parent's Status button is the recovery path.

## Configuration

Secrets are env or `application-local.yaml` only, never the settings table:

```yaml
agentic-ui:
  slack:
    enabled: ${AGENTIC_UI_SLACK_ENABLED:false}
    bot-token: ${AGENTIC_UI_SLACK_BOT_TOKEN:}      # xoxb-…
    app-token: ${AGENTIC_UI_SLACK_APP_TOKEN:}      # xapp-… (Socket Mode)
    channel: ${AGENTIC_UI_SLACK_CHANNEL:}          # channel ID, not name
    allowed-users: ${AGENTIC_UI_SLACK_ALLOWED_USERS:}   # comma-separated Slack user IDs; empty = nobody
    verbose: ${AGENTIC_UI_SLACK_VERBOSE:false}     # true = no secret masking in tool inputs
    public-url: ${AGENTIC_UI_PUBLIC_URL:}          # e.g. https://mac.tailnet.ts.net:8080 — Open button; omitted when unset
```

(As built: `public-url` sits under `agentic-ui.slack`, not at the `agentic-ui` level, to leave
`AppProperties` alone; the env var name is unchanged.)

Persisted settings (Settings dialog → "Slack"): `slack.default-enabled` (default off).

Slack app: scopes `chat:write`, `channels:history` (or `groups:history` for a private channel),
`reactions:write`, `files:write` (plan snippets). Events: `message.channels` / `message.groups`.
Interactivity on. Socket Mode on. The app manifest is in `DEPLOY.md` section 8e and `docs/SLACK.md`.

## Backend structure

- `slack/SlackProperties`, `slack/SlackClient` (Web API calls on `slack-api-client`, the outbound
  queue, rate limiting), `slack/SlackSocket` (Socket Mode connection, ack, dedupe, dispatch),
  `slack/SlackSessionMirror` (subscribes to `SessionEventBus`, maps journal events to thread
  posts, keeps the parent current), `slack/SlackInbound` (allowlist, filtering, calls
  `SessionService` — never the sidecar directly).
- Migration `V<next>__slack.sql`: `ALTER TABLE session ADD slack_channel text, ADD slack_thread_ts
  text, ADD slack_enabled boolean NOT NULL DEFAULT false` (table name to be checked).
- Per-request message `ts` (for editing a permission/plan message after a decision) lives in
  memory keyed by request ID; losing it on restart only loses the "who decided" edit.

## Steps

### Step 1 — Library and connection
Evaluate `slack-api-client`'s Socket Mode client under Boot 4 / JDK 25 (dependency footprint,
WebSocket backend). Decide: use it, or `java.net.http.WebSocket`. Connect, ack, dedupe, reconnect.
Tests with a fake socket.

### Step 2 — Outbound
Config, migration, per-session opt-in, session → thread mapping with lazy parent creation,
curated events, coalesced parent updates, queue and rate limits, redaction, `public-url` links,
child and system-session rules.

### Step 3 — Inbound
Allowlist on messages and actions, subtype filtering, permission and plan buttons with the
stale-decision check in both directions, `AskUserQuestion` buttons and free-text, thread replies
as prompts, Stop/Status, audit fields in the journal. Tests with a fake client.

### Step 4 — Docs
`DEPLOY.md` app manifest and setup, `CLAUDE.md` env rows, `PROTOCOL.md` for the `source` field on
`permission_response`/prompts, decision log.

## Out of scope

- Slash commands, DMs, multiple channels, a channel per project. (Creating sessions from Slack was out of scope in the first plan;
  it was added afterwards as an @mention — see "Follow-up A" and "B" below.)
- Reviewing fork pull requests from Slack, and committing/stashing/discarding on a Slack-initiated close (those stay on the dashboard).
- Edit-before-allow from Slack.
- Multiple backend instances sharing one channel.
- Replaying inbound events missed during a disconnect.

## Definition of Done

- [x] A session with Slack enabled gets one top-level message and a thread; the parent updates on
      state/PR change; close posts a final message and ✅. *(live, real sessions)*
- [x] Allow/Deny, Approve plan/Keep planning, and `AskUserQuestion` buttons steer the session;
      a decision already made on the dashboard is reported, not re-sent, and vice versa.
      *(Live: Allow, Deny, an option button, Approve plan and a dashboard-first decision with the Slack
      prompt rewritten, on a real agent. The private "already decided" reply to a stale click is
      unit-tested only — a settled prompt has no buttons left to click.)*
- [x] A thread reply becomes a prompt exactly once, including under redelivery. *(Live once; redelivery
      de-duplication unit-tested.)*
- [x] Non-allowlisted users (messages and buttons) are ignored and logged; message subtypes and
      bot messages are ignored. *(Subtypes, bot posts and our own edits ignored live; the non-allowlisted
      path is unit-tested only — no second account was available.)*
- [x] Tool inputs are masked by default; `envVars` never appear. *(Live read-back of the thread found
      none of the planted secrets.)*
- [x] A Slack outage or `429` never blocks or fails a session; permission messages are never
      dropped by the queue. *(Unit-tested against a fake; not exercised against a real outage.)*
- [x] The journal records `source`/`slackUser` on Slack-originated decisions and prompts. *(`permission_response`
      carries both; prompts and Stop add a `slack_input` event.)*
- [x] Tests and build pass; docs and the decision log are updated. *(Backend fast suite, frontend
      `tsc` + vitest, `npm run build`; `docs/SLACK.md`, `DEPLOY.md` 8e, `ARCHITECTURE.md` 3i, `CLAUDE.md`.)*

## Manual test script

1. Create a session with Slack on → top-level message with Open link (when `public-url` is set).
2. Trigger a Bash permission → thread message with masked command; Allow in Slack → session
   continues, message shows the decider; the dashboard's Needs-you row clears.
3. Trigger a permission, decide on the dashboard, then click Allow in Slack → "already decided".
4. Plan mode: the plan arrives in the thread (inline chunks or snippet); Approve plan → the
   session executes.
5. `AskUserQuestion` → option buttons; click one → the session proceeds. Repeat with a free-text
   reply.
6. Reply in the thread → prompt arrives once. Edit that Slack message → nothing happens.
7. Reply from a non-allowlisted user → ignored, WARN logged. Click a button as that user → same.
8. Stop button → session interrupted. Status button → ephemeral status.
9. Disconnect the network during a turn → session unaffected; on reconnect posts resume; parent
   Status is correct.
10. Close the session → final message with cost, ✅ reaction.
11. Follow-ups (after the first landing): with `AGENTIC_UI_SLACK_START_ENABLED=true` and the `app_mentions:read` scope + `app_mention`
    event, `@bot review <open PR URL>` → a review session linked to the PR; `@bot dev <service> <branch> <prompt>` → a development
    session; `@bot dev <service> <branch>` → the bot asks for the prompt in the thread and your reply starts it (`cancel` abandons it);
    `@bot dev-with "<template>" …` / `review-with` → the template's settings apply and its kickoff prompt is not sent; unknown service,
    template, a closed/fork PR or a bad branch name → a clear message and nothing started.
12. Press Stop on an idle session → the thread says nothing was running. Press Close: with uncommitted changes → refused, files listed;
    while a turn runs → "press Stop first"; on an idle clean session → confirm → "Close requested", "Session closed", ✅.

## Implementation log

### Step 1 — Library and connection (2026-10-17)

Done, except the inbound-event half of the live check (needs someone to post in the channel).

- **Library decision**: no SDK, see decision 5. `slack/SlackProperties` (`agentic-ui.slack.*`,
  `toString` never prints tokens; `allowed-users` binds from a comma-separated env var),
  `SlackWebApi` (`RestClient`; Slack's `ok:false` becomes `SlackApiException` carrying the error
  code; tokens only in the Authorization header), `SlackEnvelopeProcessor` (the transport-free
  half), `SlackSocket` (the connection loop). Everything is `@ConditionalOnProperty
  agentic-ui.slack.enabled=true`, so with the default `false` no Slack bean exists.
- **`SlackEnvelopeProcessor`**: acks every `events_api`/`interactive` envelope *first*, then
  dedupes on `event_id` (message) or `trigger_id` + `action_ts` (click), falling back to the
  envelope ID, in a 2,048-entry LRU, then dispatches. Dispatch is a **single-threaded** executor
  so events are handled in arrival order — a per-event virtual thread would let two quick thread
  replies swap places. The processor outlives individual connections, so a redelivery after a
  reconnect is still a duplicate. `disconnect` with reason `warning` is only logged;
  `refresh_requested` (and anything else) reconnects.
- **`SlackSocket`**: one virtual-thread loop, one connection at a time; exponential backoff
  1 s → 30 s on failure; reconnect on server request; own ping every 30 s and a 75 s silence
  limit (Slack's pings or our pongs both count). Sends are chained because `WebSocket.sendText`
  must not overlap a previous unfinished send. `auth.test` on the bot token at start (failure is
  logged loudly, the socket still runs). Startup warns when tokens, channel or the allowlist are
  missing. `addListener` is the seam Step 3 plugs into.
- **Tests**: `SlackEnvelopeProcessorTest` (12): ack with envelope ID, redelivery handled once,
  click dedupe, envelope-ID fallback, arrival order, `hello`/unknown/garbage ignored, reconnect
  rules, a throwing listener doesn't stop later events, LRU eviction, token-free `toString`.
- **Live check** against the real app (throwaway harness, outside the repo): `apps.connections.open`
  → connect → `hello` in ~0.5 s; a bad bot token logs `invalid_auth` and the socket stays up;
  clean stop.
- **Live inbound check** (4-minute listener, app token only): a top-level message, a thread
  reply, an edit and a delete each arrived exactly once as `events_api` with the expected
  `event_id` keys. Findings that shape Step 3:
  - An **edit** arrives as `subtype: message_changed`, and deleting a top-level message that has
    thread replies arrives as `message_changed` with a `tombstone` inner message — *not*
    `message_deleted`. Both are dropped by the plan's "ignore every `subtype`" rule; nothing
    extra is needed, but the rule must stay a blanket one rather than a list of known subtypes.
  - Every payload carries a legacy `token` (the app's verification token). **Never log a whole
    payload** in Step 3 — log type, user ID and keys only.
  - Not shown by the listener's 300-character preview, so confirm in Step 3's live pass: that
    the thread reply carries `thread_ts` (documented, and what the session mapping relies on).
  - Not exercised live: a button click (`interactive`), a server-initiated refresh, and whether
    Slack ever retried an envelope (no duplicate reached the handler, but redelivery logging
    was at default level, so the ack round trip is only indirectly confirmed).

### Step 2 — Outbound (2026-10-17)

Built, unit-tested and **live-verified** against the real workspace (below). Backend fast suite and
frontend suites green.

- **Session model**: Flyway `V19__slack.sql` (`slack_enabled`, `slack_channel`, `slack_thread_ts`),
  `SessionEntity`/`SessionRepository` (`attachSlackThread`). Opt-in is the merged-config key
  `slackEnabled` — the same route as `reflectionEnabled` (create dialog override → template →
  `slack.default-enabled` setting), so no `CreateOptions` change. Frontend: a **Slack** checkbox in
  the create dialog (shown when `GET /api/slack/status` says the server has Slack on) and a
  Settings → Slack default toggle. The quick dialog uses the default.
- **Event source**: `SessionEventBus.subscribeAll` (a global subscription, each subscriber isolated so
  it can never break journaling). The mirror drops everything but ten event types at the door.
- **`SlackSessionMirror`**: one single-thread executor, so a session's posts are enqueued in journal
  order. Parent message created on a session's first event (lazy, retried by the next event after a
  30 s cooldown if the post fails; thread persisted via `attachSlackThread`) and edited in place at
  most every 10 s. Thread replies: permission / plan / `AskUserQuestion` prompts (critical), turn
  finished (last assistant text, masked, 600 chars, duration), **fatal** errors only, budget
  reached, PR status change, session closed (cost) plus a white_check_mark reaction on the parent. A
  prompt that is later answered is edited to drop its buttons and say how it ended ("Allowed on
  the dashboard", or "by <@U…>" once Step 3 stamps `source: slack`).
- **Children** follow their *parent's* opt-in (their own flag is ignored), post into the parent's
  thread tagged with their name, get no top-level message. The system session never posts.
- **`SlackOutbox`**: one worker, ≥1.1 s between calls, retries transient errors (honouring
  `Retry-After`, 5 attempts), drops permanent ones with a throttled log line carrying a fix hint
  (`not_in_channel` → invite the bot, …). FIFO of posts bounded at 200: when full the oldest
  *non-critical* post is dropped, critical ones never; parent updates are keyed (one pending per
  session), sent only when the FIFO is empty, and render their content at send time.
- **Masking**: `SlackRedactor` (token shapes, Authorization/Bearer, URL credentials, `NAME=value`
  where the name ends in token/secret/password/key; `max_tokens` is left alone) and
  `SlackToolSummary` (a Bash command; a file edit shows the *path only*; other tools show a few short
  scalar arguments, never content/env fields). `slack.verbose` skips masking.
- **Decisions on contact**: `public-url` is `agentic-ui.slack.public-url` (env `AGENTIC_UI_PUBLIC_URL`)
  to avoid touching `AppProperties`' 21 constructors; the Open button links to the dashboard root
  because there is no per-session deep link; `files:write` failing only costs the full-plan file, not
  the inline part. Prompt buttons are posted now but inert until Step 3.
- **Tests**: `SlackOutboxTest` (9), `SlackRedactorTest` (12), `SlackBlocksTest` (15),
  `SlackSessionMirrorTest` (15, real outbox + bus against a fake Slack). Backend fast suite green;
  frontend `tsc` + 78 vitest green.
- **Live replay** (a synthetic session driven through the real mirror, outbox and Web API into the
  private test channel; the replay harness lives outside the repo): parent card, a Bash permission, a
  file-edit permission, a single-choice question, a plan, a child session, a turn summary, a PR change
  and a close all posted; the parent ended `closed` with the check-mark reaction and no buttons; settled
  prompts lost their buttons and read "Allowed on the dashboard" / "Denied by <@U…>" while keeping the
  masked command; a read-back of the thread found none of the five planted secrets (API token, Bearer
  value, `DB_PASSWORD`, old/new file content). The live run found three things the unit tests could
  not:
  1. `not_in_channel` until the bot was invited — the log hint named the fix.
  2. **`invalid_blocks`: Slack requires every `action_id` in a message to be unique**; the question's
     option buttons all reused `ask_option`. They are now `ask_option_1…n` (Step 3 must match with
     `startsWith`), with a uniqueness test over every builder.
  3. `invalid_blocks` gave no reason in our log; `response_metadata.messages` (Slack's validation
     note, e.g. the failing json-pointer) is now part of the exception message.
- **Not yet verified live**: a fresh backend start with `V19` applied to the real database, and a real
  agent session (the replay used synthetic events); both are the first thing to try with Step 3.
- Slack stores a `>` blockquote marker as `&gt;` in the text it returns; it renders as a quote.

### Step 3 — Inbound (2026-10-17)

Built, unit-tested and live-checked against the real workspace with a fake session control (below).

- **Pending-request tracking** (`session/PendingPermissions`, owned by `SessionService`): the backend
  previously had no notion of which permission requests were still open, so there was nothing to check a
  Slack click against. A request is registered when its `permission_request` arrives (*before* it is
  journaled/published, so nothing reacting to it can win a race with the bookkeeping) and removed when it
  is decided, when the turn ends (an interrupt denies the rest), or when the sidecar exits.
  `respondPermission` (dashboard) stays always-forward as before but now also settles the request;
  `respondPermissionIfPending` (Slack) delivers only if it was still pending and puts the request back if
  the delivery throws. `source`/`slackUser` are journaled with the `permission_response` and stripped
  before the command is sent to the adapter.
- **`SlackInbound`** (a `SlackEvent.Listener`; reaches sessions only through the small
  `SlackSessionControl` interface): allowlist on messages *and* clicks (WARN with the user ID only, never
  text or payload), configured-channel check, no `subtype`/`bot_id`, a thread reply must map to a session
  via `SessionRepository.findBySlackThread` (indexed in `V19`). Clicks: Allow/Deny, Approve plan/Keep
  planning, option buttons and the select (a truncated select value is mapped back to the full label),
  Stop (RUNNING/WAITING_INPUT only), Status (private: state, last activity, cost, PR, waiting count),
  `session_open` ignored. A child's prompt is authorised through its *parent's* opt-in and channel. A
  button whose kind does not match its pending request, or whose value disagrees with its block id, is
  ignored. Stale clicks get a private "already decided" via the interaction's `response_url`
  (`SlackMessaging.respondEphemeral`, hard-limited to `https://hooks.slack.com/`).
- **Questions**: a single-part `AskUserQuestion` is answered with `updatedInput.answers[<question>]`, the
  dashboard card's shape; a text reply in the thread answers it too. Multi-part questions are sent to the
  dashboard with a private note.
- **Feedback that survives the click** (added after the live run): the private "Interrupting…" reply
  vanishes, so a Stop also leaves a visible "Stop requested by <@U…>" thread note (from a `slack_input`
  event), and a delivered thread reply gets an eyes reaction.
- **Tests**: `SlackInboundTest` (25), `SessionStateMachineTest` +8 for the pending set; backend fast suite
  530 green.
- **Live check** (real socket, real Web API, real mirror and inbound; a fake session control that prints
  what it would do — a second backend on the shared dev database was not started because its startup sweep
  would mark the running instance's live sessions CRASHED): Allow, Deny, an option button, Approve plan,
  Status, Stop and a thread reply each produced exactly the expected call with the right author; the bot's
  own posts, the parent message and every `message_changed` (our edits, a user's edit) were ignored; every
  settled prompt was rewritten to "Allowed/Denied/Answered/Plan approved by <@U…>" with no buttons (read
  back from Slack). Real payloads confirmed: clicks carry `response_url` and `channel.id`; thread replies
  carry `thread_ts`.
- **Not yet verified**: a real backend + agent session end to end (the Stop effect, the dashboard-first
  race, restart behaviour); a non-allowlisted user live (no second account available — unit-tested); a
  server-initiated socket refresh.

### Step 4 — Docs and real-session verification (2026-10-17)

- **Docs**: `docs/SLACK.md` (production setup guide: decisions, least-privilege manifest, secret storage,
  rollout, operating, emergency stop, rotation, troubleshooting), `DEPLOY.md` section 8e (short path + manifest),
  `ARCHITECTURE.md` section 3i (as built), `CLAUDE.md` (env row, Settings bullet, docs list), `PROTOCOL.md`
  (`slack_input`, `source`/`slackUser` on `permission_response`), the decision log and phases table.
- **Real-session run** (the backend restarted on the new build, Flyway applied `V19` to the real dev database
  18 -> 19, real Claude sessions on `haiku` driven through the dashboard WebSocket by a script while the
  allowlisted user clicked in Slack): Allow on a real `Bash` prompt, a dashboard-first decision (Slack prompt
  rewritten to "Allowed on the dashboard"), an `AskUserQuestion` answered with a button (the agent replied with the
  chosen option), a thread reply delivered as a prompt (eyes reaction), **Stop on a turn that was streaming**
  (interrupt journaled 10 ms after the click, turn ended, session back to idle, "Stop requested by @user" and
  "Turn interrupted" in the thread), and close (closing note with cost, check-mark reaction, buttons gone).
- **Found by those runs and fixed**: the first scenario used `echo`, which the CLI never gates (no prompt to
  click — a test flaw, not a bug); a restart that silently rotated the dashboard token (my restart procedure,
  not the product); an interrupted turn was labelled "Turn finished" because the SDK reports an interrupt as
  `error_during_execution` (the mirror now tracks `interrupt` events); the closing note showed
  `$0.51796019999999994` (rounded to 4 places, at least 2); a settled question did not show the question or
  the chosen answer; a fatal error after closing an interrupted session was posted with a stack trace and
  local file paths (first line only, and suppressed while closing); and there was no log line saying a click or
  reply had arrived (one INFO line per handled input: user, action, session — never text).
- **Found, not Slack's, and fixed afterwards**: closing a session right after an interrupt made the agent SDK
  raise a fatal `Claude Code returned an error result` from the sidecar. Root cause and fix: see the decision-log
  row "Claude sidecar: shutdown after an error-result turn" in `docs/plan/README.md` (it affected every close or
  idle-park after a turn that ended in an error result, not just Stop).
- **Still unverified live**: a non-allowlisted user, a server-initiated socket refresh, a Slack outage / 429,
  and the Open button (no `AGENTIC_UI_PUBLIC_URL` was set). All covered by unit tests.
- **Cleanup**: five throwaway `slack-e2e-*` branches created by the runs were deleted; the test threads remain in
  the channel.

## Follow-up A — start sessions from Slack (2026-10-17)

Requested after the phase landed: **`@bot review <PR>`** and **`@bot dev <service> <branch> <prompt>`** start a
review / development session. Feasibility was checked against the code first; what shaped the design:

- **Nothing mapped a PR to a local folder.** `gh pr list` runs per local repo (gh infers the repository from the
  folder) and no code read a repo's GitHub remote. Added `GitOpsService.originSlug` (parses SSH/HTTPS remotes) and
  `viewPr` (`gh pr view --repo owner/repo`: state, fork flag, changed files); a PR is matched to the service(s) whose
  `origin` is that repository, and in a monorepo the changed files pick the package.
- **The dashboard's quick start leaves the prompt as an unsent draft and inherits the last session's settings
  (including permission mode and pre-approved tools).** Slack has no draft, so the prompt is sent at once; the
  inheritance is kept as the dashboard has it (see decision 2).
- **The service list had one home, `MetaController`.** Extracted into `git/ServiceCatalog` so the dashboard and Slack
  can never disagree on what a service is (`MetaController` delegates; its tests are unchanged).
- Fork PRs cannot work with the existing review flow (`createReviewWorktree` fetches the head branch from `origin`).

Decisions (asked before building, 2026-10-17):

1. **Trigger: an @mention in the configured channel**, not a slash command. Multi-line prompts are just the rest of
   the message, and no slash-command registration is needed. Costs one scope (`app_mentions:read`) and one event
   (`app_mention`), then a reinstall.
2. **Inherit the last session's settings exactly like the dashboard**, permission mode and allowed tools included
   (the recommendation was to force `default` mode and drop pre-approved tools; declined). Consequence, documented in
   `docs/SLACK.md`: if the last session ran in bypass mode, a Slack-started one does too and asks nothing in the
   thread. The confirmation reply names the model and permission mode the session got.
3. **Start and send immediately** (no confirmation card). Mistakes are caught by validation errors; Stop and close
   are in the thread.
4. **Strict PR handling**: full URL or `owner/repo#N`; error — and nothing started — for no matching clone, a repo
   cloned twice, a closed/merged PR, a fork PR, a monorepo PR spanning several packages or none. Deliberately no
   auto-pick.
5. **A separate switch, `AGENTIC_UI_SLACK_START_ENABLED` (default off)** — not asked for, added because starting a
   session with an arbitrary prompt is a bigger power than approving one and an existing Slack setup should not
   silently gain it; a mention while it is off is answered with how to turn it on.

As built: `SlackStartCommand` (parser), `SlackCommandHandler` (all decisions, on its own virtual thread because
creating a session takes seconds), `SlackSessionStarter` / `SessionServiceSlackStarter` (create like the dashboard,
`slackEnabled` forced on, then send the prompt and journal `slack_input kind=start`), wiring in `SlackInbound`
(allowlist, channel, de-duplicated by envelope id and message ts, eyes reaction, reply in a thread under the mention)
and a "Started from Slack by @user" note from the mirror. A thread reply that mentions the bot is a command, never a
prompt for that session. Tests: `SlackStartCommandTest` (9), `SlackCommandHandlerTest` (19), mention cases in
`SlackInboundTest`/`SlackSessionMirrorTest`, `GitOpsServiceTest` for remote and PR parsing.

**Live status (verified 2026-10-17, real workspace, real GitHub PR).** `@bot review <PR>` on a throwaway draft PR started a
review session linked to it ("Started from Slack by @user", PR status notice, `submit_pr_review` approved in the thread —
GitHub first refused `REQUEST_CHANGES` on one's own PR and the agent retried as `COMMENT`, which posted); `@bot dev
agentic-ui slack-start-test <multi-line prompt>` started a development session and answered; the four error cases (no
matching clone, unknown service, `-rf` as a branch, `help`) were each answered clearly and started nothing. The throwaway
PR, its remote and local branches and both sessions were closed/deleted afterwards.

**Improvement from that run (the requester's suggestion):** the first `dev` attempt had no prompt, got an error, and the
user then typed the prompt as a reply in the error thread — which went nowhere, so the whole command had to be re-sent.
Now a `dev <service> <branch>` without a prompt (service and branch valid) makes the bot ask in the thread, mentioning the
requester; their next reply there is the prompt, `cancel` abandons it (`SlackPendingStarts`: in memory, 15 minutes, only the
asker, capacity 50, only for a top-level mention so replies inside a session thread keep meaning "prompt for that session",
and a reply that mentions the bot is a command, not the answer). The service and branch are validated *before* asking.

**Follow-up B — templates: `review-with` and `dev-with` (2026-10-17).** Requested after the run above: two more commands
that take a **session template name first** (`dev-with <template> <service> <branch> [prompt]`, `review-with <template>
<PR> [extra]`). They are separate commands because an optional template word in `dev` could not be told apart from the
start of a prompt — and a missing prompt is now legitimate (it is asked for in the thread).

What was checked first: templates are `name` (unique) + `config` (the same keys as a session's overrides) + linked
skill/agent assets; the dashboard's quick start applies one by dropping the keys it sets from the inherited last-session
config (so the template wins) and its asset lists (so the template's are merged server-side), uses its `baseBranch`
(`PR base ?? template ?? default` for a review), and **blanks its kickoff prompt** — whereas `SessionService.create`
itself enqueues a non-blank kickoff, so leaving it would have fired a template message on top of the typed one. There
were no templates in the database to learn from.

Decision (asked, 2026-10-17): **ignore the template's kickoff prompt, like the dashboard quick start.** The other
options (send it first and fail on unfilled `{{placeholders}}`; or fill them from `key=value` words) were declined as
more to learn. So a template contributes settings, assets, instructions and base branch; standing guidance belongs in
its *instructions*. Also decided without asking, following the dashboard: template over inherited settings, key by key;
unique name or prefix, case-insensitive; names with spaces in quotes (`"`, curly quotes, `` ` `` or `'`); unknown names
list the available ones and `help` lists them; the template is checked before the service or the PR (so a typo costs no
GitHub call). A useful consequence recorded in `docs/SLACK.md`: a template that sets `permissionMode` is how to pin the
permission mode of Slack-started sessions despite the "inherit like the dashboard" choice.

As built: `SlackStartCommand` (`Review`/`Dev` carry an optional template; `withTemplate` parses the quoted name),
`SlackSessionStarter.templates()`/`TemplateInfo`, `StartRequest.templateId`, `SlackCommandHandler.resolveTemplate`
(and the template carried through `AwaitedPrompt` so the later prompt still starts from it),
`SessionServiceSlackStarter.buildOverrides` (static, unit-tested). Tests: parser (quoted names, missing parts),
handler (resolution, base-branch precedence, ordering of checks, help), `SessionServiceSlackStarterTest` (override rules),
and an end-to-end mention in `SlackInboundTest`.

**Follow-up C — a Close button (2026-10-17).** Asked as a question ("should we also add a Close button?"); answered first with
an assessment, built after the user agreed to the defaults. Reasons: a Slack-started session could otherwise be ended only on the
dashboard; parking frees the process but not the worktree; a review session is one-shot. What closing does, from
`SessionService.close`: stops the sidecar, removes the worktree (the branch stays), is irreversible, and — with the default
`dirty=fail` — throws `DirtyWorktreeException` and touches nothing when there are uncommitted changes; commit/stash/discard are the
modes that can change or lose work.

Decisions (agreed): **clean close only** (no stash/discard from a phone; "Stash & close" can follow if wanted), **refused while a turn
is running or waiting** (closing mid-turn cuts it off after ~7 s and leaves half-finished edits that would block the clean close
anyway), **refused while child sessions are live** (a parent close leaves children running today), confirmation dialog like Stop,
audit `slack_input kind=close` + a "Close requested by @user" note, all refusals said privately **and** in the thread (the lesson of the
Stop episode: a private reply alone is easy to miss). As built: `SlackBlocks.SESSION_CLOSE`, `SlackSessionControl.closeClean`,
`SlackInbound.close` / `refuseClose` (the close runs via `SlackCommandHandler.runInBackground`). Tests: button + confirm, close of an
idle/parked/crashed session, refusal while running/waiting, live vs finished children, dirty worktree (first 8 files + "and N more"),
other failures, already closed, non-allowlisted.

