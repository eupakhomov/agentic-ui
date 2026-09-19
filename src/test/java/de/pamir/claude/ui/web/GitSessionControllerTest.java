package de.pamir.claude.ui.web;

import de.pamir.claude.ui.git.GitOpsService;
import de.pamir.claude.ui.session.GitAssistService;
import de.pamir.claude.ui.session.SessionEntity;
import de.pamir.claude.ui.session.SessionRepository;
import de.pamir.claude.ui.session.SessionState;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The write-path guard in {@link GitSessionController#worktree} — refuses commit/push/pr outright
 * for a review session (docs/plan/phase-15-review-sessions.md proposal 10), mapped to a 409 by the
 * existing {@link ApiExceptionHandler} (unit-tested at the exception-type level here, matching this
 * codebase's no-MockMvc convention — see docs/plan/phase-9-production-hardening.md T2).
 */
class GitSessionControllerTest {

	private static SessionEntity session(String sessionType, SessionState state) {
		return SessionEntity.builder()
				.id(UUID.randomUUID()).name("s").provider("claude").repoPath("/repo")
				.branch("b").baseBranch("main").worktreePath("/worktree")
				.contextDirs(List.of()).permissionMode("default").allowedTools(List.of()).disallowedTools(List.of())
				.state(state).kind("user").sessionType(sessionType)
				.build();
	}

	/** Minimal fake — only {@code get} is exercised by the controller's read/write paths under test. */
	private static SessionRepository fakeSessions(SessionEntity entity) {
		return new SessionRepository(null, null) {
			@Override
			public SessionEntity get(UUID id) {
				return entity;
			}

			@Override
			public Optional<SessionEntity> find(UUID id) {
				return Optional.of(entity);
			}
		};
	}

	@Test
	void commitIsRefusedOnAReviewSession() {
		SessionEntity review = session("review", SessionState.IDLE);
		GitSessionController controller = new GitSessionController(fakeSessions(review), null, new GitAssistService(null, null, null));

		assertThatThrownBy(() -> controller.commit(review.id(), new GitSessionController.CommitRequest("msg")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("review session");
	}

	@Test
	void pushIsRefusedOnAReviewSession() {
		SessionEntity review = session("review", SessionState.IDLE);
		GitSessionController controller = new GitSessionController(fakeSessions(review), null, new GitAssistService(null, null, null));

		assertThatThrownBy(() -> controller.push(review.id()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("review session");
	}

	@Test
	void createPrIsRefusedOnAReviewSession() {
		SessionEntity review = session("review", SessionState.IDLE);
		GitSessionController controller = new GitSessionController(fakeSessions(review), null, new GitAssistService(null, null, null));

		assertThatThrownBy(() -> controller.createPr(review.id(), new GitSessionController.PrRequest("t", "b")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("review session");
	}

	@Test
	void statusStillWorksOnAReviewSessionSinceReadsAreNotGated() {
		SessionEntity review = session("review", SessionState.IDLE);
		GitOpsService gitOps = new GitOpsService(null, null, null) {
			@Override
			public GitStatus status(java.nio.file.Path worktree, String baseBranch) {
				return new GitStatus("b", List.of(), null, 0, 0, 0);
			}
		};
		GitSessionController controller = new GitSessionController(fakeSessions(review), gitOps, new GitAssistService(null, null, null));

		assertThat(controller.status(review.id()).branch()).isEqualTo("b");
	}

	@Test
	void commitStillRefusedMidTurnOnADevelopmentSessionUnchangedFromBeforeThisPhase() {
		SessionEntity running = session("development", SessionState.RUNNING);
		GitSessionController controller = new GitSessionController(fakeSessions(running), null, new GitAssistService(null, null, null));

		assertThatThrownBy(() -> controller.commit(running.id(), new GitSessionController.CommitRequest("msg")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("wait for the turn to finish");
	}
}
