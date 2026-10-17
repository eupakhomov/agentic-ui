package de.pamir.agentic.ui.slack;

import de.pamir.agentic.ui.journal.EventJournal;
import de.pamir.agentic.ui.session.DirtyWorktreeException;
import de.pamir.agentic.ui.session.PendingPermissions.Pending;
import de.pamir.agentic.ui.session.SessionEntity;
import de.pamir.agentic.ui.session.SessionRepository;
import de.pamir.agentic.ui.session.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Inbound half of the Slack integration (phase 18 Step 3): turns thread replies and button clicks
 * from Slack into the same operations the dashboard performs — answer a permission, approve a plan,
 * answer a question, send a prompt, stop a turn — and nothing more.
 *
 * <p><b>Who may act.</b> Only Slack user IDs in {@code allowed-users}; the list defaults to empty,
 * which accepts nobody. The check covers messages and button clicks alike (a click payload carries
 * its own {@code user.id}). Anyone else is ignored and logged at WARN with their user ID, never
 * with message content. Only the configured channel is considered, only plain user messages in a
 * thread that belongs to a session (no {@code subtype}, no {@code bot_id}), and only sessions that
 * are actually mirrored.
 *
 * <p><b>Who wins.</b> A decision is delivered only if the request is still pending
 * ({@link SlackSessionControl#respondIfPending}); otherwise the clicker is told, privately, that it
 * was already decided. The other direction — a dashboard decision after the Slack message was posted
 * — is the mirror's job: it edits the Slack message when the {@code permission_response} event arrives.
 *
 * <p>Deliveries reach {@link #onEvent} already acknowledged and de-duplicated, one at a time and in
 * order (see {@link SlackEnvelopeProcessor}); handlers must stay quick.
 */
@Component
@ConditionalOnProperty(prefix = "artus.slack", name = "enabled", havingValue = "true")
public class SlackInbound implements SlackEvent.Listener {

	private static final Logger log = LoggerFactory.getLogger(SlackInbound.class);
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final int CLOSE_FILES_SHOWN = 8;

	static final String ALREADY_DECIDED = "That request was already decided — on the dashboard, or by someone else.";
	static final String KEEP_PLANNING = "Keep planning; do not execute yet.";

	/** Slack wraps links as {@code <https://x|label>}; a prompt should carry the plain URL. */
	private static final Pattern LINK = Pattern.compile("<((?:https?://|mailto:)[^|>]+)(?:\\|[^>]*)?>");

	private final SlackProperties props;
	private final SlackSocket socket;
	private final SlackOutbox outbox;
	private final SlackMessaging api;
	private final SessionRepository sessions;
	private final SlackSessionControl control;
	private final Function<UUID, BigDecimal> costToDate;
	private final SlackCommandHandler commands;
	// a mention that Slack delivers twice must never start two sessions; the envelope de-duplication
	// should already prevent it, this is the second line of defence
	private final SlackEnvelopeProcessor.RecentKeys handledMentions = new SlackEnvelopeProcessor.RecentKeys(256);
	// `@bot dev <service> <branch>` without a prompt: the requester's next reply in that thread is the prompt
	private final SlackPendingStarts pendingStarts = new SlackPendingStarts(java.time.Clock.systemUTC());

	@Autowired
	public SlackInbound(SlackProperties props, SlackSocket socket, SlackOutbox outbox, SlackMessaging api,
						SessionRepository sessions, SlackSessionControl control, EventJournal journal,
						SlackSessionStarter starter) {
		this(props, socket, outbox, api, sessions, control, journal::costToDate,
				new SlackCommandHandler(starter, java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()));
	}

	/** Test seam: no socket, no real journal. */
	SlackInbound(SlackProperties props, SlackSocket socket, SlackOutbox outbox, SlackMessaging api,
				 SessionRepository sessions, SlackSessionControl control, Function<UUID, BigDecimal> costToDate,
				 SlackCommandHandler commands) {
		this.props = props;
		this.socket = socket;
		this.outbox = outbox;
		this.api = api;
		this.sessions = sessions;
		this.control = control;
		this.costToDate = costToDate;
		this.commands = commands;
	}

	@EventListener(ApplicationReadyEvent.class)
	void start() {
		if (socket != null && props.hasTokens()) {
			socket.addListener(this);
		}
	}

	@Override
	public void onEvent(SlackEvent event) {
		try {
			switch (event.type()) {
				case "events_api" -> {
					switch (event.payload().path("event").path("type").asText("")) {
						case "message" -> onMessage(event.payload());
						case "app_mention" -> onMention(event.payload());
						default -> { /* not subscribed */ }
					}
				}
				case "interactive" -> onInteractive(event.payload());
				default -> { /* nothing else is subscribed */ }
			}
		} catch (RuntimeException e) {
			// never log the payload: it carries a verification token and the user's text
			log.warn("slack: could not handle {} ({}): {}", event.type(), event.dedupeKey(), e.getMessage());
		}
	}

	// ------------------------------------------------------------------ messages

	private void onMessage(JsonNode payload) {
		JsonNode ev = payload.path("event");
		if (!"message".equals(ev.path("type").asText(""))) {
			return;
		}
		// edits, deletes, joins, bot posts, broadcasts… all carry a subtype or a bot id
		if (ev.has("subtype") || ev.hasNonNull("bot_id")) {
			return;
		}
		String ts = ev.path("ts").asText("");
		String threadTs = ev.path("thread_ts").asText("");
		String channel = ev.path("channel").asText("");
		if (threadTs.isBlank() || threadTs.equals(ts) || !channel.equals(props.channel())) {
			return; // only replies inside a thread in our channel are ever prompts
		}
		String user = ev.path("user").asText("");
		// an open "what should it do?" question comes first: only the person who was asked, only a fresh one,
		// and not a reply that mentions the bot (that is a command of its own)
		String replyText = fromSlack(ev.path("text").asText("")).strip();
		if (!replyText.isEmpty() && !mentionsBot(replyText) && allowed(user)) {
			SlackCommandHandler.AwaitedPrompt awaited = pendingStarts.take(channel, threadTs, user).orElse(null);
			if (awaited != null) {
				log.info("slack: prompt for a pending dev request from {} ({} chars)", user, replyText.length());
				outbox.react(channel, ts, "eyes");
				commands.continueDev(awaited, replyText, mrkdwn -> reply(channel, threadTs, mrkdwn));
				return;
			}
		}
		SessionEntity session = sessions.findBySlackThread(channel, threadTs).orElse(null);
		if (session == null || !session.slackEnabled()) {
			return;
		}
		if (!allowed(user)) {
			log.warn("slack: ignoring a thread reply from non-allowlisted user {}", user);
			return;
		}
		String text = fromSlack(ev.path("text").asText("")).strip();
		if (text.isEmpty()) {
			return;
		}
		if (mentionsBot(text)) {
			return; // addressed to the bot, not to the session: onMention handles it
		}
		// one line per handled input, so "did my click arrive?" is answerable from the log; never the text itself
		log.info("slack: thread reply from {} for session {} ({} chars)", user, session.id(), text.length());
		Pending question = control.pendingList(session.id()).stream()
				.filter(p -> "AskUserQuestion".equals(p.toolName())).findFirst().orElse(null);
		if (question != null) {
			answerQuestionWithText(session, user, channel, threadTs, question, text);
			return;
		}
		audit(session, "prompt", user, null);
		try {
			control.sendPrompt(session.id(), text);
			outbox.react(channel, ts, "eyes"); // delivered: the session has it (queued behind a running turn, if one is)
		} catch (RuntimeException e) {
			reply(channel, threadTs, ":warning: Could not send that to the session — " + SlackBlocks.esc(e.getMessage()));
		}
	}

	// ------------------------------------------------------------------ mentions: start a session

	/**
	 * {@code @bot review <PR>} / {@code @bot dev <service> <branch> <prompt>} in the configured channel,
	 * from an allowlisted user, when starting sessions is switched on. The work happens on another
	 * thread ({@link SlackCommandHandler}); this only checks who may ask and acknowledges the message.
	 */
	private void onMention(JsonNode payload) {
		JsonNode ev = payload.path("event");
		if (ev.has("subtype") || ev.hasNonNull("bot_id")) {
			return;
		}
		String channel = ev.path("channel").asText("");
		if (!channel.equals(props.channel())) {
			return;
		}
		String user = ev.path("user").asText("");
		if (!allowed(user)) {
			log.warn("slack: ignoring a mention from non-allowlisted user {}", user);
			return;
		}
		String ts = ev.path("ts").asText("");
		if (ts.isBlank() || !handledMentions.firstTime(ts)) {
			return;
		}
		String replyThread = ev.path("thread_ts").asText(ts);
		if (!props.startEnabled()) {
			reply(channel, replyThread, ":information_source: Starting sessions from Slack is turned off on this server "
					+ "(set `ARTUS_SLACK_START_ENABLED=true` and restart).");
			return;
		}
		String text = withoutBotMention(fromSlack(ev.path("text").asText("")).strip());
		log.info("slack: mention from {} ({} chars)", user, text.length());
		outbox.react(channel, ts, "eyes");
		// a missing prompt can be asked for only under a top-level mention: inside a session thread a reply
		// already means "prompt for that session"
		java.util.function.Consumer<SlackCommandHandler.AwaitedPrompt> await = ev.hasNonNull("thread_ts") ? null
				: awaited -> pendingStarts.put(channel, ts, awaited);
		commands.handle(user, text, mrkdwn -> reply(channel, replyThread, mrkdwn), await);
	}

	private boolean mentionsBot(String text) {
		String id = botUserId();
		return id != null && (text.contains("<@" + id + ">") || text.contains("<@" + id + "|"));
	}

	private String withoutBotMention(String text) {
		String id = botUserId();
		return id == null ? text : text.replaceAll("<@" + java.util.regex.Pattern.quote(id) + "(\\|[^>]*)?>", "").strip();
	}

	private String botUserId() {
		try {
			return api.botUserId();
		} catch (RuntimeException e) {
			log.debug("slack: bot user id unavailable: {}", e.getMessage());
			return null;
		}
	}

	private void answerQuestionWithText(SessionEntity session, String user, String channel, String threadTs,
										Pending question, String text) {
		JsonNode questions = question.input().path("questions");
		if (questions.size() != 1) {
			reply(channel, threadTs, ":information_source: That question has several parts — please answer it on the dashboard.");
			return;
		}
		ObjectNode cmd = answerCommand(question, questions.get(0).path("question").asText(""), text, user);
		try {
			if (!control.respondIfPending(session.id(), cmd)) {
				reply(channel, threadTs, ":information_source: " + ALREADY_DECIDED);
			}
		} catch (RuntimeException e) {
			reply(channel, threadTs, ":warning: Could not deliver that answer — " + SlackBlocks.esc(e.getMessage()));
		}
	}

	// ------------------------------------------------------------------ button clicks

	private void onInteractive(JsonNode payload) {
		if (!"block_actions".equals(payload.path("type").asText(""))) {
			return;
		}
		String channel = payload.path("channel").path("id").asText(payload.path("container").path("channel_id").asText(""));
		if (!channel.equals(props.channel())) {
			return;
		}
		String user = payload.path("user").path("id").asText("");
		String responseUrl = payload.path("response_url").asText("");
		for (JsonNode action : payload.path("actions")) {
			try {
				onAction(user, channel, responseUrl, action);
			} catch (RuntimeException e) {
				log.warn("slack: action '{}' failed: {}", action.path("action_id").asText(""), e.getMessage());
				ephemeral(responseUrl, ":warning: That did not work — " + e.getMessage());
			}
		}
	}

	private void onAction(String user, String channel, String responseUrl, JsonNode action) {
		String actionId = action.path("action_id").asText("");
		if (SlackBlocks.SESSION_OPEN.equals(actionId)) {
			return; // a link button; Slack reports the click, there is nothing to do
		}
		// block_id: "<kind>:<sessionId>[:<requestId>]"
		String[] block = action.path("block_id").asText("").split(":", 3);
		if (block.length < 2) {
			return;
		}
		UUID sessionId;
		try {
			sessionId = UUID.fromString(block[1]);
		} catch (IllegalArgumentException e) {
			return;
		}
		SessionEntity session = authorizedSession(sessionId, channel);
		if (session == null) {
			return;
		}
		if (!allowed(user)) {
			log.warn("slack: ignoring a button click from non-allowlisted user {}", user);
			return;
		}
		String requestId = block.length > 2 ? block[2] : "";
		log.info("slack: {} by {} on session {}{}", actionId, user, sessionId, requestId.isEmpty() ? "" : " request " + requestId);
		switch (actionId) {
			case SlackBlocks.SESSION_STOP -> stop(session, user, responseUrl);
			case SlackBlocks.SESSION_STATUS -> status(session, responseUrl);
			case SlackBlocks.SESSION_CLOSE -> close(session, user, responseUrl);
			case SlackBlocks.PERM_ALLOW, SlackBlocks.PERM_DENY, SlackBlocks.PLAN_APPROVE, SlackBlocks.PLAN_KEEP ->
					decide(session, user, responseUrl, actionId, action.path("value").asText(""), requestId);
			default -> {
				if (actionId.startsWith(SlackBlocks.ASK_OPTION)) {
					String value = action.has("selected_option")
							? action.path("selected_option").path("value").asText("") : action.path("value").asText("");
					chooseOption(session, user, responseUrl, requestId, value);
				}
			}
		}
	}

	/**
	 * The session a click is about — or null. A child session posts into its <em>parent's</em> thread,
	 * so a click on a child's prompt is authorised by the parent's opt-in and channel.
	 */
	private SessionEntity authorizedSession(UUID sessionId, String channel) {
		SessionEntity session = sessions.find(sessionId).orElse(null);
		if (session == null || !"user".equals(session.kind())) {
			return null;
		}
		SessionEntity root = session.parentSessionId() == null ? session : sessions.find(session.parentSessionId()).orElse(null);
		if (root == null || !root.slackEnabled() || !channel.equals(root.slackChannel())) {
			return null;
		}
		return session;
	}

	private void decide(SessionEntity session, String user, String responseUrl, String actionId, String value,
						String requestId) {
		if (value.isBlank() || !value.equals(requestId)) {
			return;
		}
		boolean planAction = actionId.startsWith("plan_");
		boolean allow = SlackBlocks.PERM_ALLOW.equals(actionId) || SlackBlocks.PLAN_APPROVE.equals(actionId);
		Optional<Pending> pending = control.pending(session.id(), requestId);
		if (pending.isEmpty()) {
			ephemeral(responseUrl, ALREADY_DECIDED);
			return;
		}
		String tool = pending.get().toolName();
		if ("AskUserQuestion".equals(tool) || planAction != "ExitPlanMode".equals(tool)) {
			return; // a button that does not match the request it points at: ignore
		}
		ObjectNode cmd = command(requestId, allow ? "allow" : "deny", user);
		if (!allow && planAction) {
			cmd.put("message", KEEP_PLANNING);
		}
		deliver(session, responseUrl, cmd);
	}

	private void chooseOption(SessionEntity session, String user, String responseUrl, String requestId, String value) {
		Optional<Pending> pending = control.pending(session.id(), requestId);
		if (pending.isEmpty()) {
			ephemeral(responseUrl, ALREADY_DECIDED);
			return;
		}
		Pending p = pending.get();
		JsonNode questions = p.input().path("questions");
		if (!"AskUserQuestion".equals(p.toolName()) || questions.size() != 1) {
			ephemeral(responseUrl, "Please answer that question on the dashboard.");
			return;
		}
		JsonNode question = questions.get(0);
		deliver(session, responseUrl,
				answerCommand(p, question.path("question").asText(""), resolveLabel(question.path("options"), value), user));
	}

	private void stop(SessionEntity session, String user, String responseUrl) {
		SessionEntity current = sessions.get(session.id());
		SessionState state = current.state();
		if (state != SessionState.RUNNING && state != SessionState.WAITING_INPUT) {
			String label = state.name().toLowerCase().replace('_', ' ');
			ephemeral(responseUrl, "Nothing is running right now (" + label + ").");
			// the private reply is easy to miss (Slack can show it outside the thread you are looking at), and "Stop did
			// nothing" is a confusing thing to be left with — so say it in the thread as well
			if (current.slackChannel() != null && current.slackThreadTs() != null) {
				reply(current.slackChannel(), current.slackThreadTs(), ":octagonal_sign: <@" + user + "> pressed Stop, but nothing was running \u2014 the session is "
						+ label + "." + (state == SessionState.CRASHED ? " Resume it from the dashboard." : ""));
			}
			return;
		}
		audit(session, "stop", user, null);
		control.interrupt(session.id());
		ephemeral(responseUrl, "Interrupting the current turn…");
	}

	/**
	 * Closes a session from its card — the clean way only. Refused (and said so, privately and in the thread) while a
	 * turn is running or waiting for you, while it has live child sessions, and when the worktree has uncommitted
	 * changes; those last choices (commit, stash, discard) stay on the dashboard.
	 */
	private void close(SessionEntity session, String user, String responseUrl) {
		SessionEntity current = sessions.get(session.id());
		SessionState state = current.state();
		if (state == SessionState.CLOSED || state == SessionState.CLOSING || state == SessionState.FAILED) {
			ephemeral(responseUrl, "This session is already " + state.name().toLowerCase() + ".");
			return;
		}
		if (state == SessionState.RUNNING || state == SessionState.WAITING_INPUT) {
			refuseClose(current, user, responseUrl, "a turn is in progress \u2014 press Stop first");
			return;
		}
		List<String> liveChildren = sessions.findByParent(session.id()).stream()
				.filter(c -> c.state() != SessionState.CLOSED && c.state() != SessionState.FAILED)
				.map(c -> "*" + SlackBlocks.esc(c.name()) + "*").toList();
		if (!liveChildren.isEmpty()) {
			refuseClose(current, user, responseUrl, "it still has child sessions running (" + String.join(", ", liveChildren)
					+ ") \u2014 close those first");
			return;
		}
		audit(session, "close", user, null);
		ephemeral(responseUrl, "Closing the session\u2026");
		// closing waits for the sidecar and removes the worktree: seconds, so not on the thread that handles every click
		commands.runInBackground(() -> {
			try {
				control.closeClean(session.id()); // the mirror then posts the closing note and the check mark
			} catch (DirtyWorktreeException e) {
				List<String> files = e.dirtyFiles();
				String shown = String.join(", ", files.stream().limit(CLOSE_FILES_SHOWN).map(f -> "`" + SlackBlocks.esc(f.strip()) + "`").toList());
				refuseClose(current, user, null, "the worktree has " + files.size() + " uncommitted change" + (files.size() == 1 ? "" : "s")
						+ " (" + shown + (files.size() > CLOSE_FILES_SHOWN ? ", and " + (files.size() - CLOSE_FILES_SHOWN) + " more" : "")
						+ "). Close it from the dashboard to commit, stash or discard them");
			} catch (RuntimeException e) {
				log.warn("slack: close of {} failed: {}", session.id(), e.getMessage());
				refuseClose(current, user, null, "it could not be closed (" + SlackBlocks.esc(String.valueOf(e.getMessage()).lines().findFirst().orElse("")) + ")");
			}
		});
	}

	/** Said privately and in the thread, because a private reply alone is easy to miss. */
	private void refuseClose(SessionEntity session, String user, String responseUrl, String why) {
		ephemeral(responseUrl, ":warning: Not closed \u2014 " + why + ".");
		if (session.slackChannel() != null && session.slackThreadTs() != null) {
			reply(session.slackChannel(), session.slackThreadTs(), ":warning: <@" + user + "> asked to close this session, but it was not closed \u2014 " + why + ".");
		}
	}

	private void status(SessionEntity session, String responseUrl) {
		SessionEntity s = sessions.get(session.id());
		StringBuilder sb = new StringBuilder("*").append(SlackBlocks.esc(s.name())).append("* — ")
				.append(s.state().name().toLowerCase().replace('_', ' '));
		if (s.updatedAt() != null) {
			sb.append(" (last activity ")
					.append(SlackSessionMirror.formatDuration(Duration.between(s.updatedAt(), Instant.now()).toMillis()))
					.append(" ago)");
		}
		sb.append("\n`").append(SlackBlocks.esc(s.branch())).append("` · cost $")
				.append(SlackSessionMirror.money(costToDate.apply(s.id())));
		if (s.prUrl() != null) {
			sb.append(" · <").append(s.prUrl()).append("|PR>");
			if (s.prCheckStatus() != null) {
				sb.append(' ').append(SlackBlocks.esc(s.prCheckStatus()));
			}
		}
		int waiting = control.pendingList(s.id()).size();
		if (waiting > 0) {
			sb.append("\n").append(waiting).append(waiting == 1 ? " request is" : " requests are").append(" waiting for you");
		}
		ephemeral(responseUrl, sb.toString());
	}

	// ------------------------------------------------------------------ helpers

	private void deliver(SessionEntity session, String responseUrl, ObjectNode command) {
		try {
			if (!control.respondIfPending(session.id(), command)) {
				ephemeral(responseUrl, ALREADY_DECIDED);
			}
		} catch (RuntimeException e) {
			ephemeral(responseUrl, ":warning: Could not deliver that — " + e.getMessage());
		}
	}

	private static ObjectNode command(String requestId, String behavior, String slackUser) {
		return MAPPER.createObjectNode().put("type", "permission_response").put("requestId", requestId)
				.put("behavior", behavior).put("source", "slack").put("slackUser", slackUser);
	}

	/** Allow with the question's {@code answers} filled in — the shape the dashboard's question card sends. */
	private static ObjectNode answerCommand(Pending question, String questionText, String answer, String slackUser) {
		ObjectNode updated = question.input() instanceof ObjectNode obj ? obj.deepCopy() : MAPPER.createObjectNode();
		updated.putObject("answers").put(questionText, answer);
		ObjectNode cmd = command(question.requestId(), "allow", slackUser);
		cmd.set("updatedInput", updated);
		return cmd;
	}

	/** A select's option value may have been cut to Slack's 75 characters; map it back to the full label. */
	private static String resolveLabel(JsonNode options, String value) {
		for (JsonNode option : options) {
			String label = option.path("label").asText("");
			if (label.equals(value)) {
				return label;
			}
		}
		for (JsonNode option : options) {
			String label = option.path("label").asText("");
			if (!value.isEmpty() && label.startsWith(value)) {
				return label;
			}
		}
		return value;
	}

	private void audit(SessionEntity session, String kind, String user, String detail) {
		ObjectNode payload = MAPPER.createObjectNode().put("source", "slack").put("slackUser", user).put("kind", kind);
		if (detail != null) {
			payload.put("detail", detail);
		}
		control.audit(session.id(), payload);
	}

	private boolean allowed(String user) {
		return !user.isBlank() && props.allowedUsers().contains(user);
	}

	private void reply(String channel, String threadTs, String mrkdwn) {
		outbox.post(channel, threadTs, false, () -> SlackBlocks.notice("Artus", mrkdwn));
	}

	private void ephemeral(String responseUrl, String text) {
		if (responseUrl == null || responseUrl.isBlank()) {
			return;
		}
		try {
			api.respondEphemeral(responseUrl, text);
		} catch (RuntimeException e) {
			log.debug("slack: could not send an ephemeral reply: {}", e.getMessage());
		}
	}

	/** Slack's message encoding to plain text: unwrap links, then undo the three escapes. */
	static String fromSlack(String text) {
		String s = LINK.matcher(text).replaceAll("$1");
		return s.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
	}
}
