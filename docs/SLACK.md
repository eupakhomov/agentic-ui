# Slack integration — production setup guide

Mirrors sessions into one Slack channel (a session = a top-level message plus its thread), accepts
decisions back from Slack (tool permissions, plan approval, `AskUserQuestion` answers, thread replies as
prompts, Stop / Status / Close) and — if you switch it on — lets you start review and development
sessions by mentioning the bot. Design and decisions: [`plan/phase-18-slack.md`](plan/phase-18-slack.md).
Base deployment (macOS, `start.sh`, launchd): [`DEPLOY.md`](DEPLOY.md).

**Status (phase 18 landed):** an opted-in session gets a parent message and a thread
of permission/plan/question prompts, turn summaries, errors, PR changes and a final message (outbound),
and the people on the allowlist can act from Slack (inbound): **Allow / Deny** a tool, **Approve plan /
Keep planning**, answer a question with a button or a reply, **reply in the thread** to send the session a
prompt, and use **Stop** / **Status** / **Close** on the parent message. Optionally (own switch), **mention the bot** to start a
review or development session, from a template if you like. Decisions work in both places: whichever
of the dashboard and Slack answers first wins, and the other side is told it was already decided. Not
yet exercised: a non-allowlisted user, a Slack outage and a server-initiated socket refresh
(unit-tested; the rest was verified against a real workspace and real agent sessions — see the phase 18 log).

Off by default. With `ARTUS_SLACK_ENABLED` unset or `false`, no Slack code runs.

## Read this first: what you are enabling

Slack becomes a **second remote control** for the machine the backend runs on.

- An **Allow** click in Slack lets an agent run a command or edit files on that machine, exactly
  like Allow on the dashboard. Whoever controls an allowlisted Slack account can do that from
  anywhere, with no second factor of our own.
- Everything mirrored into the channel — prompts, tool commands, plan text, file paths — is stored
  by Slack under your workspace's retention and export rules, and is visible to everyone in the
  channel and to workspace owners/admins who can export data.
- With `ARTUS_SLACK_START_ENABLED`, an allowlisted account can also **start sessions with an arbitrary prompt**. They
  inherit the settings of your last session — including `bypassPermissions` and pre-approved tools, on purpose — and then
  nothing is asked in the thread. A template that sets `permissionMode` pins a safer mode (see below).
- Secret masking on tool inputs is best-effort. It is not a guarantee that no secret
  ever reaches Slack.

So the production posture is: a **private channel with only you in it**, an **allowlist of only
you**, **two-factor or SSO enforced** on the Slack account, and the **emergency stop** below known
before you need it.

## Decisions to make before you start

| Decision | Recommendation |
|---|---|
| Which workspace | The one you actually work in, if you control it or your admin has approved the app. Check whether it requires **admin approval for custom apps** (Workspace settings → Apps); request it first, it can take days. |
| Channel | A **private** channel created for this, with only you (and any other operator you deliberately trust) in it. No guests, no Slack Connect, no other bots or integrations added to it. |
| App per environment | **One app per backend.** Create a separate `Artus (dev)` app for testing. Slack spreads events across *all* open Socket Mode connections of one app, so a dev backend and a production backend sharing an app token randomly steal each other's messages and button clicks. |
| Who is on the allowlist | You, alone, to start. Each added person can approve commands on your machine. |
| Where secrets live | Not in a committed file, a chat, or a screenshot. See [Secrets](#4-store-the-secrets). |
| Distribution | **Never distribute the app** (leave *Manage Distribution* off / `org_deploy_enabled: false`). It is an internal app for one workspace. |

## 1. Create the Slack app

api.slack.com/apps → **Create New App** → **From a manifest** → pick the workspace → **JSON** tab.

This manifest is the least-privilege setup for a **private channel** (the recommended one):

```json
{
  "display_information": {
    "name": "Artus",
    "description": "Mirrors Artus coding sessions into a private channel and takes approvals back."
  },
  "features": {
    "bot_user": { "display_name": "Artus", "always_online": false }
  },
  "oauth_config": {
    "scopes": {
      "bot": ["chat:write", "groups:history", "reactions:write", "files:write", "app_mentions:read"]
    }
  },
  "settings": {
    "event_subscriptions": { "bot_events": ["message.groups", "app_mention"] },
    "interactivity": { "is_enabled": true },
    "socket_mode_enabled": true,
    "org_deploy_enabled": false,
    "token_rotation_enabled": false
  }
}
```

Why each permission is there. A **scope** is a permission the bot is granted; an **event** is a
notification Slack sends to the app. Events are what *wake* the app, scopes are what it is *allowed
to do*, and an event only arrives if its matching scope is granted:

| Name | Type | Set under | Used for |
|---|---|---|---|
| `chat:write` | Bot scope | OAuth & Permissions → Bot Token Scopes | Posting and updating the session messages |
| `groups:history` | Bot scope | OAuth & Permissions → Bot Token Scopes | Permission to read messages in the **private** channel; required for `message.groups` to be delivered |
| `message.groups` | Bot event | Event Subscriptions → Subscribe to bot events | Slack tells the app about each new message in a private channel it belongs to; thread replies become prompts |
| `reactions:write` | Bot scope | OAuth & Permissions → Bot Token Scopes | The ✅ on a closed session, and the 👀 on a reply or mention that was received |
| `files:write` | Bot scope | OAuth & Permissions → Bot Token Scopes | Uploading a plan longer than ~12,000 characters as a file in the thread (the first part stays inline). Safe to drop: the file upload then fails with `missing_scope` in the log and only the inline part is shown |
| `app_mentions:read` | Bot scope | OAuth & Permissions → Bot Token Scopes | Permission to be told when someone mentions the bot; required for `app_mention`. Only needed to [start sessions from Slack](#starting-a-session-from-slack) — drop it and the event if you will not use that |
| `app_mention` | Bot event | Event Subscriptions → Subscribe to bot events | Slack tells the app "someone mentioned the bot in a channel it is in": the `review …` / `dev …` commands |

Button clicks need neither a scope nor an event: **Interactivity** (a switch, not a scope) makes
Slack send them over the same Socket Mode connection.

**Public channel instead?** Replace `groups:history` with `channels:history` and `message.groups`
with `message.channels`. Don't add both sets "just in case" — each one widens what the app can read.

For an app that already exists: left sidebar → **App Manifest** → paste → **Save Changes**, then
**Reinstall to Workspace** if Slack says the scopes changed.

### Settings by hand (what the manifest sets)

| Setting | Where in the app's sidebar | Value |
|---|---|---|
| Socket Mode | Settings → **Socket Mode** | on |
| Interactivity | Features → **Interactivity & Shortcuts** | on; no request URL in Socket Mode |
| Event subscriptions | Features → **Event Subscriptions** | **Enable Events** on; bot events `message.groups` (or `message.channels`) and, to start sessions from Slack, `app_mention` |
| Bot scopes | Features → **OAuth & Permissions** → **Bot Token Scopes** | as in the table above |

### Generate the two tokens

| Token | Starts with | Env var | How |
|---|---|---|---|
| App-level token | `xapp-` | `ARTUS_SLACK_APP_TOKEN` | **Basic Information → App-Level Tokens → Generate Token and Scopes**, scope `connections:write` only. Opens the Socket Mode connection. |
| Bot token | `xoxb-` | `ARTUS_SLACK_BOT_TOKEN` | **OAuth & Permissions → Install to Workspace**, approve, then copy **Bot User OAuth Token**. Posts and reacts. |

Copy each one straight into your secret store (step 4). Do not paste a token into a chat, ticket,
or terminal you are recording.

## 2. Create the channel and collect two IDs

1. Create the private channel and invite the bot: `/invite @Artus`. The bot must be a member,
   or Slack sends it no message events and it cannot post.
2. **Channel ID** — channel name at the top → **About** → bottom of the dialog. Starts with `C`
   (older private channels: `G`). Use the ID, not the name.
3. **Your member ID** — your profile → **⋯** → **Copy member ID**. Starts with `U`.

Also, in the Slack admin settings, make sure your account has **two-factor authentication or SSO**
enforced: it is now one of the ways into your machine.

## 3. Pick a restrictive starting configuration

First run with the smallest blast radius: allowlist = only you, mirroring off for everything but one
test session, `VERBOSE` left `false`. Mirroring is a per-session choice: a **Slack** checkbox in the
new-session dialog (shown only when the server has Slack enabled), whose default is **Settings → Slack →
Mirror by default** (off). A session spawned by another session follows its parent's choice.

| Env var | Default | Meaning |
|---|---|---|
| `ARTUS_SLACK_ENABLED` | `false` | Master switch. Off = no Slack beans, no connection |
| `ARTUS_SLACK_BOT_TOKEN` | — | `xoxb-…` bot token (**secret**) |
| `ARTUS_SLACK_APP_TOKEN` | — | `xapp-…` app-level token (**secret**) |
| `ARTUS_SLACK_CHANNEL` | — | Channel **ID** sessions are mirrored into |
| `ARTUS_SLACK_ALLOWED_USERS` | empty | Comma-separated member IDs allowed to act. **Empty = nobody**; messages and clicks from anyone else are ignored and logged |
| `ARTUS_SLACK_START_ENABLED` | `false` | `true` lets an allowlisted user start review/development sessions by mentioning the bot ([details](#starting-a-session-from-slack)). A bigger power than approving, so separate from the allowlist |
| `ARTUS_SLACK_VERBOSE` | `false` | `true` posts tool inputs and message excerpts **unmasked** — leave off |
| `ARTUS_PUBLIC_URL` | — | Base URL for the **Open** button on the parent message, e.g. your Tailscale hostname (the link opens the dashboard; there is no per-session deep link yet). Must be reachable only over a private network (Tailscale/VPN), never the open internet. Unset = no Open button |

The allowlist is read at startup only, so adding or removing a person needs a restart.

## 4. Store the secrets

The tokens must reach the backend's environment and nowhere else. Pick one:

**A. Keychain (recommended on macOS).** Nothing readable on disk:

```bash
security add-generic-password -U -a "$USER" -s artus-slack-bot -w   # prompts for the token
security add-generic-password -U -a "$USER" -s artus-slack-app -w
```

Then in your shell's startup file (`~/.zshrc` for zsh, `~/.bashrc` for bash — see
[Which startup file](#which-startup-file) below):

```bash
export ARTUS_SLACK_ENABLED=true
export ARTUS_SLACK_BOT_TOKEN="$(security find-generic-password -a "$USER" -s artus-slack-bot -w)"
export ARTUS_SLACK_APP_TOKEN="$(security find-generic-password -a "$USER" -s artus-slack-app -w)"
export ARTUS_SLACK_CHANNEL=C0123456789
export ARTUS_SLACK_ALLOWED_USERS=U0123456789
```

**B. A locked-down env file.** Keep the secrets in `~/.config/artus/slack.env` (owner-only:
`chmod 700` on the folder, `chmod 600` on the file, never inside a repository), containing the same
`export …` lines, and add `source ~/.config/artus/slack.env` to your shell's startup file.

**C. Plain `export` lines in your shell's startup file.** Acceptable on a single-user machine with
disk encryption (FileVault); the tokens are then readable by anything that runs as you.

#### Which startup file

The variables must exist in the environment of whatever process runs `./start.sh`:

| Started from | zsh | bash |
|---|---|---|
| An interactive terminal | `~/.zshrc` | `~/.bashrc` (macOS Terminal opens bash as a *login* shell, which reads `~/.bash_profile` or `~/.profile` instead — make that file `source ~/.bashrc`) |
| Anything non-interactive: an IDE, a launcher, cron | `~/.zshenv` | `~/.bash_profile` / `~/.profile` do **not** apply to non-login, non-interactive shells; set the variables in the launcher itself, or in a wrapper script that sources your env file |
| The launchd agent from [DEPLOY.md](DEPLOY.md#start-at-login-optional) | neither shell file is read | put them in the agent's `EnvironmentVariables`, or have the agent run a wrapper script that loads them (see below) |

The rest of this guide says "your shell's startup file" for the first row.

Whichever you pick:

- A launchd agent keeps its environment in the plist, so the plist then holds the secrets:
  `chmod 600` it, keep it out of backups you sync to the cloud, and prefer a small wrapper script
  that reads the tokens from Keychain over pasting them into the plist.
- `application-local.yaml` is only read when the Spring profile `local` is active
  (`SPRING_PROFILES_ACTIVE=local`); nothing here activates it. Environment variables are simpler.
- Never put tokens in the Settings dialog, a committed file, or the process arguments.

Apply with `source <your startup file>` (or a new terminal) and restart the backend with
`./start.sh` — a running process keeps the environment it started with.

## 5. Verify

Check the environment without printing secrets:

```bash
env | grep -c ARTUS_SLACK                                  # 5 (6 with START_ENABLED)
env | grep ARTUS_SLACK_BOT_TOKEN | cut -c1-40              # starts with xoxb-
```

Start the backend and read the log (`logs/artus.log`, or `tail -f /tmp/artus.log`):

```
slack: bot 'Artus' in workspace '<your workspace>'      ← bot token valid
slack socket: connected
slack socket: ready                                          ← Slack accepted the app token
```

Then create a session with the **Slack** checkbox ticked: within a few seconds the channel gets a
top-level message for it, and the thread fills as the session works (a Bash permission prompt shows the
command with secrets masked). Click **Allow** — the prompt is rewritten to "Allowed by @you" and the
session continues; reply in the thread — your message gets an eyes reaction once the session has it.
Decide one prompt on the dashboard and check that its Slack message is rewritten to say so. Finally
ask a **second, non-allowlisted** account to reply or click: it must be ignored, with only a WARN line
(its user ID, never its text) in the backend log.

### Rollout order

1. Install on a **dev app** first and run the full manual test script in the phase 18 plan.
2. Create the **production app** from the same manifest; new tokens, private channel, allowlist = you.
3. Enable on one session, watch the thread behave, then widen. Keep the dashboard open next to it
   the first few times: a decision made in either place must show up in the other.

## Starting a session from Slack

An allowlisted user can start a session by **mentioning the bot** in the configured channel. This is a
bigger power than approving something, so it has its own switch: `ARTUS_SLACK_START_ENABLED=true`
(off by default; while it is off a mention is answered with a note saying so). It also needs the
`app_mentions:read` scope and the `app_mention` event on the Slack app (see the manifest above) and a
reinstall.

```
@Artus review https://github.com/acme/api/pull/123
@Artus review acme/api#123 please focus on the auth changes
@Artus dev billing-service feat/rate-limit Add rate limiting to the login endpoint.
Per user and per IP; tests included.
@Artus dev billing-service feat/rate-limit      <- no prompt: the bot asks for it in the thread
@Artus dev-with bugfix billing-service fix/npe Fix the NPE when the email is empty
@Artus review-with "Deep review" acme/api#123
@Artus help
```

**`review <PR>`** takes a full pull-request URL or `owner/repo#123`, and optionally extra instructions that
are added to the built-in review instruction. It finds the local service by matching the PR's repository to
each service's `origin` remote, then starts a **review session** on the PR's branch (a detached checkout, so a
development session on the same branch is not disturbed) with a first message asking for a review and a
submission through `submit_pr_review` — which, like every sensitive step, asks for approval in the thread.
It reports an error, and starts nothing, when:

- no local service is a clone of the PR's repository, or the repository is cloned twice;
- the pull request is closed or merged (only open ones are reviewed);
- it comes from a fork (the branch is not in the local clone; not supported yet);
- in a monorepo, the changed files span several packages, or fall in none (it will not pick one for you);
- GitHub cannot be reached or the PR does not exist.

**`dev <service> <branch> <prompt>`** takes a service name (exact, any case, or a unique prefix — an
ambiguous or unknown name lists what is available), a branch (created from `main`, else `master`, else the
first branch; an existing branch is checked out, as in the dashboard), and **everything after the branch as
the prompt**, newlines included. The prompt is sent as soon as the session is ready.

**Leave the prompt out** and the bot checks the service and branch, then asks in a thread under your message,
mentioning you: *"what should the session do on `billing-service` (branch `feat/rate-limit`)? Reply in this thread
with the prompt, or `cancel`."* Your next reply in that thread is the prompt (a multi-line reply is fine; do not mention
the bot in it). Only the person who asked can answer, the question is forgotten after 15 minutes and on a restart, and
it is only offered for a mention made outside a session thread — inside one, your replies already mean "a prompt for
that session", so a missing prompt is an error there.

Either command: the bot reacts with an eyes reaction, answers in a thread under your message once the session
exists (or with the reason it could not be started), and the new session gets its own thread in the channel
like any other mirrored session, starting with "Started from Slack by @you". Starting is refused with a clear
message when `ARTUS_MAX_SESSIONS` is reached.

**Starting from a template: `dev-with` and `review-with`.** The same two commands with the **template name first**
(`dev-with <template> <service> <branch> [prompt…]`, `review-with <template> <PR> [extra instructions]`). They are
separate commands, rather than an optional word in `dev`, so a template name can never be mistaken for the start of a
prompt. The name matches exactly (any case) or by unique prefix; a name with spaces goes in quotes (`"Bug fix"`,
`“Bug fix”` or `` `Bug fix` ``). An unknown name lists the templates there are, and `help` shows them too. A missing
prompt in `dev-with` is asked for in the thread just like in `dev`.

What a template contributes is exactly what it contributes in the dashboard's **quick start**:

- its settings (model, permission mode, tools, MCP servers, instructions, thinking, budget, …) **win over what would
  otherwise be inherited from your last session**, key by key — what the template does not set is still inherited;
- its linked **skills and agents** are used instead of the inherited ones;
- its **base branch** is used for `dev-with` (for `review-with` the pull request's own base branch always wins);
- its **kickoff prompt is not sent**: the first message is what you typed (or the built-in review instruction), as in the
  dashboard quick start. Put standing guidance in the template's *instructions*, which do apply.

A template is also the way to **pin the permission mode** of sessions started from Slack: a template that sets
`permissionMode` overrides whatever your last session happened to use, so `dev-with safe …` is always as safe as that
template says, whatever you were doing before.

**What the new session inherits.** Exactly what the dashboard's quick start gives: the settings of the most
recent session — model, **permission mode, allowed tools**, MCP servers, skills, instructions, environment
variables. If your last session ran in `bypassPermissions` mode or with tools pre-approved, so does the one you
start from Slack, and then nothing is asked in the thread. The confirmation names the model and permission mode
it got; keep an eye on it. Allowing even a bypass session to be started from Slack is intentional. To pin a safer mode
for some starts, use a template that sets `permissionMode` (`dev-with`, `review-with`).

## Operating it

- **Reconnects.** Slack recycles Socket Mode connections every few hours; the client reconnects on
  its own and drops duplicates. If the machine sleeps or the network drops, events sent meanwhile
  are **not replayed**; the parent message's **Status** button is the recovery path (a private reply
  with state, cost, last activity, PR and how many requests are waiting for you).
- **Slack outages never block a session.** Posting is queued with retry/backoff (honouring
  `Retry-After`, about one message per second); under pressure the oldest chatter is dropped, but
  permission, plan and question messages never are. A first post that fails is retried by the next event
  (after 30 s), so a Slack outage at creation does not leave a session without a thread.
- **One backend per app.** Two backends on one app split events at random (see the decision table).
  Multiple backends sharing a channel are out of scope.
- **What to watch.** `grep -i slack logs/artus.log` for connection warnings, `invalid_auth`,
  `missing_scope`, `not_in_channel`, and `ignoring … user` lines for people poking at the bot.
- **Audit.** A decision made in Slack is journaled as a `permission_response` with `source: slack` and
  the Slack user ID; a prompt, a Stop, a Close, or the command that started a session adds a `slack_input` event (who and what, not the
  text — the text is the `user_message`). The Slack prompt itself is rewritten to name who decided.
- **Stop.** The button interrupts a turn that is running (or waiting on a prompt); on an idle session it just says so,
  privately. A running turn ends within a moment, the thread gets "Stop requested by @you" and "Turn interrupted",
  and the session is idle again. If nothing was running, that is said in the thread too (not only privately), because a private
  reply is easy to miss.
- **The card stays current.** The parent message is edited in place: a change in what you can do — now idle, waiting for
  you, parked, crashed or failed — is shown at once; minor changes at most every 10 s.
- **Close.** The button closes the session and removes its worktree (the branch is kept; a closed session cannot be reopened),
  after a confirmation. It closes **cleanly or not at all**: if the worktree has uncommitted changes nothing happens and the
  thread lists the changed files — commit, stash or discard them from the dashboard, where those choices belong. It is also
  refused (privately *and* in the thread, so it is not missed) while a turn is running or waiting for you (press Stop first)
  and while the session still has live child sessions (close those first). A parked or crashed session can be closed. On
  success the thread says who asked, then the usual "Session closed — cost …" and the ✅, and the card loses its buttons. If
  reflection is on for that session, closing starts its memory reflection as usual (to approve on the dashboard).
- **What replies do.** Only a plain reply inside a mirrored session's thread, in the configured channel,
  from an allowlisted user, is ever a prompt. Top-level messages, edits, deletions and bot posts are
  ignored. A reply to a thread whose session has a pending single-part question answers it. A reply
  in a parent session's thread always goes to the parent, not to a child.

### Emergency stop

If you suspect the Slack account, a token or the channel is compromised, in this order:

1. **Slack → your app → Basic Information → App-Level Tokens → delete the token.** The socket drops
   and cannot reconnect: inbound control is cut immediately, no restart needed.
2. **OAuth & Permissions → revoke the bot token** (or uninstall the app): outbound posting stops.
3. On the machine: set `ARTUS_SLACK_ENABLED=false` and restart. (To keep approvals but stop remote *starts* only, set
   `ARTUS_SLACK_START_ENABLED=false` and restart.)
4. Review the session journal for decisions with `source: slack` since the suspected time.
5. Rotate everything before re-enabling.

## Maintenance

### Rotating a token

Rotate on a schedule (every 90 days is reasonable), whenever a token was pasted anywhere it
shouldn't have been, and whenever someone who had access leaves.

1. **App token:** Basic Information → App-Level Tokens → generate a new one (`connections:write`),
   update your secret store, restart, confirm `slack socket: ready`, then delete the old one.
2. **Bot token:** a *different* value requires revoking the old one (Install App → revoke, or
   uninstall and reinstall). The window between revoke and updating the secret store is a gap in
   posting, so do it with no session waiting on a Slack approval.
3. Update the secret (Keychain: `security add-generic-password -U …`; env file: edit it; plain
   startup file: edit the line), `source` it, and `./start.sh`.
4. Verify with the `cut -c1-40` check above — never print the whole value.

### Adding or removing an operator

Edit `ARTUS_SLACK_ALLOWED_USERS` and restart. Removing someone also means removing them from the
private channel. Review the list whenever your team changes.

### Updating the app's permissions

Change the manifest, **Reinstall to Workspace** (the token value normally stays the same), and
restart only if the env changed. Adding a scope later never needs a code change, but removing one a
feature relies on will show up as `missing_scope` in the log.

## Troubleshooting

| Symptom (log or behaviour) | Cause and fix |
|---|---|
| `slack: enabled but bot-token/app-token are not both set … not connecting` | A token variable is empty in the backend's environment. Was it exported *before* `./start.sh`, in a file the launching process actually reads (see [Which startup file](#which-startup-file): interactive terminal vs IDE/launchd)? |
| `bot token check failed (slack auth.test failed: invalid_auth)` | Wrong, revoked or truncated `xoxb-` token. Re-copy it from **OAuth & Permissions**. The socket still runs; posting will not work. |
| `connection failed: slack apps.connections.open failed: invalid_auth` | The `xapp-` token is wrong, deleted, or lacks `connections:write`. |
| `…: not_allowed_token_type` | Bot token and app token are swapped. |
| `slack: no channel configured` | `ARTUS_SLACK_CHANNEL` is empty. |
| `slack: allowed-users is empty — every Slack message and button click will be ignored` | Set `ARTUS_SLACK_ALLOWED_USERS` to your member ID. |
| Connected, but channel messages produce no events | The bot is not in the channel, or the `message.groups` / `message.channels` subscription doesn't match the channel type. Reinstall after changing events or scopes. |
| A mention gets no reaction and no answer | The `app_mentions:read` scope or the `app_mention` event is missing from the Slack app (add both, **Reinstall to Workspace**); or the mention was in another channel, or from a user not on the allowlist (WARN in the log). The bot must be a member of the channel. |
| A mention is answered "Starting sessions from Slack is turned off" | Set `ARTUS_SLACK_START_ENABLED=true` and restart. |
| `review` says no local service matches the pull request | The service's `origin` remote is not that GitHub repository (`git -C <service> remote get-url origin`), or it is not under the ecosystem root (Settings → Sessions). |
| Stop says "nothing was running" / Close says "not closed" | Working as designed: Stop only interrupts a running turn; Close needs an idle session with a clean worktree and no live child sessions (the thread says which). Check the card — it shows the current state. |
| I answered the bot's "what should the session do?" and nothing started | The question is forgotten after 15 minutes (and on a restart); only the person who asked can answer, in that thread; the reply must not mention the bot. Ask again with a new mention. |
| Events arrive intermittently, buttons "do nothing" (no effect, no error) | A second process (a dev backend, an old instance) holds a connection with the same app token. One app per backend; check `pgrep -fl agentic` and the Slack app's Socket Mode connection count. |
| `…: not_in_channel` *(when posting)* | `/invite @Artus` in the channel. |
| `…: channel_not_found` *(when posting)* | Wrong channel ID, or a private channel the bot was never invited to. |
| `…: missing_scope` | Add the scope in **OAuth & Permissions**, then **Reinstall to Workspace**. |
| `…: ratelimited` *(when posting)* | Normal under bursts; the queue retries with `Retry-After`. Persistent means something is looping — check the log. |
| App won't install: "needs admin approval" | Your workspace restricts custom apps. Submit the request from the install screen and wait for an admin. |

## Turning it off

Set `ARTUS_SLACK_ENABLED=false` (or unset it) and restart. To remove access entirely: delete the
app-level token, revoke or uninstall the bot, remove the Keychain items / env file, and delete the
private channel if you no longer want the history in Slack.
