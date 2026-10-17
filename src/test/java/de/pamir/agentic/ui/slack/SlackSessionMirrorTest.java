package de.pamir.agentic.ui.slack;

import de.pamir.agentic.ui.journal.EventJournal.JournalEvent;
import de.pamir.agentic.ui.journal.SessionEventBus;
import de.pamir.agentic.ui.session.SessionEntity;
import de.pamir.agentic.ui.session.SessionRepository;
import de.pamir.agentic.ui.session.SessionState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** The mirror end to end: a real outbox and event bus, a fake Slack and an in-memory session store. */
class SlackSessionMirrorTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** In-memory {@link SessionRepository}; also records which thread was persisted for a session. */
	private static final class Repo extends SessionRepository {
		final Map<UUID, SessionEntity> byId = new ConcurrentHashMap<>();
		final List<String> attached = new ArrayList<>();

		Repo() {
			super(null, null);
		}

		@Override
		public Optional<SessionEntity> find(UUID id) {
			return Optional.ofNullable(byId.get(id));
		}

		@Override
		public SessionEntity get(UUID id) {
			return find(id).orElseThrow(() -> new NoSuchElementException(id.toString()));
		}

		@Override
		public void attachSlackThread(UUID id, String channel, String threadTs) {
			attached.add(id + "@" + channel + "/" + threadTs);
			byId.computeIfPresent(id, (k, e) -> e.toBuilder().slackChannel(channel).slackThreadTs(threadTs).build());
		}
	}

	private final FakeSlack slack = new FakeSlack();
	private final Repo repo = new Repo();
	private final SessionEventBus bus = new SessionEventBus();
	private final AtomicLong seq = new AtomicLong();
	private SlackOutbox outbox;
	private SlackSessionMirror mirror;

	@BeforeEach
	void setUp() {
		outbox = new SlackOutbox(slack, Duration.ZERO, 100, d -> { });
		outbox.start();
		SlackProperties props = new SlackProperties(true, "xoxb-test", "xapp-test", "C123", List.of("U1"), false,
				"https://dash.example/", false);
		mirror = new SlackSessionMirror(props, outbox, repo, bus, id -> new BigDecimal("1.2500"));
		mirror.start();
	}

	@AfterEach
	void tearDown() {
		mirror.stop();
		outbox.stop();
	}

	private SessionEntity session(String name, boolean slack, UUID parent, String kind) {
		SessionEntity e = SessionEntity.builder().id(UUID.randomUUID()).name(name).provider("claude")
				.repoPath("/repo").branch("feat/" + name).baseBranch("main").worktreePath("/wt")
				.state(SessionState.CREATING).kind(kind).slackEnabled(slack).parentSessionId(parent)
				.kickoffPrompt("Implement " + name).build();
		repo.byId.put(e.id(), e);
		return e;
	}

	private SessionEntity session(String name) {
		return session(name, true, null, "user");
	}

	private void emit(SessionEntity e, String type, String payloadJson) {
		bus.publish(e.id(), new JournalEvent(seq.incrementAndGet(), Instant.now(), type, MAPPER.readTree(payloadJson)));
	}

	private void setState(SessionEntity e, SessionState state) {
		repo.byId.put(e.id(), repo.byId.get(e.id()).toBuilder().state(state).build());
		emit(e, "state_changed", "{\"state\":\"" + state.name() + "\"}");
	}

	private String parentTsOf(SessionEntity e) {
		FakeSlack.await(() -> repo.byId.get(e.id()).slackThreadTs() != null);
		return repo.byId.get(e.id()).slackThreadTs();
	}

	private static String textOf(FakeSlack.Call call) {
		return call.message().blocks().toString();
	}

	// ---------------------------------------------------------------- creation

	@Test
	void anOptedInSessionGetsOneParentMessageAndItsThreadIsPersisted() {
		SessionEntity s = session("login");
		emit(s, "state_changed", "{\"state\":\"CREATING\"}");
		FakeSlack.await(() -> slack.of("post").size() == 1);

		FakeSlack.Call parent = slack.of("post").get(0);
		assertThat(parent.channel()).isEqualTo("C123");
		assertThat(parent.extra()).isNull(); // top level, not in a thread
		assertThat(textOf(parent)).contains("login").contains("starting").contains("feat/login").contains("Implement login")
				.contains("session_stop").contains("https://dash.example");
		assertThat(parentTsOf(s)).isEqualTo(parent.ts());
		assertThat(repo.attached).containsExactly(s.id() + "@C123/" + parent.ts());
	}

	@Test
	void sessionsThatAreNotOptedInOrAreSystemSessionsPostNothing() throws Exception {
		SessionEntity off = session("off", false, null, "user");
		SessionEntity system = session("system", true, null, "system");
		emit(off, "state_changed", "{\"state\":\"CREATING\"}");
		emit(system, "state_changed", "{\"state\":\"CREATING\"}");
		emit(off, "turn_complete", "{\"stopReason\":\"end_turn\"}");
		Thread.sleep(300);
		assertThat(slack.calls).isEmpty();
	}

	@Test
	void anExistingThreadIsReusedInsteadOfPostingANewParent() {
		SessionEntity s = session("resumed");
		repo.byId.put(s.id(), s.toBuilder().slackChannel("C999").slackThreadTs("55.5").build());
		emit(s, "turn_complete", "{\"stopReason\":\"end_turn\",\"durationMs\":2000}");
		FakeSlack.await(() -> slack.of("post").size() == 1);
		FakeSlack.Call reply = slack.of("post").get(0);
		assertThat(reply.channel()).isEqualTo("C999");
		assertThat(reply.extra()).isEqualTo("55.5");
	}

	@Test
	void aSlackFailureNeverReachesThePublisher() throws Exception {
		slack.failures.add(new SlackApiException("chat.postMessage", "channel_not_found"));
		SessionEntity s = session("broken");
		emit(s, "state_changed", "{\"state\":\"CREATING\"}"); // must not throw
		emit(s, "turn_complete", "{\"stopReason\":\"end_turn\"}");
		Thread.sleep(300);
		assertThat(repo.attached).isEmpty();
	}

	// ---------------------------------------------------------------- thread content

	@Test
	void aPermissionRequestIsPostedInTheThreadWithMaskedCommandAndLaterSettled() {
		SessionEntity s = session("perm");
		emit(s, "state_changed", "{\"state\":\"CREATING\"}");
		String parentTs = parentTsOf(s);
		emit(s, "permission_request", "{\"requestId\":\"p1\",\"toolName\":\"Bash\",\"input\":"
				+ "{\"command\":\"export TOKEN=abc123 && deploy\"}}");
		FakeSlack.await(() -> slack.of("post").size() == 2);

		FakeSlack.Call prompt = slack.of("post").get(1);
		assertThat(prompt.extra()).isEqualTo(parentTs);
		assertThat(textOf(prompt)).contains("perm_allow").contains("perm_deny").contains("perm:" + s.id() + ":p1")
				.contains("export TOKEN=***").doesNotContain("abc123");

		emit(s, "permission_response", "{\"requestId\":\"p1\",\"behavior\":\"allow\"}");
		FakeSlack.await(() -> slack.of("update").stream().anyMatch(c -> c.ts().equals(prompt.ts())));
		FakeSlack.Call settled = slack.of("update").stream().filter(c -> c.ts().equals(prompt.ts())).findFirst().orElseThrow();
		assertThat(textOf(settled)).contains("Allowed on the dashboard").doesNotContain("perm_allow")
				.contains("export TOKEN=***").doesNotContain("abc123"); // the masked command stays, the buttons go
	}

	@Test
	void aPromptSettledFromSlackNamesWhoDecided() {
		SessionEntity s = session("who");
		emit(s, "state_changed", "{\"state\":\"CREATING\"}");
		parentTsOf(s);
		emit(s, "permission_request", "{\"requestId\":\"p1\",\"toolName\":\"Bash\",\"input\":{\"command\":\"ls\"}}");
		FakeSlack.await(() -> slack.of("post").size() == 2);
		emit(s, "permission_response", "{\"requestId\":\"p1\",\"behavior\":\"deny\",\"source\":\"slack\",\"slackUser\":\"U1\"}");
		FakeSlack.await(() -> slack.of("update").stream().anyMatch(c -> textOf(c).contains("Denied by <@U1>")));
	}

	@Test
	void aLongPlanIsPostedWithApprovalButtonsAndTheFullTextAsASnippet() {
		SessionEntity s = session("plan");
		emit(s, "state_changed", "{\"state\":\"CREATING\"}");
		parentTsOf(s);
		String plan = ("step " + "x".repeat(300) + "\\n\\n").repeat(60);
		emit(s, "permission_request", "{\"requestId\":\"p9\",\"toolName\":\"ExitPlanMode\",\"input\":{\"plan\":\"" + plan + "\"}}");
		FakeSlack.await(() -> slack.of("post").size() == 2 && slack.of("snippet").size() == 1);
		assertThat(textOf(slack.of("post").get(1))).contains("plan_approve").contains("plan_keep");
		assertThat(slack.of("snippet").get(0).extra()).startsWith("plan|");
	}

	@Test
	void aFinishedTurnPostsTheLastAssistantTextWithItsDuration() {
		SessionEntity s = session("turn");
		emit(s, "state_changed", "{\"state\":\"CREATING\"}");
		parentTsOf(s);
		emit(s, "assistant_message", "{\"content\":[{\"type\":\"text\",\"text\":\"Draft one\"}]}");
		emit(s, "assistant_message", "{\"content\":[{\"type\":\"text\",\"text\":\"Done. api_key=hunter2 is set\"}]}");
		emit(s, "assistant_message", "{\"content\":[{\"type\":\"text\",\"text\":\"sub-agent noise\"}],\"parentToolUseId\":\"t1\"}");
		emit(s, "turn_complete", "{\"stopReason\":\"end_turn\",\"durationMs\":72000}");
		FakeSlack.await(() -> slack.of("post").size() == 2);
		String text = textOf(slack.of("post").get(1));
		assertThat(text).contains("Turn finished").contains("1m 12s").contains("Done.")
				.contains("api_key=***").doesNotContain("hunter2").doesNotContain("Draft one").doesNotContain("noise");
	}

	@Test
	void aTurnEndedByAnInterruptIsLabelledInterruptedWhateverTheStopReason() {
		SessionEntity s = session("intr");
		emit(s, "state_changed", "{\"state\":\"CREATING\"}");
		parentTsOf(s);
		emit(s, "interrupt", "{}");
		emit(s, "turn_complete", "{\"stopReason\":\"error_during_execution\",\"durationMs\":19000}");
		emit(s, "user_message", "{\"text\":\"next\"}"); // a new turn: the earlier interrupt no longer applies
		emit(s, "turn_complete", "{\"stopReason\":\"success\",\"durationMs\":3000}");
		FakeSlack.await(() -> slack.of("post").size() == 3);
		assertThat(textOf(slack.of("post").get(1))).contains("Turn interrupted");
		assertThat(textOf(slack.of("post").get(2))).contains("Turn finished");
	}

	@Test
	void aFatalErrorKeepsOnlyItsFirstLineAndIsSkippedWhileTheSessionIsClosing() throws Exception {
		SessionEntity s = session("noisy");
		emit(s, "state_changed", "{\"state\":\"CREATING\"}");
		parentTsOf(s);
		emit(s, "error", "{\"fatal\":true,\"message\":\"provider failed\\n    at y.read (file:///home/me/secret/path.js:1:2)\"}");
		FakeSlack.await(() -> slack.of("post").size() == 2);
		assertThat(textOf(slack.of("post").get(1))).contains("provider failed").doesNotContain("file:///").doesNotContain("secret");
		emit(s, "state_changed", "{\"state\":\"CLOSING\"}");
		emit(s, "error", "{\"fatal\":true,\"message\":\"shutdown noise\"}");
		Thread.sleep(300);
		assertThat(slack.of("post").stream().map(SlackSessionMirrorTest::textOf)).noneMatch(t -> t.contains("shutdown noise"));
	}

	@Test
	void onlyFatalErrorsAreMirrored() throws Exception {
		SessionEntity s = session("err");
		emit(s, "state_changed", "{\"state\":\"CREATING\"}");
		parentTsOf(s);
		emit(s, "error", "{\"message\":\"guard denied a write\",\"fatal\":false}");
		emit(s, "error", "{\"message\":\"provider exited\",\"fatal\":true}");
		FakeSlack.await(() -> slack.of("post").size() == 2);
		Thread.sleep(150);
		assertThat(slack.of("post")).hasSize(2);
		assertThat(textOf(slack.of("post").get(1))).contains("provider exited");
	}

	@Test
	void aStopFromSlackLeavesAVisibleNoteNamingWhoAskedForIt() {
		SessionEntity s = session("stopped");
		emit(s, "state_changed", "{\"state\":\"CREATING\"}");
		parentTsOf(s);
		emit(s, "slack_input", "{\"source\":\"slack\",\"slackUser\":\"U1\",\"kind\":\"stop\"}");
		emit(s, "slack_input", "{\"source\":\"slack\",\"slackUser\":\"U1\",\"kind\":\"prompt\"}"); // prompts get a reaction instead
		FakeSlack.await(() -> slack.of("post").size() == 2);
		assertThat(textOf(slack.of("post").get(1))).contains("Stop requested").contains("<@U1>");
	}

	@Test
	void aCloseFromSlackLeavesANoteNamingWhoAskedForIt() {
		SessionEntity s = session("closing");
		emit(s, "state_changed", "{\"state\":\"CREATING\"}");
		parentTsOf(s);
		emit(s, "slack_input", "{\"source\":\"slack\",\"slackUser\":\"U1\",\"kind\":\"close\"}");
		FakeSlack.await(() -> slack.of("post").size() == 2);
		assertThat(textOf(slack.of("post").get(1))).contains("Close requested").contains("<@U1>");
	}

	@Test
	void aSessionStartedFromSlackSaysSoInItsThread() {
		SessionEntity s = session("started");
		emit(s, "state_changed", "{\"state\":\"CREATING\"}");
		parentTsOf(s);
		emit(s, "slack_input", "{\"source\":\"slack\",\"slackUser\":\"U1\",\"kind\":\"start\",\"tool\":\"review\"}");
		FakeSlack.await(() -> slack.of("post").size() == 2);
		assertThat(textOf(slack.of("post").get(1))).contains("Started from Slack by <@U1>").contains("review");
	}

	@Test
	void aPullRequestStatusChangeIsAnnounced() {
		SessionEntity s = session("pr");
		emit(s, "state_changed", "{\"state\":\"CREATING\"}");
		parentTsOf(s);
		emit(s, "pr_status_changed", "{\"url\":\"https://github.com/o/r/pull/3\",\"status\":\"SUCCESS\",\"previousStatus\":\"PENDING\"}");
		FakeSlack.await(() -> slack.of("post").size() == 2);
		assertThat(textOf(slack.of("post").get(1))).contains("https://github.com/o/r/pull/3").contains("PENDING").contains("SUCCESS");
	}

	// ---------------------------------------------------------------- parent updates

	@Test
	void minorStateChangesAreCoalescedIntoOneParentUpdate() throws Exception {
		SessionEntity s = session("busy");
		emit(s, "state_changed", "{\"state\":\"CREATING\"}");
		parentTsOf(s);
		setState(s, SessionState.PROVISIONING);
		setState(s, SessionState.RUNNING);
		setState(s, SessionState.RUNNING);
		setState(s, SessionState.RUNNING);
		FakeSlack.await(() -> !slack.of("update").isEmpty());
		Thread.sleep(400);
		assertThat(slack.of("update")).hasSize(1); // the next one is held back for 10 s
		assertThat(textOf(slack.of("update").get(0))).contains("busy");
	}

	@Test
	void aSessionThatBecomesIdleOrNeedsYouIsShownAtOnceNotAfterTheThrottle() {
		SessionEntity s = session("snappy");
		emit(s, "state_changed", "{\"state\":\"CREATING\"}");
		parentTsOf(s);
		setState(s, SessionState.RUNNING);          // first update goes out immediately
		FakeSlack.await(() -> slack.of("update").stream().anyMatch(c -> textOf(c).contains("working")));
		setState(s, SessionState.WAITING_INPUT);    // well inside the 10 s window
		FakeSlack.await(() -> slack.of("update").stream().anyMatch(c -> textOf(c).contains("waiting for you")));
		setState(s, SessionState.IDLE);
		FakeSlack.await(() -> slack.of("update").stream().anyMatch(c -> textOf(c).contains("idle")));
	}

	@Test
	void aDelayedMinorUpdateIsOvertakenByAnUrgentOne() {
		SessionEntity s = session("overtaken");
		emit(s, "state_changed", "{\"state\":\"CREATING\"}");
		parentTsOf(s);
		setState(s, SessionState.RUNNING);          // goes out at once
		FakeSlack.await(() -> !slack.of("update").isEmpty());
		setState(s, SessionState.PROVISIONING);     // minor: now scheduled ~10 s out
		setState(s, SessionState.IDLE);             // urgent: must not wait for that
		FakeSlack.await(() -> slack.of("update").stream().anyMatch(c -> textOf(c).contains("idle")));
	}

	// ---------------------------------------------------------------- closing

	@Test
	void closingPostsTheCostMarksTheParentDoneAndRemovesItsButtons() {
		SessionEntity s = session("done");
		emit(s, "state_changed", "{\"state\":\"CREATING\"}");
		String parentTs = parentTsOf(s);
		setState(s, SessionState.CLOSED);
		FakeSlack.await(() -> !slack.of("react").isEmpty() && !slack.of("update").isEmpty()
				&& slack.of("post").size() == 2);

		assertThat(textOf(slack.of("post").get(1))).contains("Session closed").contains("$1.25");
		assertThat(slack.of("react").get(0).ts()).isEqualTo(parentTs);
		assertThat(slack.of("react").get(0).extra()).isEqualTo("white_check_mark");
		FakeSlack.Call update = slack.of("update").get(0);
		assertThat(update.ts()).isEqualTo(parentTs);
		assertThat(textOf(update)).contains("closed").doesNotContain("session_stop");
	}

	// ---------------------------------------------------------------- children

	@Test
	void aChildPostsIntoItsParentsThreadTaggedWithItsName() {
		SessionEntity parent = session("parent");
		emit(parent, "state_changed", "{\"state\":\"CREATING\"}");
		String parentTs = parentTsOf(parent);
		SessionEntity child = session("api-service", false, parent.id(), "user");
		emit(child, "state_changed", "{\"state\":\"CREATING\"}");
		emit(child, "permission_request", "{\"requestId\":\"c1\",\"toolName\":\"Bash\",\"input\":{\"command\":\"ls\"}}");
		FakeSlack.await(() -> slack.of("post").size() == 3);

		assertThat(slack.of("post").stream().filter(c -> c.extra() == null)).hasSize(1); // only the parent is top level
		assertThat(slack.of("post").stream().skip(1)).allSatisfy(c -> assertThat(c.extra()).isEqualTo(parentTs));
		assertThat(textOf(slack.of("post").get(1))).contains("api-service").contains("started");
		assertThat(textOf(slack.of("post").get(2))).contains("api-service").contains("perm:" + child.id() + ":c1");
	}

	@Test
	void aChildOfASessionThatIsNotOptedInPostsNothing() throws Exception {
		SessionEntity parent = session("quiet", false, null, "user");
		SessionEntity child = session("kid", true, parent.id(), "user"); // its own flag is irrelevant
		emit(child, "state_changed", "{\"state\":\"CREATING\"}");
		emit(child, "turn_complete", "{\"stopReason\":\"end_turn\"}");
		Thread.sleep(300);
		assertThat(slack.calls).isEmpty();
	}

	@Test
	void anAnsweredQuestionShowsWhatWasAskedAndWhatWasChosen() {
		SessionEntity s = session("ask");
		emit(s, "state_changed", "{\"state\":\"CREATING\"}");
		parentTsOf(s);
		emit(s, "permission_request", "{\"requestId\":\"q1\",\"toolName\":\"AskUserQuestion\",\"input\":{\"questions\":[{\"question\":\"Which colour do you prefer?\",\"options\":[{\"label\":\"Green\"}]}]}}");
		FakeSlack.await(() -> slack.of("post").size() == 2);
		emit(s, "permission_response", "{\"requestId\":\"q1\",\"behavior\":\"allow\",\"source\":\"slack\",\"slackUser\":\"U1\",\"updatedInput\":{\"answers\":{\"Which colour do you prefer?\":\"Green\"}}}");
		FakeSlack.await(() -> slack.of("update").stream().anyMatch(c -> textOf(c).contains("Green")));
		String settled = slack.of("update").stream().filter(c -> textOf(c).contains("Green")).map(SlackSessionMirrorTest::textOf).findFirst().orElseThrow();
		assertThat(settled).contains("Which colour do you prefer?").contains("Answered").contains("\u201cGreen\u201d").contains("<@U1>");
	}

	@Test
	void moneyIsRoundedAndNeverShowsFloatingPointNoise() {
		assertThat(SlackSessionMirror.money(new BigDecimal("0.51796019999999994"))).isEqualTo("0.518");
		assertThat(SlackSessionMirror.money(new BigDecimal("1.2500"))).isEqualTo("1.25");
		assertThat(SlackSessionMirror.money(BigDecimal.ZERO)).isEqualTo("0.00");
		assertThat(SlackSessionMirror.money(new BigDecimal("3"))).isEqualTo("3.00");
	}

	@Test
	void formatDurationReadsNaturally() {
		assertThat(SlackSessionMirror.formatDuration(4_000)).isEqualTo("4s");
		assertThat(SlackSessionMirror.formatDuration(72_000)).isEqualTo("1m 12s");
		assertThat(SlackSessionMirror.formatDuration(3_900_000)).isEqualTo("1h 5m");
	}
}
