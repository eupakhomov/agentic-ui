package de.pamir.claude.ui.session;

import de.pamir.claude.ui.config.AppProperties;
import de.pamir.claude.ui.config.SettingsService;
import de.pamir.claude.ui.journal.JournalPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Owns the singleton backend-initiated "system session" (ticket import, library AI-fill,
 * reflection, service discovery, commit/PR drafting, handoff briefs, auto-titling all run a turn
 * on it) — find-or-create/wake/resume, the single-turn-in-flight lock, and the
 * pendingSystemTurn/pendingSystemText handshake that stitches the sidecar's assistant_message/
 * turn_complete/error events back onto the waiting caller's future. Extracted out of
 * SessionService (see docs/plan/phase-9-production-hardening.md S1) because this cluster is the
 * riskiest, most independently-testable logic in the class (T2). SessionService still owns the
 * general session state machine and calls into this class's package-private hooks from
 * onSidecarEvent/onSidecarExit; this class calls back into SessionService's package-private
 * session-mechanics (transition/spawn/wake/record/sendUserMessage/setModel/resume) — a genuine
 * two-way dependency, so the edge from here back to SessionService is {@code @Lazy} to let Spring
 * construct the cycle (SessionService's own dependency on this class is a plain, eager one).
 */
@Service
public class SystemSessionService {

	private final AppProperties props;
	private final SettingsService settings;
	private final SessionRepository sessions;
	private final SessionConfigFactory configFactory;
	private final JournalPublisher journalPublisher;
	private final ObjectMapper mapper;
	private final SessionService sessionService;

	private static final Duration INTERACTIVE_LOCK_WAIT = Duration.ofSeconds(10);
	/** Extra budget, on top of the caller's own {@code timeout}, for reviving a parked/crashed
	 * system session (spawn + provider handshake) before {@link #runSystemTurnLocked} even
	 * reaches its own timed wait — see runSystemTurn's javadoc. */
	private static final Duration REVIVAL_BUDGET = Duration.ofSeconds(30);

	// Exactly one system turn in flight at a time; see class javadoc and S2's INTERACTIVE/BACKGROUND
	// lanes. A Semaphore, not a ReentrantLock: the turn body now runs on turnExecutor and releases
	// this itself once done (see runSystemTurn's javadoc) — a ReentrantLock requires the same
	// thread to lock and unlock, which a background-thread release would violate
	// (IllegalMonitorStateException); a binary fair semaphore has no such ownership constraint.
	private final Semaphore systemSessionLock = new Semaphore(1, true);
	private volatile UUID pendingSystemTurnSessionId;
	private volatile CompletableFuture<String> pendingSystemTurn;
	private volatile String pendingSystemText = "";
	// runs runSystemTurnLocked's body so this call's own wait can be bounded end-to-end (see
	// runSystemTurn's javadoc) rather than only bounding its last step
	private final ExecutorService turnExecutor = Executors.newVirtualThreadPerTaskExecutor();

	public SystemSessionService(AppProperties props, SettingsService settings, SessionRepository sessions,
								 SessionConfigFactory configFactory, JournalPublisher journalPublisher,
								 ObjectMapper mapper, @Lazy SessionService sessionService) {
		this.props = props;
		this.settings = settings;
		this.sessions = sessions;
		this.configFactory = configFactory;
		this.journalPublisher = journalPublisher;
		this.mapper = mapper;
		this.sessionService = sessionService;
	}

	/**
	 * Runs one backend-initiated turn and returns the assistant's final text. {@code lane}
	 * decides how long this call is willing to wait for another in-flight system turn to finish
	 * before giving up (see {@link SystemTurnLane}) — the turn itself still gets its full {@code
	 * timeout} once the lock is acquired.
	 */
	public String runSystemTurn(String prompt, SystemTurnLane lane, Duration timeout) {
		return runSystemTurn(prompt, null, lane, timeout);
	}

	/**
	 * Same as {@link #runSystemTurn(String, SystemTurnLane, Duration)}, but switches the system
	 * session to {@code modelOverride} for this one turn and back to its normal model afterward
	 * (used by reflection to run on a different model than the system session's default haiku —
	 * see docs/plan/phase-5.3-memory-reflection.md decision 7). {@code null} keeps the current
	 * model.
	 *
	 * <p>Once the lock is acquired, the actual turn (revival, dispatch, the wait, and the
	 * model-revert) runs on {@link #turnExecutor} so THIS call's wait is bounded end to end —
	 * {@code timeout} plus a fixed {@link #REVIVAL_BUDGET} for spawn/handshake — rather than only
	 * bounding the final wait-for-completion step. Previously, a stuck revival or a stuck
	 * {@code sendUserMessage} (its journal write, in particular) blocked the caller forever with
	 * no timeout ever firing. The background task, not this method, releases
	 * {@link #systemSessionLock} once it actually finishes, however long that takes:
	 * {@link #pendingSystemTurn}/{@link #pendingSystemText} are shared mutable state, so unlocking
	 * here while that task might still be running would let a second caller start a new turn the
	 * abandoned one could later stomp on.
	 */
	public String runSystemTurn(String prompt, String modelOverride, SystemTurnLane lane, Duration timeout) {
		Duration lockWait = lane == SystemTurnLane.INTERACTIVE ? INTERACTIVE_LOCK_WAIT : timeout;
		boolean acquired;
		try {
			acquired = systemSessionLock.tryAcquire(lockWait.toMillis(), TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while waiting for the system session");
		}
		if (!acquired) {
			throw new IllegalStateException("system session is busy with another task; try again shortly");
		}
		Future<String> body = turnExecutor.submit(() -> {
			try {
				return runSystemTurnLocked(prompt, modelOverride, timeout);
			} finally {
				systemSessionLock.release();
			}
		});
		try {
			return body.get(timeout.plus(REVIVAL_BUDGET).toSeconds(), TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			throw new IllegalStateException("system task timed out after " + timeout.toSeconds() + "s");
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException re) {
				throw re;
			}
			throw new IllegalStateException("system task failed: "
					+ (cause != null ? cause.getMessage() : e.getMessage()));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while waiting for system task");
		}
	}

	/** Runs the turn body; caller must hold {@link #systemSessionLock} and release it. */
	private String runSystemTurnLocked(String prompt, String modelOverride, Duration timeout) {
		SessionEntity session = getOrCreateSystemSession();
		String originalModel = session.model();
		boolean switchModel = modelOverride != null && !modelOverride.equals(originalModel);
		if (switchModel) {
			sessionService.setModel(session.id(), modelOverride);
		}
		pendingSystemText = "";
		CompletableFuture<String> future = new CompletableFuture<>();
		pendingSystemTurn = future;
		pendingSystemTurnSessionId = session.id();
		try {
			sessionService.sendUserMessage(session.id(), prompt);
			return future.get(timeout.toSeconds(), TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			throw new IllegalStateException("system task timed out after " + timeout.toSeconds() + "s");
		} catch (ExecutionException e) {
			throw new IllegalStateException("system task failed: "
					+ (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while waiting for system task");
		} finally {
			pendingSystemTurn = null;
			pendingSystemTurnSessionId = null;
			if (switchModel) {
				sessionService.setModel(session.id(), originalModel);
			}
		}
	}

	@PreDestroy
	void shutdown() {
		turnExecutor.shutdownNow();
	}

	/** Find-or-create the one system session; caller must hold systemSessionLock. */
	private SessionEntity getOrCreateSystemSession() {
		return sessions.findSystemSession().map(s -> {
			return switch (s.state()) {
				case PARKED -> {
					sessionService.wake(s);
					yield sessions.get(s.id());
				}
				case CRASHED -> sessionService.resume(s.id());
				default -> s;
			};
		}).orElseGet(this::createSystemSession);
	}

	private SessionEntity createSystemSession() {
		UUID id = UUID.randomUUID();
		Path scratch = Path.of(props.worktreeRoot(), "_system", id.toString());
		try {
			Files.createDirectories(scratch);
		} catch (IOException e) {
			throw new IllegalStateException("failed to create system session scratch dir: " + e.getMessage(), e);
		}
		JsonNode mcpConfig = configFactory.linearMcpServer();
		// Backend-initiated turns have nobody to answer an interactive permission prompt, so tools
		// exposed via linearMcpServer() are pre-approved here (allowedTools bypasses canUseTool
		// entirely, regardless of permissionMode) rather than left to prompt and hang/time out.
		// (Codex rejects allowedTools outright — see SettingsService.systemProvider()'s javadoc.)
		List<String> allowedTools = mcpConfig != null ? List.of("mcp__linear") : List.of();
		String provider = settings.systemProvider();
		String model = ModelCatalog.byTier(provider, "cheap").orElse(null);
		SessionEntity entity = SessionEntity.builder()
				.id(id).name("system").provider(provider)
				.repoPath("(system)").contextDirs(List.of())
				.branch("(system)").baseBranch("(system)").worktreePath(scratch.toString())
				.model(model).permissionMode("default")
				.allowedTools(allowedTools).disallowedTools(List.of()).mcpConfig(mcpConfig)
				.skillSources(mapper.createArrayNode()).agentSources(mapper.createArrayNode())
				.state(SessionState.CREATING).kind("system").reflectionEnabled(false)
				.build();
		sessionService.enforceSessionLimitAndInsert(entity);
		journalPublisher.record(id, "state_changed", mapper.createObjectNode().put("state", "CREATING"));
		try {
			sessionService.transition(id, SessionState.PROVISIONING);
			sessionService.writeMcpConfig(entity);
			sessionService.transition(id, SessionState.STARTING);
			sessionService.spawn(sessions.get(id), false);
		} catch (RuntimeException e) {
			journalPublisher.record(id, "error",
					mapper.createObjectNode().put("message", e.getMessage()).put("fatal", true));
			sessionService.transition(id, SessionState.FAILED);
			throw e;
		}
		return sessions.get(id);
	}

	// ------------------------------------------------------------------ sidecar-event hooks (called by SessionService)

	/** Accumulates assistant text for the in-flight system turn, if {@code sessionId} is the pending one. */
	void onAssistantMessage(UUID sessionId, JsonNode content) {
		if (!sessionId.equals(pendingSystemTurnSessionId)) {
			return;
		}
		String text = extractText(content);
		if (!text.isBlank()) {
			pendingSystemText = text;
		}
	}

	/** Completes the pending system turn (if any) for {@code sessionId} with the accumulated assistant text. */
	void completeTurn(UUID sessionId) {
		completePending(sessionId, pendingSystemText, null);
	}

	/** Fails the pending system turn (if any) for {@code sessionId} — a fatal sidecar error or crash. */
	void failTurn(UUID sessionId, Throwable error) {
		completePending(sessionId, null, error);
	}

	private void completePending(UUID id, String result, Throwable error) {
		CompletableFuture<String> waiter = pendingSystemTurn;
		if (waiter == null || !id.equals(pendingSystemTurnSessionId)) {
			return;
		}
		if (error != null) {
			waiter.completeExceptionally(error);
		} else {
			waiter.complete(result);
		}
	}

	/** Text of the last assistant_message in a turn (there may be several around a tool call). */
	static String extractText(JsonNode content) {
		if (content == null || !content.isArray()) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (JsonNode block : content) {
			if ("text".equals(block.path("type").asText()) && block.hasNonNull("text")) {
				sb.append(block.get("text").asText());
			}
		}
		return sb.toString();
	}
}
