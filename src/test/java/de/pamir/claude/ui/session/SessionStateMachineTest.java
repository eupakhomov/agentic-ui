package de.pamir.claude.ui.session;

import de.pamir.claude.ui.config.AppProperties;
import de.pamir.claude.ui.config.Settings;
import de.pamir.claude.ui.config.SettingsService;
import de.pamir.claude.ui.discovery.ServiceDiscoveryRequested;
import de.pamir.claude.ui.journal.JournalPublisher;
import de.pamir.claude.ui.journal.SessionEventBus;
import de.pamir.claude.ui.memory.ReflectionRequested;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives SessionService's state machine directly — sendUserMessage routing per state,
 * becomeIdleAndDrainQueue's peek-not-pop/budget-hold semantics, close's dirty-mode branches,
 * resume's CRASHED-only guard, and onSidecarExit's crash-vs-shutdownRequested branches — against
 * in-memory fakes (FakeSessionRepository/FakeSidecarManager/FakeEventJournal/
 * FakeGitWorktreeService, no Mockito, no DB, no real OS process). See
 * docs/plan/phase-9-production-hardening.md T2.
 */
class SessionStateMachineTest {

	private final ObjectMapper mapper = new JsonMapper();

	private FakeSessionRepository sessions;
	private FakeSidecarManager sidecars;
	private FakeEventJournal journal;
	private FakeGitWorktreeService worktrees;
	private List<Object> publishedEvents;
	private SessionService sessionService;

	@TempDir
	Path worktreeRoot;

	@BeforeEach
	void setUp() {
		AppProperties props = new AppProperties(worktreeRoot.toString(), worktreeRoot.toString(), "/skills",
				"/memory", 4, "authtoken", "", "", "logs", 30, 65536, 1048576, Map.of());
		SettingsService settings = fakeSettings(false, false);
		sessions = new FakeSessionRepository();
		sidecars = new FakeSidecarManager();
		journal = new FakeEventJournal(props);
		worktrees = new FakeGitWorktreeService();
		JournalPublisher journalPublisher = new JournalPublisher(journal, new SessionEventBus());
		SessionConfigFactory configFactory = new SessionConfigFactory(props, settings, null, mapper, null, 8080, null);
		SystemSessionService systemSessionService =
				new SystemSessionService(props, settings, sessions, configFactory, journalPublisher, mapper, null);
		publishedEvents = new ArrayList<>();
		sessionService = new SessionService(props, settings, sessions, worktrees, null, null, sidecars, journal,
				journalPublisher, mapper, publishedEvents::add, configFactory, systemSessionService, null, null);
	}

	private static SettingsService fakeSettings(boolean memoryEnabled, boolean serviceDiscoveryEnabled) {
		Settings fixed = new Settings(false, "", "", true, 180, "", "", false, true, 60, "claude", "", "",
				memoryEnabled, false, "cheap", 5, 0, true, serviceDiscoveryEnabled, 14, "cheap");
		return new SettingsService(null, null, null) {
			@Override
			public Settings current() {
				return fixed;
			}
		};
	}

	private SessionEntity session(SessionState state) {
		SessionEntity entity = SessionEntity.builder()
				.id(UUID.randomUUID()).name("s").provider("claude").repoPath("/repo")
				.branch("b").baseBranch("main").worktreePath(worktreeRoot.resolve(UUID.randomUUID().toString()).toString())
				.contextDirs(List.of()).permissionMode("default").allowedTools(List.of()).disallowedTools(List.of())
				.state(state).kind("user")
				.build();
		sessions.seed(entity);
		return entity;
	}

	// ------------------------------------------------------------------ sendUserMessage

	@Test
	void sendUserMessageDispatchesImmediatelyWhenIdleWithALiveHandle() {
		SessionEntity s = session(SessionState.IDLE);
		sidecars.spawn(s, null, false, null, e -> {
		}, (h, c) -> {
		});

		sessionService.sendUserMessage(s.id(), "hello");

		assertThat(sessions.get(s.id()).state()).isEqualTo(SessionState.RUNNING);
		assertThat(sidecars.sentTo(s.id())).anyMatch(line -> line.contains("\"user_message\"") && line.contains("hello"));
		assertThat(sessions.queued(s.id())).isEmpty();
	}

	@Test
	void sendUserMessageRejectsWhenIdleButTheSidecarHandleIsDead() {
		SessionEntity s = session(SessionState.IDLE);
		// no spawn() call: hasLiveHandle() is false

		assertThatThrownBy(() -> sessionService.sendUserMessage(s.id(), "hello"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("does not accept messages");
	}

	@Test
	void sendUserMessageRejectsWhenIdleButBudgetIsExhausted() {
		SessionEntity s = session(SessionState.IDLE).toBuilder().costBudgetUsd(new BigDecimal("1.00")).build();
		sessions.seed(s);
		journal.setCostToDate(s.id(), new BigDecimal("1.00"));
		sidecars.spawn(s, null, false, null, e -> {
		}, (h, c) -> {
		});

		assertThatThrownBy(() -> sessionService.sendUserMessage(s.id(), "hello"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("budget exhausted");
		assertThat(sidecars.sentTo(s.id())).isEmpty();
	}

	@Test
	void sendUserMessageEnqueuesAndWakesAParkedSession() {
		SessionEntity s = session(SessionState.PARKED);

		sessionService.sendUserMessage(s.id(), "hello");

		assertThat(sessions.queued(s.id())).extracting("text").containsExactly("hello");
		assertThat(sessions.get(s.id()).state()).isEqualTo(SessionState.STARTING);
		assertThat(sidecars.hasLiveHandle(s.id())).isTrue(); // wake() spawned a fresh handle
	}

	@Test
	void sendUserMessageRejectsAParkedSessionWithAnExhaustedBudget() {
		SessionEntity s = session(SessionState.PARKED).toBuilder().costBudgetUsd(BigDecimal.ZERO).build();
		sessions.seed(s);
		journal.setCostToDate(s.id(), BigDecimal.ZERO);

		assertThatThrownBy(() -> sessionService.sendUserMessage(s.id(), "hello"))
				.isInstanceOf(IllegalStateException.class);
		assertThat(sessions.queued(s.id())).isEmpty();
		assertThat(sessions.get(s.id()).state()).isEqualTo(SessionState.PARKED);
	}

	@Test
	void sendUserMessageJustEnqueuesWhileAnotherTurnIsAlreadyInFlight() {
		SessionEntity s = session(SessionState.RUNNING);

		sessionService.sendUserMessage(s.id(), "hello");

		assertThat(sessions.queued(s.id())).extracting("text").containsExactly("hello");
		assertThat(sessions.get(s.id()).state()).isEqualTo(SessionState.RUNNING);
	}

	@Test
	void sendUserMessageJustEnqueuesWhileWaitingOnAPermissionPrompt() {
		SessionEntity s = session(SessionState.WAITING_INPUT);

		sessionService.sendUserMessage(s.id(), "hello");

		assertThat(sessions.queued(s.id())).extracting("text").containsExactly("hello");
	}

	@Test
	void sendUserMessageRejectsATerminalSession() {
		SessionEntity s = session(SessionState.CLOSED);

		assertThatThrownBy(() -> sessionService.sendUserMessage(s.id(), "hello"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("does not accept messages");
	}

	// ------------------------------------------------------------------ becomeIdleAndDrainQueue

	@Test
	void becomeIdleAndDrainQueueGoesIdleWithAnEmptyQueue() {
		SessionEntity s = session(SessionState.RUNNING);

		sessionService.becomeIdleAndDrainQueue(s.id());

		assertThat(sessions.get(s.id()).state()).isEqualTo(SessionState.IDLE);
	}

	@Test
	void becomeIdleAndDrainQueueDispatchesTheHeadOfTheQueueAndRemovesItOnSuccess() {
		SessionEntity s = session(SessionState.RUNNING);
		sidecars.spawn(s, null, false, null, e -> {
		}, (h, c) -> {
		});
		sessions.enqueue(s.id(), "queued message");

		sessionService.becomeIdleAndDrainQueue(s.id());

		assertThat(sessions.get(s.id()).state()).isEqualTo(SessionState.RUNNING); // dispatch() re-enters RUNNING
		assertThat(sessions.queued(s.id())).isEmpty();
		assertThat(sidecars.sentTo(s.id())).anyMatch(line -> line.contains("queued message"));
	}

	@Test
	void becomeIdleAndDrainQueueLeavesTheMessageQueuedWhenSendFails() {
		SessionEntity s = session(SessionState.RUNNING);
		sidecars.spawn(s, null, false, null, e -> {
		}, (h, c) -> {
		});
		sidecars.brokenHandles.add(s.id()); // simulates a dead/broken sidecar handle
		sessions.enqueue(s.id(), "queued message");

		sessionService.becomeIdleAndDrainQueue(s.id());

		assertThat(sessions.get(s.id()).state()).isEqualTo(SessionState.IDLE); // never reached dispatch()'s RUNNING
		assertThat(sessions.queued(s.id())).extracting("text").containsExactly("queued message");
	}

	@Test
	void becomeIdleAndDrainQueueHoldsAQueueWhenTheBudgetIsExhausted() {
		SessionEntity s = session(SessionState.RUNNING).toBuilder().costBudgetUsd(new BigDecimal("5")).build();
		sessions.seed(s);
		journal.setCostToDate(s.id(), new BigDecimal("5"));
		sessions.enqueue(s.id(), "queued message");

		sessionService.becomeIdleAndDrainQueue(s.id());

		assertThat(sessions.get(s.id()).state()).isEqualTo(SessionState.IDLE);
		assertThat(sessions.queued(s.id())).extracting("text").containsExactly("queued message");
	}

	// ------------------------------------------------------------------ resume

	@Test
	void resumeRejectsANonCrashedSession() {
		SessionEntity s = session(SessionState.IDLE);

		assertThatThrownBy(() -> sessionService.resume(s.id()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("only CRASHED sessions can be resumed");
	}

	@Test
	void resumeRespawnsACrashedSession() {
		SessionEntity s = session(SessionState.CRASHED);

		sessionService.resume(s.id());

		assertThat(sessions.get(s.id()).state()).isEqualTo(SessionState.STARTING);
		assertThat(sidecars.hasLiveHandle(s.id())).isTrue();
	}

	// ------------------------------------------------------------------ close

	@Test
	void closeIsANoOpWhenAlreadyClosed() {
		SessionEntity s = session(SessionState.CLOSED);

		sessionService.close(s.id(), null, null);

		assertThat(sessions.get(s.id()).state()).isEqualTo(SessionState.CLOSED);
	}

	@Test
	void closeThrowsOnADirtyWorktreeByDefault() {
		SessionEntity s = session(SessionState.IDLE);
		worktrees.setDirtyFiles(List.of("a.txt"));

		assertThatThrownBy(() -> sessionService.close(s.id(), null, null))
				.isInstanceOf(DirtyWorktreeException.class);
		assertThat(sessions.get(s.id()).state()).isEqualTo(SessionState.IDLE);
	}

	@Test
	void closeRejectsAnUnknownDirtyMode() {
		SessionEntity s = session(SessionState.IDLE);
		worktrees.setDirtyFiles(List.of("a.txt"));

		assertThatThrownBy(() -> sessionService.close(s.id(), "bogus", null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("unknown dirty mode");
	}

	@Test
	void closeDiscardsADirtyWorktreeWhenAskedTo() {
		SessionEntity s = session(SessionState.IDLE);
		worktrees.setDirtyFiles(List.of("a.txt"));

		sessionService.close(s.id(), "discard", null);

		assertThat(sessions.get(s.id()).state()).isEqualTo(SessionState.CLOSED);
	}

	@Test
	void closeSucceedsDirectlyOnACleanWorktree() {
		SessionEntity s = session(SessionState.IDLE);

		sessionService.close(s.id(), null, null);

		assertThat(sessions.get(s.id()).state()).isEqualTo(SessionState.CLOSED);
	}

	@Test
	void closeReleasesTheSessionLockAndTheJournalNoLongerNeedsToBeQueried() {
		SessionEntity s = session(SessionState.IDLE);

		sessionService.close(s.id(), null, null);

		assertThat(sessionService.hasLock(s.id())).isFalse();
		// FakeEventJournal doesn't track a real in-memory session map to assert against directly,
		// but readAfter (unaffected by release — see EventJournal.release's javadoc) must still
		// work for a released session's event history, same as it does for a never-seen one.
		assertThat(journal.readAfter(s.id(), 0)).isNotNull();
	}

	@Test
	void closePublishesReflectionAndServiceDiscoveryEventsWhenEnabled() {
		SessionConfigFactory configFactory = new SessionConfigFactory(
				new AppProperties(worktreeRoot.toString(), worktreeRoot.toString(), "/skills", "/memory", 4,
						"authtoken", "", "", "logs", 30, 65536, 1048576, Map.of()),
				fakeSettings(false, true), null, mapper, null, 8080, null);
		SettingsService settings = fakeSettings(false, true);
		JournalPublisher journalPublisher = new JournalPublisher(journal, new SessionEventBus());
		SystemSessionService systemSessionService =
				new SystemSessionService(null, settings, sessions, configFactory, journalPublisher, mapper, null);
		AppProperties props = new AppProperties(worktreeRoot.toString(), worktreeRoot.toString(), "/skills",
				"/memory", 4, "authtoken", "", "", "logs", 30, 65536, 1048576, Map.of());
		SessionService withDiscovery = new SessionService(props, settings, sessions, worktrees, null, null, sidecars,
				journal, journalPublisher, mapper, publishedEvents::add, configFactory, systemSessionService, null, null);
		SessionEntity s = session(SessionState.IDLE).toBuilder().reflectionEnabled(true).build();
		sessions.seed(s);

		withDiscovery.close(s.id(), null, null);

		assertThat(publishedEvents).hasAtLeastOneElementOfType(ReflectionRequested.class);
		assertThat(publishedEvents).hasAtLeastOneElementOfType(ServiceDiscoveryRequested.class);
	}

	@Test
	void closeTerminatesASystemSessionAndSkipsTheDirtyCheck() {
		SessionEntity s = SessionEntity.builder()
				.id(UUID.randomUUID()).name("system").provider("claude").repoPath("(system)")
				.branch("(system)").baseBranch("(system)").worktreePath(worktreeRoot.resolve("nonexistent").toString())
				.contextDirs(List.of()).permissionMode("default").allowedTools(List.of()).disallowedTools(List.of())
				.state(SessionState.IDLE).kind("system")
				.build();
		sessions.seed(s);
		worktrees.setDirtyFiles(List.of("would normally block")); // must be ignored for kind=system

		sessionService.close(s.id(), null, null);

		assertThat(sessions.get(s.id()).state()).isEqualTo(SessionState.CLOSED);
	}

	// ------------------------------------------------------------------ onSidecarExit

	@Test
	void onSidecarExitIgnoresAnExpectedShutdown() {
		SessionEntity s = session(SessionState.RUNNING);

		sessionService.onSidecarExit(s.id(), 0, List.of(), true);

		assertThat(sessions.get(s.id()).state()).isEqualTo(SessionState.RUNNING);
	}

	@Test
	void onSidecarExitIsANoOpForATerminalState() {
		SessionEntity s = session(SessionState.CLOSED);

		sessionService.onSidecarExit(s.id(), 1, List.of(), false);

		assertThat(sessions.get(s.id()).state()).isEqualTo(SessionState.CLOSED);
	}

	@Test
	void onSidecarExitMarksAnUnexpectedExitAsCrashed() {
		SessionEntity s = session(SessionState.RUNNING);

		sessionService.onSidecarExit(s.id(), 1, List.of("stack trace line"), false);

		assertThat(sessions.get(s.id()).state()).isEqualTo(SessionState.CRASHED);
	}

	@Test
	void onSidecarExitIsANoOpWhenTheSessionIsAlreadyGone() {
		UUID unknown = UUID.randomUUID();

		sessionService.onSidecarExit(unknown, 1, List.of(), false); // must not throw
	}
}
