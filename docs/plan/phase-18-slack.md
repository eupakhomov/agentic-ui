# Phase 18 — Slack integration (session = thread)

Status: **proposed (2026-10-17)**. Nothing is implemented. Split out of the Phase 17 draft after
review so Phase 17's DoD doesn't wait on an external app setup. Depends on nothing in Phase 17.

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
5. **Library: `slack-api-client` only (Web API types + its Socket Mode client), no Bolt.**
   Evaluate in Step 1; if its Socket Mode client's WebSocket backend doesn't sit well with Boot 4
   / JDK 25, implement Socket Mode on `java.net.http.WebSocket` directly — the protocol is one
   `apps.connections.open` call, one WebSocket, envelopes in, `{envelope_id}` acks out.
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
  public-url: ${AGENTIC_UI_PUBLIC_URL:}            # e.g. https://mac.tailnet.ts.net:8080 — Open links; omitted when unset
  slack:
    enabled: false
    bot-token: ${AGENTIC_UI_SLACK_BOT_TOKEN:}      # xoxb-…
    app-token: ${AGENTIC_UI_SLACK_APP_TOKEN:}      # xapp-… (Socket Mode)
    channel: ${AGENTIC_UI_SLACK_CHANNEL:}          # channel ID, not name
    allowed-users: []                              # Slack user IDs
    verbose: false                                 # true = no secret masking in tool inputs
```

Persisted settings (Settings dialog → "Slack"): `slack.default-enabled` (default off).

Slack app: scopes `chat:write`, `channels:history` (or `groups:history` for a private channel),
`reactions:write`, `files:write` (plan snippets). Events: `message.channels` / `message.groups`.
Interactivity on. Socket Mode on. The app manifest goes in `DEPLOY.md`.

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

- Creating sessions from Slack, slash commands, DMs, multiple channels, a channel per project.
- Edit-before-allow from Slack.
- Multiple backend instances sharing one channel.
- Replaying inbound events missed during a disconnect.

## Definition of Done

- [ ] A session with Slack enabled gets one top-level message and a thread; the parent updates on
      state/PR change; close posts a final message and ✅.
- [ ] Allow/Deny, Approve plan/Keep planning, and `AskUserQuestion` buttons steer the session;
      a decision already made on the dashboard is reported, not re-sent, and vice versa.
- [ ] A thread reply becomes a prompt exactly once, including under redelivery.
- [ ] Non-allowlisted users (messages and buttons) are ignored and logged; message subtypes and
      bot messages are ignored.
- [ ] Tool inputs are masked by default; `envVars` never appear.
- [ ] A Slack outage or `429` never blocks or fails a session; permission messages are never
      dropped by the queue.
- [ ] The journal records `source`/`slackUser` on Slack-originated decisions and prompts.
- [ ] Tests and build pass; docs and the decision log are updated.

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

## Implementation log

_Not started._
