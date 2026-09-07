package de.pamir.claude.ui.session;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import de.pamir.claude.ui.config.AppProperties;
import de.pamir.claude.ui.config.SettingsService;
import de.pamir.claude.ui.git.GitCommandRunner;
import de.pamir.claude.ui.git.GitWorktreeService;
import de.pamir.claude.ui.journal.EventJournal;
import de.pamir.claude.ui.journal.JournalPublisher;
import de.pamir.claude.ui.process.SidecarManager;
import de.pamir.claude.ui.provision.AssetProvisioningService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SessionService {

	private static final Logger log = LoggerFactory.getLogger(SessionService.class);

	private final AppProperties props;
	private final SettingsService settings;
	private final SessionRepository sessions;
	private final GitWorktreeService worktrees;
	private final GitCommandRunner git;
	private final AssetProvisioningService assets;
	private final SidecarManager sidecars;
	private final EventJournal journal;
	private final JournalPublisher journalPublisher;
	private final ObjectMapper mapper;
	private final org.springframework.context.ApplicationEventPublisher events;
	private final SessionConfigFactory configFactory;
	private final SystemSessionService systemSessionService;
	private final AutoTitleService autoTitleService;
	private final ProviderCatalog catalog;
	private final Map<UUID, Object> locks = new ConcurrentHashMap<>();
	/** Guards the enforceSessionLimit()+insert critical section — see {@link #enforceSessionLimitAndInsert}. */
	private final Object sessionLimitLock = new Object();

	public SessionService(AppProperties props, SettingsService settings, SessionRepository sessions,
						  GitWorktreeService worktrees, GitCommandRunner git,
						  AssetProvisioningService assets, SidecarManager sidecars, EventJournal journal,
						  JournalPublisher journalPublisher, ObjectMapper mapper,
						  org.springframework.context.ApplicationEventPublisher events,
						  SessionConfigFactory configFactory, SystemSessionService systemSessionService,
						  AutoTitleService autoTitleService, ProviderCatalog catalog) {
		this.props = props;
		this.settings = settings;
		this.sessions = sessions;
		this.worktrees = worktrees;
		this.git = git;
		this.assets = assets;
		this.sidecars = sidecars;
		this.journal = journal;
		this.journalPublisher = journalPublisher;
		this.mapper = mapper;
		this.events = events;
		this.configFactory = configFactory;
		this.systemSessionService = systemSessionService;
		this.autoTitleService = autoTitleService;
		this.catalog = catalog;
	}

	// ------------------------------------------------------------------ creation

	/**
	 * Options for {@link #create(CreateOptions)} — replaces a three-deep overload ladder that had
	 * grown one trailing optional param per phase (7.3's continuedFromId, 7.4's parentSessionId;
	 * see docs/plan/phase-9-production-hardening.md S4). The 8-arg constructor covers the common
	 * case (plain create, no continuation/parent link); {@link #withContinuedFrom}/{@link
	 * #withParent} attach those independently so callers that need one don't have to spell out
	 * the other as {@code null}.
	 */
	public record CreateOptions(String name, String branch, String baseBranch, String repoPath, UUID templateId,
								JsonNode overrides, Map<String, String> kickoffValues, boolean syncBaseBranch,
								UUID continuedFromId, UUID parentSessionId) {

		public CreateOptions(String name, String branch, String baseBranch, String repoPath, UUID templateId,
							  JsonNode overrides, Map<String, String> kickoffValues, boolean syncBaseBranch) {
			this(name, branch, baseBranch, repoPath, templateId, overrides, kickoffValues, syncBaseBranch, null, null);
		}

		/** With an explicit continuation provenance link (7.3's "continue from" picker). */
		public CreateOptions withContinuedFrom(UUID continuedFromId) {
			return new CreateOptions(name, branch, baseBranch, repoPath, templateId, overrides, kickoffValues,
					syncBaseBranch, continuedFromId, parentSessionId);
		}

		/** With an explicit parent link (7.4's spawn_child_session tool). */
		public CreateOptions withParent(UUID parentSessionId) {
			return new CreateOptions(name, branch, baseBranch, repoPath, templateId, overrides, kickoffValues,
					syncBaseBranch, continuedFromId, parentSessionId);
		}
	}

	public SessionEntity create(CreateOptions options) {
		UUID id = UUID.randomUUID();
		Path worktree = Path.of(props.worktreeRoot()).resolve(id.toString());
		SessionConfigFactory.Prepared prepared = configFactory.prepare(id, worktree, options);
		SessionEntity entity = prepared.entity();
		enforceSessionLimitAndInsert(entity);
		record(id, "state_changed", mapper.createObjectNode().put("state", "CREATING"));
		for (String warning : prepared.warnings()) {
			record(id, "warning", mapper.createObjectNode().put("message", warning));
		}

		try {
			transition(id, SessionState.PROVISIONING);
			if (options.syncBaseBranch()) {
				worktrees.syncBaseBranch(Path.of(entity.repoPath()), options.baseBranch());
			}
			worktrees.createWorktree(Path.of(entity.repoPath()), worktree, options.branch(), options.baseBranch());
			excludeProvisionedAssets(worktree);
			for (var warning : assets.provision(worktree, entity.skillSources(), entity.agentSources())) {
				record(id, "warning", mapper.createObjectNode().put("message", warning.message()));
			}
			writeMcpConfig(entity);

			transition(id, SessionState.STARTING);
			SessionEntity persisted = sessions.get(id);
			spawn(persisted, false);
		} catch (RuntimeException e) {
			record(id, "error", mapper.createObjectNode().put("message", e.getMessage()).put("fatal", true));
			transition(id, SessionState.FAILED);
			throw e;
		}
		if (entity.kickoffPrompt() != null && !entity.kickoffPrompt().isBlank()) {
			sessions.enqueue(id, entity.kickoffPrompt());
			recordQueue(id);
		}
		return sessions.get(id);
	}

	public SessionEntity resume(UUID id) {
		synchronized (lock(id)) {
			SessionEntity session = sessions.get(id);
			if (session.state() != SessionState.CRASHED) {
				throw new IllegalStateException("session is " + session.state() + ", only CRASHED sessions can be resumed");
			}
			// no providerSessionId means the sidecar crashed before its first turn (no conversation
			// to resume yet) — buildArgs omits --resume in that case and spawns fresh, which is correct
			enforceSessionLimit();
			transition(id, SessionState.STARTING);
			spawn(session, true);
			return sessions.get(id);
		}
	}

	// ------------------------------------------------------------------ inbound commands

	public void sendUserMessage(UUID id, String text) {
		synchronized (lock(id)) {
			SessionEntity session = sessions.get(id);
			SessionState state = session.state();
			if (state == SessionState.IDLE && sidecars.hasLiveHandle(id)) {
				requireBudget(session);
				dispatch(id, text);
			} else if (state == SessionState.PARKED) {
				requireBudget(session);
				sessions.enqueue(id, text);
				recordQueue(id);
				wake(session);
			} else if (state == SessionState.CREATING || state == SessionState.PROVISIONING
					|| state == SessionState.STARTING || state == SessionState.RUNNING
					|| state == SessionState.WAITING_INPUT) {
				sessions.enqueue(id, text);
				recordQueue(id);
			} else {
				throw new IllegalStateException("session is " + state + " and does not accept messages");
			}
		}
	}

	public void updateCostBudget(UUID id, java.math.BigDecimal budget) {
		sessions.get(id);
		sessions.updateCostBudget(id, budget);
		record(id, "budget_updated", mapper.createObjectNode()
				.put("costBudgetUsd", budget == null ? null : budget.toPlainString()));
	}

	public void rename(UUID id, String name) {
		sessions.get(id);
		sessions.updateName(id, name);
		record(id, "session_renamed", mapper.createObjectNode().put("name", name).put("auto", false));
	}

	public void updateReflectionEnabled(UUID id, boolean enabled) {
		sessions.get(id);
		sessions.updateReflectionEnabled(id, enabled);
		record(id, "reflection_setting_changed", mapper.createObjectNode().put("reflectionEnabled", enabled));
	}

	public void respondPermission(UUID id, JsonNode command) {
		record(id, "permission_response", command);
		sidecars.handle(id).send(command.toString());
		synchronized (lock(id)) {
			if (sessions.get(id).state() == SessionState.WAITING_INPUT) {
				transition(id, SessionState.RUNNING);
			}
		}
	}

	public void interrupt(UUID id) {
		record(id, "interrupt", mapper.createObjectNode());
		sidecars.handle(id).send("{\"type\":\"interrupt\"}");
	}

	public void setPermissionMode(UUID id, String mode) {
		sidecars.handle(id).send(mapper.createObjectNode()
				.put("type", "set_permission_mode").put("mode", mode).toString());
	}

	public void setModel(UUID id, String model) {
		sidecars.handle(id).send(mapper.createObjectNode()
				.put("type", "set_model").put("model", model).toString());
	}

	public boolean deleteQueued(UUID id, long pos) {
		boolean removed = sessions.deleteQueued(id, pos);
		if (removed) {
			recordQueue(id);
		}
		return removed;
	}

	// ------------------------------------------------------------------ close

	public void close(UUID id, String dirtyMode, String commitMessage) {
		synchronized (lock(id)) {
			SessionEntity session = sessions.get(id);
			if (session.state() == SessionState.CLOSED || session.state() == SessionState.CLOSING) {
				return;
			}
			if ("system".equals(session.kind())) {
				transition(id, SessionState.CLOSING);
				sidecars.terminate(id);
				deleteRecursively(Path.of(session.worktreePath()));
				transition(id, SessionState.CLOSED);
				releaseSessionState(id);
				return;
			}
			Path worktree = Path.of(session.worktreePath());
			List<String> dirty = worktrees.dirtyFiles(worktree);
			if (!dirty.isEmpty() && (dirtyMode == null || dirtyMode.equals("fail"))) {
				throw new DirtyWorktreeException(dirty);
			}
			transition(id, SessionState.CLOSING);
			sidecars.terminate(id);
			if (!dirty.isEmpty()) {
				switch (dirtyMode) {
					case "commit" -> worktrees.commitAll(worktree,
							commitMessage != null && !commitMessage.isBlank() ? commitMessage : "WIP from claude-ui session " + session.name());
					case "stash" -> worktrees.stashAll(worktree, "claude-ui close: " + session.name());
					case "discard" -> { /* worktree remove --force discards */ }
					default -> throw new IllegalArgumentException("unknown dirty mode: " + dirtyMode);
				}
			}
			worktrees.removeWorktree(Path.of(session.repoPath()), worktree);
			transition(id, SessionState.CLOSED);
			releaseSessionState(id);
			if (session.reflectionEnabled()) {
				events.publishEvent(new de.pamir.claude.ui.memory.ReflectionRequested(id));
			}
			if (settings.serviceDiscoveryEnabled()) {
				events.publishEvent(new de.pamir.claude.ui.discovery.ServiceDiscoveryRequested(id, session.repoPath()));
			}
		}
	}

	private static void deleteRecursively(Path dir) {
		if (!Files.exists(dir)) {
			return;
		}
		try (var stream = Files.walk(dir)) {
			stream.sorted(Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (IOException ignored) {
					// best-effort cleanup
				}
			});
		} catch (IOException e) {
			log.warn("could not clean up scratch dir {}: {}", dir, e.getMessage());
		}
	}

	// ------------------------------------------------------------------ sidecar event handling

	private void onSidecarEvent(UUID id, JsonNode event) {
		String type = event.path("type").asText("unknown");
		if ("turn_complete".equals(type) && event instanceof ObjectNode turnComplete) {
			applyEstimatedCost(id, turnComplete);
		}
		record(id, type, event);
		switch (type) {
			case "ready" -> {
				// the adapter accepts input from here on; system_init only arrives with the first turn
				sessions.updateCapabilities(id, event.get("capabilities"));
				synchronized (lock(id)) {
					if (sessions.get(id).state() == SessionState.STARTING) {
						becomeIdleAndDrainQueue(id);
					}
				}
			}
			case "system_init" -> {
				sessions.updateProviderSessionId(id, event.path("providerSessionId").asText());
				sessions.updateModel(id, event.path("model").asText());
			}
			case "permission_request" -> transition(id, SessionState.WAITING_INPUT);
			case "permission_mode_changed" -> sessions.updatePermissionMode(id, event.path("mode").asText());
			case "model_changed" -> sessions.updateModel(id, event.path("model").asText());
			case "assistant_message" -> systemSessionService.onAssistantMessage(id, event.get("content"));
			case "turn_complete" -> {
				// drain first: best-effort housekeeping below must never be able to block it
				synchronized (lock(id)) {
					becomeIdleAndDrainQueue(id);
				}
				try {
					journal.deleteDeltasBefore(id, journal.lastSeq(id));
					autoTitleService.maybeAutoTitle(id);
				} catch (RuntimeException e) {
					log.warn("post-turn housekeeping failed for {}: {}", id, e.getMessage());
				}
				systemSessionService.completeTurn(id);
			}
			case "error" -> {
				if (event.path("fatal").asBoolean(false)) {
					systemSessionService.failTurn(id, new IllegalStateException(event.path("message").asText("system session error")));
				}
			}
			default -> { /* journaled above; no state effect */ }
		}
	}

	// package-private (not private): unit-tested directly — see docs/plan/phase-9-production-hardening.md T2
	void onSidecarExit(UUID id, int code, List<String> stderrTail, boolean shutdownRequested) {
		synchronized (lock(id)) {
			SessionEntity session = sessions.find(id).orElse(null);
			if (session == null) {
				return;
			}
			SessionState state = session.state();
			if (state == SessionState.CLOSING || state == SessionState.CLOSED || state == SessionState.FAILED) {
				return;
			}
			if (shutdownRequested) {
				return; // expected exit; the close flow owns the state
			}
			ObjectNode payload = mapper.createObjectNode()
					.put("message", "sidecar exited unexpectedly with code " + code)
					.put("fatal", true);
			payload.set("stderrTail", mapper.valueToTree(stderrTail));
			record(id, "error", payload);
			transition(id, SessionState.CRASHED);
			systemSessionService.failTurn(id, new IllegalStateException("system session crashed (exit " + code + ")"));
		}
	}

	// ------------------------------------------------------------------ internals

	/** Package-private: also called by {@link SystemSessionService#createSystemSession}. */
	void spawn(SessionEntity session, boolean resume) {
		String extraSystemPrompt = configFactory.extraSystemPrompt(session);
		sidecars.spawn(session, mcpConfigPath(session.id()), resume, extraSystemPrompt,
				event -> onSidecarEvent(session.id(), event),
				(handle, code) -> onSidecarExit(session.id(), code, handle.stderrTail(), handle.isShutdownRequested()));
	}

	/**
	 * Sends to the sidecar before journaling "sent" — if the handle is dead or the write
	 * fails, nothing is recorded and the caller can safely leave the message queued for a
	 * retry, rather than showing a transcript entry that was never actually delivered.
	 */
	private void dispatch(UUID id, String text) {
		sidecars.handle(id).send(mapper.createObjectNode()
				.put("type", "user_message").put("text", text).toString());
		record(id, "user_message", mapper.createObjectNode().put("text", text));
		transition(id, SessionState.RUNNING);
	}

	// package-private (not private): unit-tested directly — see docs/plan/phase-9-production-hardening.md T2
	void becomeIdleAndDrainQueue(UUID id) {
		transition(id, SessionState.IDLE);
		SessionEntity session = sessions.get(id);
		if (budgetExhausted(session)) {
			if (!sessions.queued(id).isEmpty()) {
				record(id, "budget_exhausted", budgetPayload(session));
			}
			return; // queued messages stay queued until the budget is raised
		}
		// peek (not pop): only removed from the queue once dispatch actually succeeds, so a
		// dead sidecar handle or transient send failure can't silently drop the message
		sessions.peekQueue(id).ifPresent(next -> {
			try {
				dispatch(id, next.text());
			} catch (RuntimeException e) {
				record(id, "error", mapper.createObjectNode()
						.put("message", "failed to send queued message, will retry: " + e.getMessage())
						.put("fatal", false));
				return;
			}
			sessions.deleteQueued(id, next.pos());
			recordQueue(id);
		});
	}

	/**
	 * A provider whose adapter declares {@code reportsCostUsd: false} (Codex today — see
	 * docs/plan/phase-5.13-codex-provider.md Decision 2, generalized in
	 * docs/plan/phase-10-review-followups.md R1) always reports {@code costUsd: 0} in its
	 * {@code turn_complete}; this rewrites the journaled event's costUsd in place from the raw
	 * {@code usage} token counts against that provider's Settings-editable price table
	 * ({@link SettingsService#pricingFor}), so the cost budget guard and usage dashboard don't
	 * need to know the number is estimated. No-op for a provider that reports its own cost.
	 */
	private void applyEstimatedCost(UUID id, ObjectNode turnComplete) {
		SessionEntity session = sessions.find(id).orElse(null);
		if (session == null || catalog.get(session.provider()).reportsCostUsd()) {
			return;
		}
		try {
			JsonNode pricing = mapper.readTree(settings.pricingFor(session.provider()));
			java.math.BigDecimal estimated = CodexCostEstimator.estimate(pricing, turnComplete.path("model").asText(""),
					turnComplete.path("usage"));
			turnComplete.put("costUsd", estimated);
		} catch (RuntimeException e) {
			log.warn("cost estimate failed for session {}: {}", id, e.getMessage());
		}
	}

	private boolean budgetExhausted(SessionEntity session) {
		return session.costBudgetUsd() != null
				&& journal.costToDate(session.id()).compareTo(session.costBudgetUsd()) >= 0;
	}

	private void requireBudget(SessionEntity session) {
		if (budgetExhausted(session)) {
			record(session.id(), "budget_exhausted", budgetPayload(session));
			throw new IllegalStateException("cost budget exhausted ($" + session.costBudgetUsd()
					+ "); raise the budget to continue");
		}
	}

	private ObjectNode budgetPayload(SessionEntity session) {
		return mapper.createObjectNode()
				.put("costBudgetUsd", session.costBudgetUsd().toPlainString())
				.put("costToDate", journal.costToDate(session.id()).toPlainString());
	}

	/** Package-private: also called by {@link SystemSessionService#createSystemSession} and {@link SessionHousekeeping}. */
	void transition(UUID id, SessionState state) {
		sessions.updateState(id, state);
		record(id, "state_changed", mapper.createObjectNode().put("state", state.name()));
	}

	/** Journal + fan out. The journal assigns seq; subscribers see exactly what replay will. */
	private void record(UUID id, String type, JsonNode payload) {
		journalPublisher.record(id, type, payload);
	}

	private void recordQueue(UUID id) {
		ObjectNode payload = mapper.createObjectNode();
		payload.set("queued", mapper.valueToTree(sessions.queued(id)));
		record(id, "queue_updated", payload);
	}

	/** Package-private: also called (standalone, no insert to pair it with) by {@link #resume}. */
	void enforceSessionLimit() {
		long live = sessions.countByStates(List.copyOf(SessionState.LIVE));
		if (live >= props.maxSessions()) {
			throw new IllegalStateException("max concurrent sessions reached (" + props.maxSessions() + ")");
		}
	}

	/**
	 * Package-private: also called by {@link SystemSessionService#createSystemSession}. Checking
	 * the live-session count and inserting the new row must be atomic — otherwise two concurrent
	 * creates (7.4's spawn_child_session fans a parent out into several) can both read a count
	 * under the limit and both insert, overshooting it (docs/plan/phase-9-production-hardening.md
	 * O1). The lock only spans the count query + insert, not the rest of provisioning.
	 */
	void enforceSessionLimitAndInsert(SessionEntity entity) {
		synchronized (sessionLimitLock) {
			enforceSessionLimit();
			sessions.insert(entity);
		}
	}

	/** Snapshots a session's effective config into a new session on a new branch. */
	public SessionEntity duplicate(UUID sourceId, String branch, String name, boolean syncBaseBranch) {
		SessionEntity source = sessions.get(sourceId);
		if ("system".equals(source.kind())) {
			throw new IllegalArgumentException("cannot duplicate a system session");
		}
		ObjectNode overrides = configFactory.configOverridesFrom(source);
		String sessionName = name != null && !name.isBlank() ? name : branch;
		return create(new CreateOptions(sessionName, branch, source.baseBranch(), source.repoPath(), null, overrides,
				null, syncBaseBranch));
	}

	/**
	 * The most recently created non-system session's effective config, as an overrides object
	 * shaped for {@link #create}. Backs the "quick session" create flow (ticket + service only,
	 * everything else copied from whatever was last set up) — an empty object if there is no
	 * prior session yet (a session created from it then just gets ordinary provider defaults).
	 */
	public JsonNode lastSessionConfig() {
		return sessions.findAll().stream()
				.filter(s -> !"system".equals(s.kind()))
				.findFirst()
				.<JsonNode>map(configFactory::configOverridesFrom)
				.orElseGet(mapper::createObjectNode);
	}

	/** Skills/agents symlinks and MCP artifacts must not pollute git status. */
	private void excludeProvisionedAssets(Path worktree) {
		try {
			var result = git.run(worktree, "rev-parse", "--git-path", "info/exclude");
			if (result.ok()) {
				Path exclude = Path.of(result.stdout());
				if (!exclude.isAbsolute()) {
					exclude = worktree.resolve(result.stdout());
				}
				Files.createDirectories(exclude.getParent());
				Files.writeString(exclude, "\n.claude/skills/\n.claude/agents/\n.claude-ui.pid\n",
						java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
			}
		} catch (IOException e) {
			log.warn("could not write per-worktree exclude: {}", e.getMessage());
		}
	}

	/** Package-private: also called by {@link SystemSessionService#createSystemSession}. */
	void writeMcpConfig(SessionEntity session) {
		if (session.mcpConfig() == null || session.mcpConfig().isNull()) {
			return;
		}
		try {
			Path file = mcpConfigPath(session.id());
			Files.createDirectories(file.getParent());
			Files.writeString(file, session.mcpConfig().toString());
		} catch (IOException e) {
			throw new IllegalStateException("failed to write mcp config: " + e.getMessage(), e);
		}
	}

	private Path mcpConfigPath(UUID id) {
		return Path.of(props.worktreeRoot()).resolve(".mcp").resolve(id + ".json");
	}

	/** Package-private: also used by {@link SessionHousekeeping}'s parking tick. */
	Object lock(UUID id) {
		return locks.computeIfAbsent(id, k -> new Object());
	}

	/**
	 * Drops the per-session bookkeeping ({@code locks} here, plus the journal's own in-memory
	 * state) once a session is CLOSED and will never be touched again — otherwise both maps grow
	 * unbounded over the process lifetime (docs/plan/phase-10-review-followups.md R4). Called
	 * from inside the {@code synchronized (lock(id))} block that just performed the final CLOSED
	 * transition; removing the map entry here is safe even though the current thread still holds
	 * that object's monitor until the block exits.
	 */
	private void releaseSessionState(UUID id) {
		locks.remove(id);
		journal.release(id);
	}

	/** Package-private: test-only visibility into R4's lock-release bookkeeping. */
	boolean hasLock(UUID id) {
		return locks.containsKey(id);
	}

	// ------------------------------------------------------------------ parking

	/** Package-private: also called by {@link SystemSessionService#getOrCreateSystemSession}. */
	void wake(SessionEntity session) {
		log.info("waking parked session {}", session.id());
		transition(session.id(), SessionState.STARTING);
		try {
			spawn(session, true);
		} catch (RuntimeException e) {
			record(session.id(), "error", mapper.createObjectNode()
					.put("message", "wake failed: " + e.getMessage()).put("fatal", true));
			transition(session.id(), SessionState.CRASHED);
			throw e;
		}
	}
}
