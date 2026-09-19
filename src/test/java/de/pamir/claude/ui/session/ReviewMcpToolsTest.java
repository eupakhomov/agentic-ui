package de.pamir.claude.ui.session;

import de.pamir.claude.ui.git.GitOpsService;
import de.pamir.claude.ui.journal.JournalPublisher;
import de.pamir.claude.ui.journal.SessionEventBus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ReviewMcpTools#submitPrReview} against a faked {@link GitOpsService} (no real {@code gh}
 * process) and {@link FakeSessionRepository}/{@link FakeEventJournal} — this codebase's no-Mockito
 * convention (see docs/plan/phase-9-production-hardening.md T2).
 */
class ReviewMcpToolsTest {

	private static SessionEntity session(String sessionType, String prUrl) {
		return SessionEntity.builder()
				.id(UUID.randomUUID()).name("s").provider("claude").repoPath("/repo")
				.branch("feat-x").baseBranch("main").worktreePath("/worktree")
				.contextDirs(List.of()).permissionMode("default").allowedTools(List.of()).disallowedTools(List.of())
				.state(SessionState.IDLE).kind("user").sessionType(sessionType).prUrl(prUrl)
				.build();
	}

	private record Call(String kind, GitOpsService.PrRef pr, String event, String body, List<GitOpsService.ReviewComment> comments) {
	}

	private static GitOpsService fakeGitOps(Optional<GitOpsService.PrInfo> resolved, List<Call> calls, RuntimeException submitFailure) {
		return new GitOpsService(null, null, new JsonMapper()) {
			@Override
			public Optional<GitOpsService.PrInfo> resolvePr(Path worktree, String branch) {
				calls.add(new Call("resolvePr", null, null, null, null));
				return resolved;
			}

			@Override
			public String headSha(Path worktree) {
				return "deadbeef";
			}

			@Override
			public void submitPrReview(Path worktree, GitOpsService.PrRef pr, String event, String body, List<GitOpsService.ReviewComment> comments) {
				calls.add(new Call("submitPrReview", pr, event, body, comments));
				if (submitFailure != null) {
					throw submitFailure;
				}
			}
		};
	}

	private ReviewMcpTools tools(SessionRepository sessions, GitOpsService gitOps, FakeEventJournal journal) {
		JournalPublisher journalPublisher = new JournalPublisher(journal, new SessionEventBus());
		return new ReviewMcpTools(sessions, gitOps, journalPublisher, new JsonMapper());
	}

	@Test
	void happyPathPostsTheReviewAndJournalsTheEvent() {
		FakeSessionRepository sessions = new FakeSessionRepository();
		SessionEntity session = session("review", "https://github.com/acme/widget/pull/7");
		sessions.seed(session);
		List<Call> calls = new java.util.ArrayList<>();
		GitOpsService gitOps = fakeGitOps(Optional.empty(), calls, null);
		FakeEventJournal journal = new FakeEventJournal(fakeProps());
		ReviewMcpTools tools = tools(sessions, gitOps, journal);

		String result = tools.submitPrReview(session.id().toString(), "COMMENT", "looks good",
				List.of(new ReviewMcpTools.ReviewCommentInput("src/Foo.java", 10, null, null, "nit")));

		assertThat(result).contains("submitted COMMENT review");
		assertThat(calls).hasSize(1); // resolvePr never called — prUrl was already attached
		assertThat(calls.get(0).kind()).isEqualTo("submitPrReview");
		assertThat(calls.get(0).pr()).isEqualTo(new GitOpsService.PrRef("acme", "widget", 7));
		var event = journal.firstEventOfType(session.id(), "pr_review_submitted").orElseThrow();
		assertThat(event.payload().path("commentCount").asInt()).isEqualTo(1);
		assertThat(event.payload().path("prUrl").asText()).isEqualTo("https://github.com/acme/widget/pull/7");
	}

	@Test
	void defaultsEventToCommentWhenOmitted() {
		FakeSessionRepository sessions = new FakeSessionRepository();
		SessionEntity session = session("review", "https://github.com/acme/widget/pull/7");
		sessions.seed(session);
		List<Call> calls = new java.util.ArrayList<>();
		GitOpsService gitOps = fakeGitOps(Optional.empty(), calls, null);
		ReviewMcpTools tools = tools(sessions, gitOps, new FakeEventJournal(fakeProps()));

		tools.submitPrReview(session.id().toString(), null, "summary", null);

		assertThat(calls.get(0).event()).isEqualTo("COMMENT");
		assertThat(calls.get(0).comments()).isEmpty();
	}

	@Test
	void rejectsAnUnknownEventValue() {
		FakeSessionRepository sessions = new FakeSessionRepository();
		SessionEntity session = session("review", "https://github.com/acme/widget/pull/7");
		sessions.seed(session);
		ReviewMcpTools tools = tools(sessions, fakeGitOps(Optional.empty(), new java.util.ArrayList<>(), null), new FakeEventJournal(fakeProps()));

		assertThatThrownBy(() -> tools.submitPrReview(session.id().toString(), "BOGUS", "summary", null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("event must be one of");
	}

	@Test
	void aDevelopmentSessionCallingItGetsAToolError() {
		FakeSessionRepository sessions = new FakeSessionRepository();
		SessionEntity session = session("development", null);
		sessions.seed(session);
		ReviewMcpTools tools = tools(sessions, fakeGitOps(Optional.empty(), new java.util.ArrayList<>(), null), new FakeEventJournal(fakeProps()));

		assertThatThrownBy(() -> tools.submitPrReview(session.id().toString(), null, "summary", null))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("only usable from a review session");
	}

	@Test
	void lazilyResolvesAndAttachesThePrWhenNoneWasSetAtCreation() {
		FakeSessionRepository sessions = new FakeSessionRepository();
		SessionEntity session = session("review", null); // branch-first: no prUrl attached at creation
		sessions.seed(session);
		List<Call> calls = new java.util.ArrayList<>();
		GitOpsService.PrInfo prInfo = new GitOpsService.PrInfo(9, "t", "feat-x", "main",
				"https://github.com/acme/widget/pull/9", "bob", false);
		GitOpsService gitOps = fakeGitOps(Optional.of(prInfo), calls, null);
		ReviewMcpTools tools = tools(sessions, gitOps, new FakeEventJournal(fakeProps()));

		tools.submitPrReview(session.id().toString(), null, "summary", null);

		assertThat(sessions.get(session.id()).prUrl()).isEqualTo("https://github.com/acme/widget/pull/9");
		assertThat(calls).extracting(Call::kind).containsExactly("resolvePr", "submitPrReview");
	}

	@Test
	void noPrFoundAndUnresolvableBranchIsAReadableError() {
		FakeSessionRepository sessions = new FakeSessionRepository();
		SessionEntity session = session("review", null);
		sessions.seed(session);
		GitOpsService gitOps = fakeGitOps(Optional.empty(), new java.util.ArrayList<>(), null);
		ReviewMcpTools tools = tools(sessions, gitOps, new FakeEventJournal(fakeProps()));

		assertThatThrownBy(() -> tools.submitPrReview(session.id().toString(), null, "summary", null))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("no PR found").hasMessageContaining("report your findings in the transcript");
	}

	@Test
	void a422ErrorBodyIsSurfacedVerbatim() {
		FakeSessionRepository sessions = new FakeSessionRepository();
		SessionEntity session = session("review", "https://github.com/acme/widget/pull/7");
		sessions.seed(session);
		GitOpsService gitOps = fakeGitOps(Optional.empty(), new java.util.ArrayList<>(),
				new IllegalStateException("gh api pulls/reviews failed: 422: comment path does not match diff"));
		ReviewMcpTools tools = tools(sessions, gitOps, new FakeEventJournal(fakeProps()));

		assertThatThrownBy(() -> tools.submitPrReview(session.id().toString(), null, "summary", null))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("422");
	}

	private static de.pamir.claude.ui.config.AppProperties fakeProps() {
		return new de.pamir.claude.ui.config.AppProperties("/repo", "/worktrees", "/skills", "/memory", 4,
				"authtoken", "", "", "logs", 30, 65536, 1048576, java.util.Map.of());
	}
}
