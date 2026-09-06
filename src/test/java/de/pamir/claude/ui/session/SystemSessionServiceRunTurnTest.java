package de.pamir.claude.ui.session;

import de.pamir.claude.ui.config.AppProperties;
import de.pamir.claude.ui.config.SettingsService;
import de.pamir.claude.ui.journal.JournalPublisher;
import de.pamir.claude.ui.journal.SessionEventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives {@link SystemSessionService#runSystemTurn}'s pendingSystemTurn/pendingSystemText
 * handshake end to end — successful completion, timeout, crash, and the model-override/restore
 * dance — against a fully-wired real SessionService/SystemSessionService pair (the same
 * two-way collaboration production uses, just backed by in-memory fakes). See
 * docs/plan/phase-9-production-hardening.md T2.
 *
 * <p>SystemSessionService's constructor dependency on SessionService is {@code @Lazy} in
 * production specifically to let Spring construct this cycle; a plain {@code new} can't express
 * that, so this test seeds the same back-reference via reflection once both objects exist —
 * standing in for what Spring's lazy proxy resolves automatically.
 */
class SystemSessionServiceRunTurnTest {

	private final ObjectMapper mapper = new JsonMapper();

	private FakeSessionRepository sessions;
	private FakeSidecarManager sidecars;
	private SystemSessionService systemSessionService;
	private UUID systemId;

	@TempDir
	Path worktreeRoot;

	@BeforeEach
	void setUp() throws ReflectiveOperationException {
		AppProperties props = new AppProperties(worktreeRoot.toString(), worktreeRoot.toString(), "/skills",
				"/memory", 4, "authtoken", "", "", "logs", 30, 65536, 1048576, Map.of());
		SettingsService settings = new SettingsService(null, null, null);
		sessions = new FakeSessionRepository();
		sidecars = new FakeSidecarManager();
		JournalPublisher journalPublisher = new JournalPublisher(new FakeEventJournal(props), new SessionEventBus());
		SessionConfigFactory configFactory = new SessionConfigFactory(props, settings, null, mapper, null, 8080);
		systemSessionService = new SystemSessionService(props, settings, sessions, configFactory, journalPublisher,
				mapper, null);
		SessionService sessionService = new SessionService(props, settings, sessions, null, null, null, sidecars,
				new FakeEventJournal(props), journalPublisher, mapper, event -> {
		}, configFactory, systemSessionService, null);
		bindLazySessionService(systemSessionService, sessionService);

		systemId = UUID.randomUUID();
		SessionEntity system = SessionEntity.builder()
				.id(systemId).name("system").provider("claude").repoPath("(system)")
				.branch("(system)").baseBranch("(system)").worktreePath(worktreeRoot.toString())
				.contextDirs(List.of()).permissionMode("default").allowedTools(List.of()).disallowedTools(List.of())
				.model("original-model").state(SessionState.IDLE).kind("system")
				.build();
		sessions.seed(system);
		sidecars.spawn(system, null, false, null, e -> {
		}, (h, c) -> {
		});
	}

	/** Mirrors what Spring's {@code @Lazy} proxy resolves automatically in production. */
	private static void bindLazySessionService(SystemSessionService systemSessionService,
												SessionService sessionService) throws ReflectiveOperationException {
		Field field = SystemSessionService.class.getDeclaredField("sessionService");
		field.setAccessible(true);
		field.set(systemSessionService, sessionService);
	}

	private ArrayNode textContent(String text) {
		ArrayNode content = mapper.createArrayNode();
		content.add(mapper.createObjectNode().put("type", "text").put("text", text));
		return content;
	}

	@Test
	void runSystemTurnReturnsTheAccumulatedAssistantTextOnCompletion() throws InterruptedException {
		AtomicReference<String> result = new AtomicReference<>();
		Thread caller = Thread.ofVirtual().start(() ->
				result.set(systemSessionService.runSystemTurn("prompt", SystemTurnLane.INTERACTIVE, java.time.Duration.ofSeconds(2))));

		awaitPendingTurn();
		systemSessionService.onAssistantMessage(systemId, textContent("the answer"));
		systemSessionService.completeTurn(systemId);
		caller.join(2000);

		assertThat(result.get()).isEqualTo("the answer");
	}

	@Test
	void runSystemTurnThrowsAfterItsOwnTimeoutElapses() {
		assertThatThrownBy(() -> systemSessionService.runSystemTurn("prompt", SystemTurnLane.BACKGROUND, java.time.Duration.ofSeconds(1)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("timed out");
	}

	@Test
	void runSystemTurnSurfacesAFatalSidecarErrorAsAFailure() throws InterruptedException {
		AtomicReference<Throwable> error = new AtomicReference<>();
		Thread caller = Thread.ofVirtual().start(() -> {
			try {
				systemSessionService.runSystemTurn("prompt", SystemTurnLane.INTERACTIVE, java.time.Duration.ofSeconds(2));
			} catch (RuntimeException e) {
				error.set(e);
			}
		});

		awaitPendingTurn();
		systemSessionService.failTurn(systemId, new IllegalStateException("system session crashed (exit 1)"));
		caller.join(2000);

		assertThat(error.get()).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("system task failed").hasMessageContaining("crashed (exit 1)");
	}

	@Test
	void runSystemTurnSwitchesToTheOverrideModelAndRestoresItAfterward() throws InterruptedException {
		Thread caller = Thread.ofVirtual().start(() ->
				systemSessionService.runSystemTurn("prompt", "override-model", SystemTurnLane.INTERACTIVE, java.time.Duration.ofSeconds(2)));

		awaitPendingTurn();
		assertThat(sidecars.sentTo(systemId)).anyMatch(line -> line.contains("\"set_model\"") && line.contains("override-model"));

		systemSessionService.completeTurn(systemId);
		caller.join(2000);

		assertThat(sidecars.sentTo(systemId)).anyMatch(line -> line.contains("\"set_model\"") && line.contains("original-model"));
	}

	/** No hook to await the pendingSystemTurn assignment directly (it's a private field, by design) —
	 * everything before it is non-blocking in-memory work, so a short sleep is a safe, deterministic
	 * enough stand-in for "the caller thread has reached future.get()". */
	private static void awaitPendingTurn() throws InterruptedException {
		Thread.sleep(100);
	}
}
