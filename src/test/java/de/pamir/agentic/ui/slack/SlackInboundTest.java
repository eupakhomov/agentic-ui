package de.pamir.agentic.ui.slack;

import de.pamir.agentic.ui.session.SessionEntity;
import de.pamir.agentic.ui.session.SessionState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The inbound handler against fakes: allowlist, filtering, stale decisions, questions, stop and status. */
class SlackInboundTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String CHANNEL = "C123";
	private static final String THREAD = "100.1";
	private static final String RESPONSE_URL = "https://hooks.slack.com/actions/T/1/x";

	private final FakeSlack slack = new FakeSlack();
	private final FakeSessionStore store = new FakeSessionStore();
	private final FakeSessionControl control = new FakeSessionControl();
	private final FakeStarter starter = new FakeStarter();
	private SlackOutbox outbox;
	private SlackInbound inbound;
	private SessionEntity session;

	@BeforeEach
	void setUp() {
		outbox = new SlackOutbox(slack, Duration.ZERO, 100, d -> { });
		outbox.start();
		SlackProperties props = new SlackProperties(true, "xoxb-test", "xapp-test", CHANNEL, List.of("U1", "U2"), false, "", true);
		inbound = new SlackInbound(props, null, outbox, slack, store, control, id -> new BigDecimal("0.5000"),
				new SlackCommandHandler(starter, Runnable::run));
		session = put("login", SessionState.RUNNING, true, null);
		starter.polyrepo("api", "acme/api");
		starter.prs.put(12, FakeStarter.pr(12, "OPEN", false, "src/Main.java"));
	}

	@AfterEach
	void tearDown() {
		outbox.stop();
	}

	private SessionEntity put(String name, SessionState state, boolean slackEnabled, UUID parent) {
		SessionEntity e = SessionEntity.builder().id(UUID.randomUUID()).name(name).provider("claude").repoPath("/r")
				.branch("feat/" + name).baseBranch("main").worktreePath("/w").state(state).kind("user")
				.slackEnabled(slackEnabled).parentSessionId(parent).updatedAt(Instant.now().minusSeconds(75))
				.slackChannel(parent == null ? CHANNEL : null).slackThreadTs(parent == null ? THREAD : null).build();
		store.put(e);
		return e;
	}

	// ---------------------------------------------------------------- payload builders

	private static SlackEvent message(String json) {
		return new SlackEvent("events_api", "event:x", MAPPER.readTree("{\"event\":" + json + "}"));
	}

	private static String reply(String user, String text, String extra) {
		return "{\"type\":\"message\",\"channel\":\"" + CHANNEL + "\",\"user\":\"" + user + "\",\"ts\":\"100.5\","
				+ "\"thread_ts\":\"" + THREAD + "\",\"text\":" + MAPPER.writeValueAsString(text) + extra + "}";
	}

	private static SlackEvent clickIn(String user, String channel, String actionId, String blockId, String value) {
		return new SlackEvent("interactive", "action:x", MAPPER.readTree("{\"type\":\"block_actions\",\"user\":{\"id\":\"" + user
				+ "\"},\"channel\":{\"id\":\"" + channel + "\"},\"response_url\":\"" + RESPONSE_URL + "\",\"actions\":[{\"action_id\":\""
				+ actionId + "\",\"block_id\":\"" + blockId + "\",\"value\":\"" + value + "\"}]}"));
	}

	private SlackEvent click(String user, String actionId, String kind, String requestId, String value) {
		return clickIn(user, CHANNEL, actionId, kind + ":" + session.id() + (requestId == null ? "" : ":" + requestId), value);
	}

	private void bashPending(String requestId) {
		control.addPending(session.id(), requestId, "Bash", MAPPER.readTree("{\"command\":\"ls\"}"));
	}

	private void planPending(String requestId) {
		control.addPending(session.id(), requestId, "ExitPlanMode", MAPPER.readTree("{\"plan\":\"do it\"}"));
	}

	private static final String ONE_QUESTION = "{\"questions\":[{\"question\":\"Which DB?\",\"options\":["
			+ "{\"label\":\"Postgres\"},{\"label\":\"SQLite\"}]}]}";

	private void questionPending(String requestId, String input) {
		control.addPending(session.id(), requestId, "AskUserQuestion", MAPPER.readTree(input));
	}

	private List<FakeSlack.Call> ephemerals() {
		return slack.of("ephemeral");
	}

	// ---------------------------------------------------------------- thread replies

	@Test
	void anAllowlistedThreadReplyBecomesAPromptWithSlackEncodingUndone() {
		inbound.onEvent(message(reply("U1", "use <https://x.dev/doc|the doc> &amp; &lt;b&gt;", "")));
		assertThat(control.prompts).containsExactly("use https://x.dev/doc & <b>");
		assertThat(control.audits).hasSize(1);
		assertThat(control.audits.get(0).path("kind").asText()).isEqualTo("prompt");
		assertThat(control.audits.get(0).path("slackUser").asText()).isEqualTo("U1");
		assertThat(control.audits.get(0).path("source").asText()).isEqualTo("slack");
		assertThat(control.audits.get(0).toString()).doesNotContain("the doc"); // the text is not duplicated into the audit
		FakeSlack.await(() -> !slack.of("react").isEmpty());
		assertThat(slack.of("react").get(0).ts()).isEqualTo("100.5"); // the reply itself is marked as received
		assertThat(slack.of("react").get(0).extra()).isEqualTo("eyes");
	}

	@Test
	void aNonAllowlistedUserIsIgnoredAndAnEmptyAllowlistAcceptsNobody() {
		inbound.onEvent(message(reply("U9", "do evil", "")));
		assertThat(control.prompts).isEmpty();

		SlackProperties nobody = new SlackProperties(true, "b", "a", CHANNEL, List.of(), false, "", true);
		SlackInbound strict = new SlackInbound(nobody, null, outbox, slack, store, control, id -> BigDecimal.ZERO,
				new SlackCommandHandler(starter, Runnable::run));
		strict.onEvent(message(reply("U1", "hello", "")));
		assertThat(control.prompts).isEmpty();
	}

	@Test
	void onlyPlainRepliesInAKnownThreadOfTheConfiguredChannelAreEverPrompts() {
		inbound.onEvent(message("{\"type\":\"message\",\"channel\":\"" + CHANNEL + "\",\"user\":\"U1\",\"ts\":\"1.2\",\"text\":\"top level\"}"));
		inbound.onEvent(message(reply("U1", "edited", ",\"subtype\":\"message_changed\"")));
		inbound.onEvent(message(reply("U1", "from a bot", ",\"bot_id\":\"B1\"")));
		inbound.onEvent(message(reply("U1", "the parent itself", "").replace("\"ts\":\"100.5\"", "\"ts\":\"" + THREAD + "\"")));
		inbound.onEvent(message(reply("U1", "other channel", "").replace("\"channel\":\"C123\"", "\"channel\":\"C999\"")));
		inbound.onEvent(message(reply("U1", "unknown thread", "").replace("\"thread_ts\":\"100.1\"", "\"thread_ts\":\"55.5\"")));
		inbound.onEvent(message(reply("U1", "   ", "")));
		inbound.onEvent(message("{\"type\":\"reaction_added\"}"));
		assertThat(control.prompts).isEmpty();
	}

	@Test
	void aThreadOfASessionThatIsNotMirroredIsNotListenedTo() {
		store.put(session.toBuilder().slackEnabled(false).build());
		inbound.onEvent(message(reply("U1", "hello", "")));
		assertThat(control.prompts).isEmpty();
	}

	@Test
	void aPromptTheSessionRefusesIsReportedInTheThread() {
		control.failPrompt = new IllegalStateException("session is CLOSED and does not accept messages");
		inbound.onEvent(message(reply("U1", "hello", "")));
		FakeSlack.await(() -> slack.of("post").size() == 1);
		FakeSlack.Call warning = slack.of("post").get(0);
		assertThat(slack.of("react")).isEmpty(); // not delivered, so not marked as received
		assertThat(warning.extra()).isEqualTo(THREAD);
		assertThat(warning.message().blocks().toString()).contains("does not accept messages");
	}

	@Test
	void aTextReplyAnswersAPendingSingleQuestion() {
		questionPending("q1", ONE_QUESTION);
		inbound.onEvent(message(reply("U1", "MySQL actually", "")));
		assertThat(control.prompts).isEmpty();
		assertThat(control.responses).hasSize(1);
		JsonNode cmd = control.responses.get(0);
		assertThat(cmd.path("behavior").asText()).isEqualTo("allow");
		assertThat(cmd.path("requestId").asText()).isEqualTo("q1");
		assertThat(cmd.path("slackUser").asText()).isEqualTo("U1");
		assertThat(cmd.path("updatedInput").path("answers").path("Which DB?").asText()).isEqualTo("MySQL actually");
		assertThat(cmd.path("updatedInput").path("questions")).hasSize(1); // the original input is kept
	}

	@Test
	void aTextReplyToAMultiPartQuestionSendsYouToTheDashboard() {
		questionPending("q1", "{\"questions\":[{\"question\":\"A\",\"options\":[]},{\"question\":\"B\",\"options\":[]}]}");
		inbound.onEvent(message(reply("U1", "whatever", "")));
		FakeSlack.await(() -> slack.of("post").size() == 1);
		assertThat(slack.of("post").get(0).message().blocks().toString()).contains("dashboard");
		assertThat(control.responses).isEmpty();
		assertThat(control.prompts).isEmpty();
	}

	// ---------------------------------------------------------------- permission / plan buttons

	@Test
	void allowAndDenyDeliverTheDecisionWithItsAuthor() {
		bashPending("p1");
		bashPending("p2");
		inbound.onEvent(click("U1", "perm_allow", "perm", "p1", "p1"));
		inbound.onEvent(click("U2", "perm_deny", "perm", "p2", "p2"));
		assertThat(control.responses).hasSize(2);
		assertThat(control.responses.get(0).toString()).contains("\"behavior\":\"allow\"").contains("\"slackUser\":\"U1\"")
				.contains("\"source\":\"slack\"").contains("\"type\":\"permission_response\"");
		assertThat(control.responses.get(1).toString()).contains("\"behavior\":\"deny\"").contains("\"slackUser\":\"U2\"");
		assertThat(ephemerals()).isEmpty();
	}

	@Test
	void aSecondClickOnTheSameRequestIsToldItWasAlreadyDecided() {
		bashPending("p1");
		inbound.onEvent(click("U1", "perm_allow", "perm", "p1", "p1"));
		inbound.onEvent(click("U2", "perm_deny", "perm", "p1", "p1"));
		assertThat(control.responses).hasSize(1);
		assertThat(ephemerals()).hasSize(1);
		assertThat(ephemerals().get(0).extra()).startsWith(RESPONSE_URL).contains("already decided");
	}

	@Test
	void aRequestAlreadyDecidedOnTheDashboardIsNotSentTwice() {
		bashPending("p1");
		control.pending.take(session.id(), "p1"); // the dashboard got there first
		inbound.onEvent(click("U1", "perm_allow", "perm", "p1", "p1"));
		assertThat(control.responses).isEmpty();
		assertThat(ephemerals().get(0).extra()).contains("already decided");
	}

	@Test
	void planButtonsApproveOrKeepPlanning() {
		planPending("p1");
		planPending("p2");
		inbound.onEvent(click("U1", "plan_approve", "plan", "p1", "p1"));
		inbound.onEvent(click("U1", "plan_keep", "plan", "p2", "p2"));
		assertThat(control.responses.get(0).path("behavior").asText()).isEqualTo("allow");
		assertThat(control.responses.get(1).path("behavior").asText()).isEqualTo("deny");
		assertThat(control.responses.get(1).path("message").asText()).isEqualTo("Keep planning; do not execute yet.");
	}

	@Test
	void aButtonThatDoesNotMatchItsRequestIsIgnored() {
		planPending("p1");
		bashPending("p2");
		inbound.onEvent(click("U1", "perm_allow", "perm", "p1", "p1")); // an Allow button on a plan request
		inbound.onEvent(click("U1", "plan_approve", "plan", "p2", "p2")); // a plan button on a Bash request
		inbound.onEvent(click("U1", "perm_allow", "perm", "p2", "someone-elses")); // value disagrees with the block
		assertThat(control.responses).isEmpty();
		assertThat(control.pending.list(session.id())).hasSize(2);
	}

	@Test
	void aNonAllowlistedClickIsSilentlyIgnored() {
		bashPending("p1");
		inbound.onEvent(click("U9", "perm_allow", "perm", "p1", "p1"));
		assertThat(control.responses).isEmpty();
		assertThat(ephemerals()).isEmpty();
		assertThat(control.pending.peek(session.id(), "p1")).isPresent(); // still waiting for someone who may decide
	}

	@Test
	void clicksFromAnotherChannelOrForAnUnmirroredSessionAreIgnored() {
		bashPending("p1");
		inbound.onEvent(clickIn("U1", "C999", "perm_allow", "perm:" + session.id() + ":p1", "p1"));
		store.put(session.toBuilder().slackEnabled(false).build());
		inbound.onEvent(click("U1", "perm_allow", "perm", "p1", "p1"));
		inbound.onEvent(clickIn("U1", CHANNEL, "perm_allow", "perm:not-a-uuid:p1", "p1"));
		assertThat(control.responses).isEmpty();
	}

	@Test
	void aChildsPromptIsAuthorisedByItsParentsThread() {
		SessionEntity child = put("billing", SessionState.RUNNING, false, session.id());
		control.addPending(child.id(), "c1", "Bash", MAPPER.readTree("{\"command\":\"ls\"}"));
		inbound.onEvent(clickIn("U1", CHANNEL, "perm_allow", "perm:" + child.id() + ":c1", "c1"));
		assertThat(control.responses).hasSize(1);
		store.put(session.toBuilder().slackEnabled(false).build()); // parent no longer mirrored
		control.addPending(child.id(), "c2", "Bash", MAPPER.readTree("{\"command\":\"ls\"}"));
		inbound.onEvent(clickIn("U1", CHANNEL, "perm_allow", "perm:" + child.id() + ":c2", "c2"));
		assertThat(control.responses).hasSize(1);
	}

	@Test
	void aDeliveryFailureIsReportedPrivately() {
		bashPending("p1");
		control.failRespond = new IllegalStateException("sidecar not running");
		inbound.onEvent(click("U1", "perm_allow", "perm", "p1", "p1"));
		assertThat(ephemerals().get(0).extra()).contains("Could not deliver").contains("sidecar not running");
	}

	// ---------------------------------------------------------------- question buttons

	@Test
	void anOptionButtonAnswersTheQuestionWithThatLabel() {
		questionPending("q1", ONE_QUESTION);
		inbound.onEvent(click("U1", "ask_option_2", "ask", "q1", "SQLite"));
		assertThat(control.responses).hasSize(1);
		assertThat(control.responses.get(0).path("updatedInput").path("answers").path("Which DB?").asText()).isEqualTo("SQLite");
	}

	@Test
	void aSelectsTruncatedValueIsMappedBackToTheFullLabel() {
		String longLabel = "A very long option label that goes on and on and on past seventy-five characters for sure";
		questionPending("q1", "{\"questions\":[{\"question\":\"Pick\",\"options\":[{\"label\":\"" + longLabel + "\"}]}]}");
		JsonNode payload = MAPPER.readTree("{\"type\":\"block_actions\",\"user\":{\"id\":\"U1\"},\"channel\":{\"id\":\"C123\"},"
				+ "\"response_url\":\"" + RESPONSE_URL + "\",\"actions\":[{\"action_id\":\"ask_option\",\"block_id\":\"ask:"
				+ session.id() + ":q1\",\"selected_option\":{\"value\":\"" + longLabel.substring(0, 75) + "\"}}]}");
		inbound.onEvent(new SlackEvent("interactive", "action:y", payload));
		assertThat(control.responses.get(0).path("updatedInput").path("answers").path("Pick").asText()).isEqualTo(longLabel);
	}

	@Test
	void anOptionClickOnAMultiPartOrAnsweredQuestionIsExplainedNotApplied() {
		questionPending("q1", "{\"questions\":[{\"question\":\"A\",\"options\":[]},{\"question\":\"B\",\"options\":[]}]}");
		inbound.onEvent(click("U1", "ask_option_1", "ask", "q1", "x"));
		assertThat(control.responses).isEmpty();
		assertThat(ephemerals().get(0).extra()).contains("dashboard");
		inbound.onEvent(click("U1", "ask_option_1", "ask", "gone", "x"));
		assertThat(ephemerals().get(1).extra()).contains("already decided");
	}

	// ---------------------------------------------------------------- stop / status / open

	@Test
	void stopInterruptsARunningTurnAndRecordsWhoAskedForIt() {
		inbound.onEvent(click("U1", "session_stop", "session", null, session.id().toString()));
		assertThat(control.interrupts).containsExactly(session.id());
		assertThat(control.audits.get(0).path("kind").asText()).isEqualTo("stop");
		assertThat(ephemerals().get(0).extra()).contains("Interrupting");
		assertThat(slack.of("post")).isEmpty(); // a real stop gets its note from the mirror, not from here
	}

	@Test
	void stopOnAnIdleSessionDoesNothingButSaysSoPrivatelyAndInTheThread() {
		store.put(session.toBuilder().state(SessionState.IDLE).build());
		inbound.onEvent(click("U1", "session_stop", "session", null, session.id().toString()));
		assertThat(control.interrupts).isEmpty();
		assertThat(control.audits).isEmpty();
		assertThat(ephemerals().get(0).extra()).contains("Nothing is running").contains("idle");
		FakeSlack.await(() -> !slack.of("post").isEmpty());
		FakeSlack.Call note = slack.of("post").get(0);
		assertThat(note.extra()).isEqualTo(THREAD);
		assertThat(note.message().blocks().toString()).contains("<@U1> pressed Stop").contains("nothing was running").contains("idle");
	}

	@Test
	void stopOnACrashedSessionPointsToTheDashboard() {
		store.put(session.toBuilder().state(SessionState.CRASHED).build());
		inbound.onEvent(click("U1", "session_stop", "session", null, session.id().toString()));
		FakeSlack.await(() -> !slack.of("post").isEmpty());
		assertThat(slack.of("post").get(0).message().blocks().toString()).contains("crashed").contains("Resume it from the dashboard");
	}

	// ---------------------------------------------------------------- close

	private void closeClick() {
		inbound.onEvent(click("U1", "session_close", "session", null, session.id().toString()));
	}

	private void state(SessionState state) {
		store.put(session.toBuilder().state(state).build());
	}

	@Test
	void closeClosesAnIdleSessionCleanlyAndRecordsWhoAskedForIt() {
		state(SessionState.IDLE);
		closeClick();
		assertThat(control.closed).containsExactly(session.id());
		assertThat(control.audits.get(0).path("kind").asText()).isEqualTo("close");
		assertThat(control.audits.get(0).path("slackUser").asText()).isEqualTo("U1");
		assertThat(ephemerals().get(0).extra()).contains("Closing");
		assertThat(slack.of("post")).isEmpty(); // the mirror, not the click handler, posts the closing note
	}

	@Test
	void parkedAndCrashedSessionsCanBeClosedToo() {
		state(SessionState.PARKED);
		closeClick();
		state(SessionState.CRASHED);
		closeClick();
		assertThat(control.closed).hasSize(2);
	}

	@Test
	void closeIsRefusedWhileATurnRunsOrWaitsAndSaysSoInTheThread() {
		for (SessionState busy : List.of(SessionState.RUNNING, SessionState.WAITING_INPUT)) {
			state(busy);
			closeClick();
		}
		assertThat(control.closed).isEmpty();
		assertThat(control.audits).isEmpty();
		assertThat(ephemerals()).hasSize(2).allSatisfy(c -> assertThat(c.extra()).contains("Not closed").contains("press Stop first"));
		FakeSlack.await(() -> slack.of("post").size() == 2);
		assertThat(slack.of("post").get(0).extra()).isEqualTo(THREAD);
		assertThat(slack.of("post").get(0).message().blocks().toString()).contains("<@U1> asked to close").contains("press Stop first");
	}

	@Test
	void closeIsRefusedWhileChildSessionsAreStillLiveButNotForFinishedOnes() {
		state(SessionState.IDLE);
		SessionEntity live = put("billing", SessionState.IDLE, false, session.id());
		put("done-child", SessionState.CLOSED, false, session.id());
		closeClick();
		assertThat(control.closed).isEmpty();
		FakeSlack.await(() -> !slack.of("post").isEmpty());
		String note = slack.of("post").get(0).message().blocks().toString();
		assertThat(note).contains("child sessions running").contains("billing").doesNotContain("done-child");

		store.put(live.toBuilder().state(SessionState.CLOSED).build());
		closeClick();
		assertThat(control.closed).containsExactly(session.id());
	}

	@Test
	void aDirtyWorktreeIsNotClosedAndItsFilesAreListedInTheThread() {
		state(SessionState.IDLE);
		List<String> files = new java.util.ArrayList<>();
		for (int i = 1; i <= 10; i++) {
			files.add(" M src/File" + i + ".java");
		}
		control.failClose = new de.pamir.agentic.ui.session.DirtyWorktreeException(files);
		closeClick();

		assertThat(control.closed).isEmpty();
		FakeSlack.await(() -> !slack.of("post").isEmpty());
		String note = slack.of("post").get(0).message().blocks().toString();
		assertThat(note).contains("10 uncommitted changes").contains("`M src/File1.java`").contains("`M src/File8.java`")
				.doesNotContain("File9").contains("and 2 more").contains("commit, stash or discard").contains("dashboard");
	}

	@Test
	void anyOtherFailureToCloseIsReportedInOneLine() {
		state(SessionState.IDLE);
		control.failClose = new IllegalStateException("worktree remove failed\nat some.Stack");
		closeClick();
		FakeSlack.await(() -> !slack.of("post").isEmpty());
		String note = slack.of("post").get(0).message().blocks().toString();
		assertThat(note).contains("could not be closed").contains("worktree remove failed").doesNotContain("Stack");
	}

	@Test
	void closingASessionThatIsAlreadyClosedIsAKindMessageNotAnError() {
		state(SessionState.CLOSED);
		closeClick();
		assertThat(control.closed).isEmpty();
		assertThat(ephemerals().get(0).extra()).contains("already closed");
	}

	@Test
	void closeFromSomeoneOutsideTheAllowlistDoesNothing() {
		state(SessionState.IDLE);
		inbound.onEvent(click("U9", "session_close", "session", null, session.id().toString()));
		assertThat(control.closed).isEmpty();
		assertThat(ephemerals()).isEmpty();
	}

	@Test
	void statusReportsStateCostAndWaitingRequestsPrivately() {
		bashPending("p1");
		store.put(session.toBuilder().prUrl("https://github.com/o/r/pull/3").prCheckStatus("PENDING").build());
		inbound.onEvent(click("U2", "session_status", "session", null, session.id().toString()));
		String text = ephemerals().get(0).extra();
		assertThat(text).contains("login").contains("running").contains("1m 15s ago").contains("feat/login")
				.contains("$0.50").contains("pull/3").contains("PENDING").contains("1 request is waiting for you");
	}

	@Test
	void theOpenLinkButtonAndUnknownActionsDoNothing() {
		inbound.onEvent(click("U9", "session_open", "session", null, session.id().toString()));
		inbound.onEvent(click("U1", "mystery", "session", null, "x"));
		assertThat(control.responses).isEmpty();
		assertThat(control.interrupts).isEmpty();
		assertThat(ephemerals()).isEmpty();
	}

	@Test
	void aHandlerFailureNeverEscapesToTheSocket() {
		SlackEvent broken = new SlackEvent("interactive", "action:z", MAPPER.readTree("{\"type\":\"block_actions\",\"channel\":{\"id\":\"C123\"},\"actions\":\"nonsense\"}"));
		inbound.onEvent(broken); // must not throw
		inbound.onEvent(new SlackEvent("events_api", "event:z", MAPPER.readTree("{}")));
	}

	// ---------------------------------------------------------------- mentions: start a session

	private static SlackEvent mention(String user, String text, String ts, String threadTs, String channel, String extra) {
		return message("{\"type\":\"app_mention\",\"user\":\"" + user + "\",\"channel\":\"" + channel + "\",\"ts\":\"" + ts + "\","
				+ (threadTs == null ? "" : "\"thread_ts\":\"" + threadTs + "\",") + "\"text\":" + MAPPER.writeValueAsString(text) + extra + "}");
	}

	private static SlackEvent mention(String user, String text, String ts) {
		return mention(user, text, ts, null, CHANNEL, "");
	}

	private static final String REVIEW_12 = "<@UBOT> review <https://github.com/acme/api/pull/12|https://github.com/acme/api/pull/12>";

	@Test
	void anAllowlistedMentionStartsASessionAcknowledgesItAndAnswersInThreadUnderIt() {
		inbound.onEvent(mention("U1", REVIEW_12, "200.1"));

		assertThat(starter.started).hasSize(1);
		assertThat(starter.started.get(0).sessionType()).isEqualTo("review");
		assertThat(starter.started.get(0).prUrl()).isEqualTo("https://github.com/acme/api/pull/12");
		FakeSlack.await(() -> !slack.of("react").isEmpty() && !slack.of("post").isEmpty());
		assertThat(slack.of("react").get(0).ts()).isEqualTo("200.1");
		assertThat(slack.of("react").get(0).extra()).isEqualTo("eyes");
		FakeSlack.Call answer = slack.of("post").get(0);
		assertThat(answer.extra()).isEqualTo("200.1"); // a thread under the mention
		assertThat(answer.message().blocks().toString()).contains("Review session");
	}

	@Test
	void aDevMentionPassesTheWholeMultiLinePromptThrough() {
		inbound.onEvent(mention("U1", "<@UBOT> dev api feat/x Add a health endpoint\nand test it &amp; document it", "200.2"));
		assertThat(starter.started).hasSize(1);
		assertThat(starter.started.get(0).prompt()).isEqualTo("Add a health endpoint\nand test it & document it");
		assertThat(starter.started.get(0).branch()).isEqualTo("feat/x");
	}

	@Test
	void aMentionInsideAThreadIsAnsweredInThatThread() {
		inbound.onEvent(mention("U1", "<@UBOT> help", "200.3", "100.1", CHANNEL, ""));
		FakeSlack.await(() -> !slack.of("post").isEmpty());
		assertThat(slack.of("post").get(0).extra()).isEqualTo("100.1");
		assertThat(starter.started).isEmpty();
	}

	@Test
	void mentionsFromOutsideTheAllowlistTheChannelOrABotAreIgnored() {
		inbound.onEvent(mention("U9", REVIEW_12, "201.1"));
		inbound.onEvent(mention("U1", REVIEW_12, "201.2", null, "C999", ""));
		inbound.onEvent(mention("U1", REVIEW_12, "201.3", null, CHANNEL, ",\"bot_id\":\"B1\""));
		inbound.onEvent(mention("U1", REVIEW_12, "201.4", null, CHANNEL, ",\"subtype\":\"bot_message\""));
		assertThat(starter.started).isEmpty();
		assertThat(slack.calls).isEmpty();
	}

	@Test
	void whenStartingIsSwitchedOffTheMentionIsAnsweredAndNothingStarts() {
		SlackProperties off = new SlackProperties(true, "b", "a", CHANNEL, List.of("U1"), false, "", false);
		SlackInbound disabled = new SlackInbound(off, null, outbox, slack, store, control, id -> BigDecimal.ZERO,
				new SlackCommandHandler(starter, Runnable::run));
		disabled.onEvent(mention("U1", REVIEW_12, "202.1"));
		FakeSlack.await(() -> !slack.of("post").isEmpty());
		assertThat(slack.of("post").get(0).message().blocks().toString()).contains("turned off").contains("ARTUS_SLACK_START_ENABLED");
		assertThat(starter.started).isEmpty();
		assertThat(slack.of("react")).isEmpty();
	}

	@Test
	void theSameMentionDeliveredTwiceStartsOneSession() {
		inbound.onEvent(mention("U1", REVIEW_12, "203.1"));
		inbound.onEvent(mention("U1", REVIEW_12, "203.1"));
		assertThat(starter.started).hasSize(1);
	}

	@Test
	void aThreadReplyThatMentionsTheBotIsACommandNotAPromptForTheSession() {
		inbound.onEvent(message(reply("U1", "<@UBOT> help", "")));
		assertThat(control.prompts).isEmpty();
		inbound.onEvent(message(reply("U1", "ask <@UBOT|artus> something", "")));
		assertThat(control.prompts).isEmpty();
		inbound.onEvent(message(reply("U1", "an ordinary reply", "")));
		assertThat(control.prompts).containsExactly("an ordinary reply");
	}

	@Test
	void mentionsAreStillCommandsWhenTheBotsOwnIdCannotBeLearned() {
		slack.botUser = null;
		// without its own id the bot cannot recognise or strip its mention, so the text reaches the parser as is
		inbound.onEvent(mention("U1", "<@UBOT> help", "204.1"));
		FakeSlack.await(() -> !slack.of("post").isEmpty());
		assertThat(starter.started).isEmpty();
	}

	// ---- dev without a prompt: the bot asks, the requester's next reply in that thread answers

	private static SlackEvent replyIn(String threadTs, String ts, String user, String text) {
		return message("{\"type\":\"message\",\"channel\":\"" + CHANNEL + "\",\"user\":\"" + user + "\",\"ts\":\"" + ts + "\","
				+ "\"thread_ts\":\"" + threadTs + "\",\"text\":" + MAPPER.writeValueAsString(text) + "}");
	}

	private void askForPrompt(String ts) {
		inbound.onEvent(mention("U1", "<@UBOT> dev api feat/x", ts));
		FakeSlack.await(() -> !slack.of("post").isEmpty());
	}

	@Test
	void aDevMentionWithoutAPromptAsksInTheThreadAndTheRequestersReplyBecomesThePrompt() {
		askForPrompt("300.1");
		FakeSlack.Call ask = slack.of("post").get(0);
		assertThat(ask.extra()).isEqualTo("300.1");
		assertThat(ask.message().blocks().toString()).contains("<@U1>").contains("Reply in this thread");
		assertThat(starter.started).isEmpty();

		inbound.onEvent(replyIn("300.1", "300.2", "U1", "Add a health endpoint &amp; test it"));

		assertThat(starter.started).hasSize(1);
		assertThat(starter.started.get(0).prompt()).isEqualTo("Add a health endpoint & test it");
		assertThat(starter.started.get(0).branch()).isEqualTo("feat/x");
		FakeSlack.await(() -> slack.of("post").size() == 2 && slack.of("react").stream().anyMatch(c -> c.ts().equals("300.2")));
		assertThat(slack.of("post").get(1).extra()).isEqualTo("300.1");
		assertThat(control.prompts).isEmpty(); // not a prompt for any session
		// answered once: another reply starts nothing
		inbound.onEvent(replyIn("300.1", "300.3", "U1", "and more"));
		assertThat(starter.started).hasSize(1);
	}

	@Test
	void onlyTheRequestersReplyCountsAndTheirLaterReplyStillWorks() {
		askForPrompt("301.1");
		inbound.onEvent(replyIn("301.1", "301.2", "U2", "I am not who was asked"));
		assertThat(starter.started).isEmpty();
		inbound.onEvent(replyIn("301.1", "301.3", "U1", "the real prompt"));
		assertThat(starter.started).hasSize(1);
		assertThat(starter.started.get(0).prompt()).isEqualTo("the real prompt");
	}

	@Test
	void cancelInTheThreadStartsNothing() {
		askForPrompt("302.1");
		inbound.onEvent(replyIn("302.1", "302.2", "U1", "cancel"));
		FakeSlack.await(() -> slack.of("post").size() == 2);
		assertThat(slack.of("post").get(1).message().blocks().toString()).contains("Cancelled");
		assertThat(starter.started).isEmpty();
	}

	@Test
	void aReplyThatMentionsTheBotOrNotInThatThreadIsNotTakenAsThePrompt() {
		askForPrompt("303.1");
		inbound.onEvent(replyIn("303.1", "303.2", "U1", "<@UBOT> help"));            // a command of its own
		inbound.onEvent(replyIn("999.9", "303.3", "U1", "wrong thread"));            // another thread
		assertThat(starter.started).isEmpty();
		inbound.onEvent(replyIn("303.1", "303.4", "U1", "now the prompt"));
		assertThat(starter.started).hasSize(1);
	}

	@Test
	void insideASessionThreadAMissingPromptIsStillAnErrorSoRepliesKeepTheirMeaning() {
		inbound.onEvent(mention("U1", "<@UBOT> dev api feat/x", "304.1", "100.1", CHANNEL, ""));
		FakeSlack.await(() -> !slack.of("post").isEmpty());
		assertThat(slack.of("post").get(0).message().blocks().toString()).contains("needs a prompt");
		inbound.onEvent(message(reply("U1", "a normal prompt for the session", "")));
		assertThat(control.prompts).containsExactly("a normal prompt for the session");
		assertThat(starter.started).isEmpty();
	}

	@Test
	void aWithMentionStartsTheSessionFromTheNamedTemplateEvenWhenItsNameHasSpaces() {
		SlackSessionStarter.TemplateInfo t = starter.template("Bug fix", null);
		inbound.onEvent(mention("U1", "<@UBOT> dev-with \"Bug fix\" api feat/x Fix the login bug", "400.1"));
		assertThat(starter.started).hasSize(1);
		assertThat(starter.started.get(0).templateId()).isEqualTo(t.id());
		assertThat(starter.started.get(0).prompt()).isEqualTo("Fix the login bug");

		inbound.onEvent(mention("U1", "<@UBOT> review-with bug <https://github.com/acme/api/pull/12|x>", "400.2"));
		assertThat(starter.started).hasSize(2);
		assertThat(starter.started.get(1).sessionType()).isEqualTo("review");
		assertThat(starter.started.get(1).templateId()).isEqualTo(t.id());
	}

	@Test
	void slackEncodingIsUndoneInTheRightOrder() {
		assertThat(SlackInbound.fromSlack("&amp;lt; stays literal, &lt;tag&gt; and <https://a.b|c> and <mailto:x@y.z|x@y.z>"))
				.isEqualTo("&lt; stays literal, <tag> and https://a.b and mailto:x@y.z");
	}
}
