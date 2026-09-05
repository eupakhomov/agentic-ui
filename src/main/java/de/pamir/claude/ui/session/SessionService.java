package de.pamir.claude.ui.session;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import de.pamir.claude.ui.config.AppProperties;
import de.pamir.claude.ui.config.SettingsService;
import de.pamir.claude.ui.git.GitCommandRunner;
import de.pamir.claude.ui.git.GitWorktreeService;
import de.pamir.claude.ui.journal.EventJournal;
import de.pamir.claude.ui.journal.SessionEventBus;
import de.pamir.claude.ui.memory.MemoryEpisodeRepository;
import de.pamir.claude.ui.process.SidecarManager;
import de.pamir.claude.ui.provision.AssetProvisioningService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class SessionService {

	private static final Logger log = LoggerFactory.getLogger(SessionService.class);

	private final AppProperties props;
	private final SettingsService settings;
	private final SessionRepository sessions;
	private final TemplateRepository templates;
	private final GitWorktreeService worktrees;
	private final GitCommandRunner git;
	private final AssetProvisioningService assets;
	private final SidecarManager sidecars;
	private final EventJournal journal;
	private final SessionEventBus bus;
	private final ObjectMapper mapper;
	private final org.springframework.context.ApplicationEventPublisher events;
	private final MemoryEpisodeRepository episodes;
	private final int serverPort;
	private final Map<UUID, Object> locks = new ConcurrentHashMap<>();

	// system session: exactly one live at a time, guarded by this lock (see "system sessions" section below)
	private final Object systemSessionLock = new Object();
	private volatile UUID pendingSystemTurnSessionId;
	private volatile CompletableFuture<String> pendingSystemTurn;
	private final StringBuilder pendingSystemText = new StringBuilder();

	public SessionService(AppProperties props, SettingsService settings, SessionRepository sessions,
						  TemplateRepository templates, GitWorktreeService worktrees, GitCommandRunner git,
						  AssetProvisioningService assets, SidecarManager sidecars, EventJournal journal,
						  SessionEventBus bus, ObjectMapper mapper,
						  org.springframework.context.ApplicationEventPublisher events,
						  MemoryEpisodeRepository episodes, @Value("${server.port:8080}") int serverPort) {
		this.props = props;
		this.settings = settings;
		this.sessions = sessions;
		this.templates = templates;
		this.worktrees = worktrees;
		this.git = git;
		this.assets = assets;
		this.sidecars = sidecars;
		this.journal = journal;
		this.bus = bus;
		this.mapper = mapper;
		this.events = events;
		this.episodes = episodes;
		this.serverPort = serverPort;
	}

	// ------------------------------------------------------------------ creation

	public SessionEntity create(String name, String branch, String baseBranch, String repoPath, UUID templateId,
								JsonNode overrides, Map<String, String> kickoffValues, boolean syncBaseBranch) {
		return create(name, branch, baseBranch, repoPath, templateId, overrides, kickoffValues, syncBaseBranch, null);
	}

	/** Same as above, with an explicit continuation provenance link (7.3's "continue from" picker). */
	public SessionEntity create(String name, String branch, String baseBranch, String repoPath, UUID templateId,
								JsonNode overrides, Map<String, String> kickoffValues, boolean syncBaseBranch,
								UUID continuedFromId) {
		return create(name, branch, baseBranch, repoPath, templateId, overrides, kickoffValues, syncBaseBranch,
				continuedFromId, null);
	}

	/** Same as above, with an explicit parent link (7.4's spawn_child_session tool). */
	public SessionEntity create(String name, String branch, String baseBranch, String repoPath, UUID templateId,
								JsonNode overrides, Map<String, String> kickoffValues, boolean syncBaseBranch,
								UUID continuedFromId, UUID parentSessionId) {
		enforceSessionLimit();
		String repo = repoPath == null || repoPath.isBlank() ? props.repoPath() : repoPath;
		if (!Files.exists(Path.of(repo).resolve(".git"))) {
			throw new IllegalArgumentException("not a git repository: " + repo);
		}
		ObjectNode config = mergedConfig(templateId, overrides);
		List<String> assetWarnings = new java.util.ArrayList<>();
		if (templateId != null) {
			List<TemplateRepository.TemplateAsset> templateAssets = templates.get(templateId).assets();
			if (overrides == null || !overrides.has("skillSources")) {
				config.set("skillSources", combineTemplateSources(config.get("skillSources"), templateAssets,
						"skill", "dir", assetWarnings));
			}
			if (overrides == null || !overrides.has("agentSources")) {
				config.set("agentSources", combineTemplateSources(config.get("agentSources"), templateAssets,
						"agent", "file", assetWarnings));
			}
		}
		String provider = text(config, "provider", settings.defaultProvider());
		String permissionMode = text(config, "permissionMode", "default");
		JsonNode explicitMcpConfig = config.get("mcpConfig");
		List<String> allowedTools = stringList(config, "allowedTools");
		List<String> disallowedTools = stringList(config, "disallowedTools");
		String thinking = nullableText(config, "thinking");
		Integer maxTurns = config.hasNonNull("maxTurns") ? config.get("maxTurns").asInt() : null;
		String fallbackModel = nullableText(config, "fallbackModel");
		String ecosystemPath = config.has("ecosystemPath") ? nullableText(config, "ecosystemPath")
				: nullableIfBlank(settings.ecosystemRoot());
		List<String> contextDirs = stringList(config, "contextDirs");
		if ("codex".equals(provider)) {
			// See docs/plan/phase-5.13-codex-provider.md's DoD: unsupported controls must be
			// rejected at creation time, not silently downgraded. mcpConfig is NOT rejected
			// here (unlike the other codex-unsupported fields below) — the 2026-08-30 MCP
			// follow-up confirmed sidecar-codex can translate the same Claude-shaped config
			// every other session already gets, including the default Linear MCP layering.
			if (!"default".equals(permissionMode) && !"bypassPermissions".equals(permissionMode)) {
				throw new IllegalArgumentException(
						"provider 'codex' does not support permission mode '" + permissionMode + "'");
			}
			if (!allowedTools.isEmpty() || !disallowedTools.isEmpty()) {
				throw new IllegalArgumentException("provider 'codex' does not support allowedTools/disallowedTools");
			}
			if (thinking != null) {
				throw new IllegalArgumentException("provider 'codex' does not support the thinking control (use effort)");
			}
			if (maxTurns != null) {
				throw new IllegalArgumentException("provider 'codex' does not support maxTurns");
			}
			if (fallbackModel != null) {
				throw new IllegalArgumentException("provider 'codex' does not support fallbackModel");
			}
			if (arrayOrEmpty(config, "agentSources").size() > 0) {
				// Confirmed (docs/plan/phase-5.13-codex-provider.md's follow-up): Codex has no
				// equivalent to Claude's static subagent files at all, so unlike skillSources
				// (silently materialized and now genuinely used, see Task 9) this can't be
				// quietly downgraded to a no-op — reject it instead.
				throw new IllegalArgumentException("provider 'codex' does not support agentSources");
			}
			// Ecosystem/context dirs commonly come from a global default (settings.ecosystemRoot()),
			// not explicit per-session intent, so this degrades with a visible warning rather than
			// rejecting creation outright.
			if (ecosystemPath != null || !contextDirs.isEmpty()) {
				assetWarnings.add("codex provider does not support context directories — skipped");
			}
			ecosystemPath = null;
			contextDirs = List.of();
		} else if (settings.memoryEnabled()) {
			// allowedTools/disallowedTools are additive presets (bypass or block specific tools
			// without switching the session into allow-list-only mode) — safe to append to
			// regardless of whether the session configured any of its own. Claude sessions
			// pre-approve the read-only memory tools this way; Codex rejects allowedTools
			// entirely (handled above), so its sessions go through the normal approval flow
			// instead (decision 10). Named explicitly (not the blanket "mcp__memory" server-level
			// grant) since 7.4's orchestration tools now live on the same MCP server and must NOT
			// be pre-approved — spawn_child_session needs the normal human-in-the-loop prompt.
			allowedTools = new java.util.ArrayList<>(allowedTools);
			allowedTools.add("mcp__memory__memory_tags");
			allowedTools.add("mcp__memory__memory_search");
			allowedTools.add("mcp__memory__memory_read");
		}
		JsonNode mcpConfig = withDefaultMemoryMcp(withDefaultLinearMcp(explicitMcpConfig));

		UUID id = UUID.randomUUID();
		Path worktree = Path.of(props.worktreeRoot()).resolve(id.toString());

		SessionEntity entity = new SessionEntity(
				id, name,
				provider,
				config.get("providerConfig"),
				repo,
				ecosystemPath,
				contextDirs,
				branch, baseBranch, worktree.toString(),
				null, null,
				nullableText(config, "model"),
				permissionMode,
				allowedTools,
				disallowedTools,
				mcpConfig,
				config.get("envVars"),
				arrayOrEmpty(config, "skillSources"),
				arrayOrEmpty(config, "agentSources"),
				nullableText(config, "instructions"),
				thinking,
				nullableText(config, "effort"),
				maxTurns,
				fallbackModel,
				config.hasNonNull("costBudgetUsd") ? new BigDecimal(config.get("costBudgetUsd").asText()) : null,
				fillPlaceholders(nullableText(config, "kickoffPrompt"), kickoffValues),
				SessionState.CREATING, "user", nullableText(config, "ticketRef"), continuedFromId, parentSessionId,
				null, null, null, null,
				config.path("reflectionEnabled").asBoolean(settings.memoryReflectionDefault()), null, null, null);
		sessions.insert(entity);
		record(id, "state_changed", mapper.createObjectNode().put("state", "CREATING"));
		for (String warning : assetWarnings) {
			record(id, "warning", mapper.createObjectNode().put("message", warning));
		}

		try {
			transition(id, SessionState.PROVISIONING);
			if (syncBaseBranch) {
				worktrees.syncBaseBranch(Path.of(entity.repoPath()), baseBranch);
			}
			worktrees.createWorktree(Path.of(entity.repoPath()), worktree, branch, baseBranch);
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
			if (session.reflectionEnabled()) {
				events.publishEvent(new de.pamir.claude.ui.memory.ReflectionRequested(id));
			}
		}
	}

	// ------------------------------------------------------------------ system sessions

	/**
	 * Runs one backend-initiated turn (e.g. ticket import) on the singleton system session and
	 * returns the assistant's final text. Serialized end-to-end by systemSessionLock: at most one
	 * system turn is ever in flight, so concurrent callers simply queue behind each other rather
	 * than racing to create a second system session or mixing up whose turn_complete is whose.
	 */
	public String runSystemTurn(String prompt, Duration timeout) {
		return runSystemTurn(prompt, null, timeout);
	}

	/**
	 * Same as {@link #runSystemTurn(String, Duration)}, but switches the system session to
	 * {@code modelOverride} for this one turn and back to its normal model afterward (used by
	 * reflection to run on a different model than the system session's default haiku — see
	 * docs/plan/phase-5.3-memory-reflection.md decision 7). {@code null} keeps the current model.
	 */
	public String runSystemTurn(String prompt, String modelOverride, Duration timeout) {
		synchronized (systemSessionLock) {
			SessionEntity session = getOrCreateSystemSession();
			String originalModel = session.model();
			boolean switchModel = modelOverride != null && !modelOverride.equals(originalModel);
			if (switchModel) {
				setModel(session.id(), modelOverride);
			}
			pendingSystemText.setLength(0);
			CompletableFuture<String> future = new CompletableFuture<>();
			pendingSystemTurn = future;
			pendingSystemTurnSessionId = session.id();
			try {
				sendUserMessage(session.id(), prompt);
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
					setModel(session.id(), originalModel);
				}
			}
		}
	}

	/** Find-or-create the one system session; caller must hold systemSessionLock. */
	private SessionEntity getOrCreateSystemSession() {
		return sessions.findSystemSession().map(s -> {
			return switch (s.state()) {
				case PARKED -> {
					wake(s);
					yield sessions.get(s.id());
				}
				case CRASHED -> resume(s.id());
				default -> s;
			};
		}).orElseGet(this::createSystemSession);
	}

	private SessionEntity createSystemSession() {
		enforceSessionLimit();
		UUID id = UUID.randomUUID();
		Path scratch = Path.of(props.worktreeRoot(), "_system", id.toString());
		try {
			Files.createDirectories(scratch);
		} catch (IOException e) {
			throw new IllegalStateException("failed to create system session scratch dir: " + e.getMessage(), e);
		}
		JsonNode mcpConfig = linearMcpServer();
		// Backend-initiated turns have nobody to answer an interactive permission prompt, so tools
		// exposed via linearMcpServer() are pre-approved here (allowedTools bypasses canUseTool
		// entirely, regardless of permissionMode) rather than left to prompt and hang/time out.
		List<String> allowedTools = mcpConfig != null ? List.of("mcp__linear") : List.of();
		SessionEntity entity = new SessionEntity(
				id, "system", "claude", null,
				"(system)", null, List.of(),
				"(system)", "(system)", scratch.toString(),
				null, null, "haiku", "default",
				allowedTools, List.of(), mcpConfig, null, mapper.createArrayNode(), mapper.createArrayNode(),
				null, null, null, null, null, null, null,
				SessionState.CREATING, "system", null, null, null, null, null, null, null, false, null, null, null);
		sessions.insert(entity);
		record(id, "state_changed", mapper.createObjectNode().put("state", "CREATING"));
		try {
			transition(id, SessionState.PROVISIONING);
			writeMcpConfig(entity);
			transition(id, SessionState.STARTING);
			spawn(sessions.get(id), false);
		} catch (RuntimeException e) {
			record(id, "error", mapper.createObjectNode().put("message", e.getMessage()).put("fatal", true));
			transition(id, SessionState.FAILED);
			throw e;
		}
		return sessions.get(id);
	}

	/** The Linear MCP server block ({"linear": {...}}), or null if Linear integration isn't configured. */
	private ObjectNode linearMcpServer() {
		boolean apiKey = props.linearApiKey() != null && !props.linearApiKey().isBlank();
		if (!apiKey && !settings.linearOAuthEnabled()) {
			return null;
		}
		ObjectNode servers = mapper.createObjectNode();
		ObjectNode linear = servers.putObject("linear");
		linear.put("type", "http").put("url", "https://mcp.linear.app/mcp");
		if (apiKey) {
			// explicit key wins even if OAuth is also enabled in Settings
			linear.putObject("headers").put("Authorization", "Bearer " + props.linearApiKey());
		}
		// else: no headers — relies on the ambient `claude` CLI's own cached OAuth credential for
		// this server URL (set up once via `claude mcp add` on the backend host, e.g. Google-SSO Linear)
		return servers;
	}

	/**
	 * Layers the Linear MCP server into a regular session's mcpConfig by default when Linear
	 * integration is configured, so the agent can read/update tickets without the user having to
	 * wire it up per session — unless the session's own config already defines a "linear" entry,
	 * which wins. Regular sessions go through the normal permission-approval flow for its tools
	 * (unlike the system session, which pre-approves them — see createSystemSession).
	 */
	private JsonNode withDefaultLinearMcp(JsonNode configured) {
		ObjectNode linear = linearMcpServer();
		if (linear == null) {
			return configured;
		}
		if (configured == null || configured.isNull()) {
			return linear;
		}
		if (!(configured instanceof ObjectNode existing) || existing.has("linear")) {
			return configured;
		}
		ObjectNode merged = mapper.createObjectNode();
		merged.setAll(existing);
		merged.setAll(linear);
		return merged;
	}

	/**
	 * The memory MCP server block ({"memory": {...}}), or null if memory is disabled in Settings.
	 * Same shape as {@link #linearMcpServer()} — an in-process Spring AI MCP server, not a
	 * spawned child (docs/plan/phase-5.3-memory-reflection.md decision 12a) — pointed at
	 * ourselves and authenticated with the same dashboard bearer token (decision 11).
	 */
	private ObjectNode memoryMcpServer() {
		if (!settings.memoryEnabled()) {
			return null;
		}
		ObjectNode servers = mapper.createObjectNode();
		ObjectNode memory = servers.putObject("memory");
		memory.put("type", "http").put("url", "http://127.0.0.1:" + serverPort + "/api/mcp/memory");
		if (props.authToken() != null && !props.authToken().isBlank()) {
			memory.putObject("headers").put("Authorization", "Bearer " + props.authToken());
		}
		return servers;
	}

	/** Layers the memory MCP server into a session's mcpConfig, same merge rule as {@link #withDefaultLinearMcp}. */
	private JsonNode withDefaultMemoryMcp(JsonNode configured) {
		ObjectNode memory = memoryMcpServer();
		if (memory == null) {
			return configured;
		}
		if (configured == null || configured.isNull()) {
			return memory;
		}
		if (!(configured instanceof ObjectNode existing) || existing.has("memory")) {
			return configured;
		}
		ObjectNode merged = mapper.createObjectNode();
		merged.setAll(existing);
		merged.setAll(memory);
		return merged;
	}

	/**
	 * The episodic-window system-prompt block (docs/plan/phase-5.3-memory-reflection.md
	 * "Automatic episodic window" / decision 12b): announces the session's own id (needed for
	 * every memory tool call) and the last few episodes recorded for this service, if any. Null
	 * when memory is disabled or this isn't a real-repo user session (system sessions skip it).
	 */
	private String memorySystemPromptBlock(SessionEntity session) {
		if (!settings.memoryEnabled() || !"user".equals(session.kind())) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		sb.append("Long-term memory tools are available (memory_tags, memory_search, memory_read). ")
				.append("Pass this as `sessionId` in every call: ").append(session.id());
		var recent = episodes.recentByService(session.repoPath(), 5);
		if (!recent.isEmpty()) {
			sb.append("\n\nRecent activity on this service, most recent first:\n");
			for (var ep : recent) {
				sb.append("- ").append(ep.summary()).append('\n');
			}
		}
		return sb.toString();
	}

	/**
	 * 7.4's orchestration-tools announcement — same MCP server as memory (decision 12a), so it's
	 * gated on the same {@code settings.memoryEnabled()}. A child session gets the report_result
	 * reminder instead of the spawn tools (it can't spawn — depth 1, enforced in the tool body
	 * too); a plain session with no ecosystem configured gets nothing, since list_services would
	 * just error for it.
	 */
	private String orchestrationSystemPromptBlock(SessionEntity session) {
		if (!settings.memoryEnabled() || !"user".equals(session.kind())) {
			return null;
		}
		if (session.parentSessionId() != null) {
			return "You are a child session (sessionId: " + session.id() + ") spawned to work on this service "
					+ "as part of a larger cross-service task. When your task is complete, call report_result "
					+ "with sessionId=" + session.id() + " and a concise summary of what you did — this reaches "
					+ "the parent session automatically.";
		}
		if (session.ecosystemPath() == null || session.ecosystemPath().isBlank()) {
			return null;
		}
		return "Multi-service orchestration tools are available (list_services, spawn_child_session, "
				+ "check_children) for tasks that span several services under this session's ecosystem folder. "
				+ "Pass this as `sessionId` in every call: " + session.id();
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
			applyCodexCostEstimate(id, turnComplete);
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
			case "assistant_message" -> {
				if (id.equals(pendingSystemTurnSessionId)) {
					String text = extractText(event.get("content"));
					if (!text.isBlank()) {
						pendingSystemText.setLength(0);
						pendingSystemText.append(text);
					}
				}
			}
			case "turn_complete" -> {
				// drain first: best-effort housekeeping below must never be able to block it
				synchronized (lock(id)) {
					becomeIdleAndDrainQueue(id);
				}
				try {
					journal.deleteDeltasBefore(id, journal.lastSeq(id));
					maybeAutoTitle(id);
				} catch (RuntimeException e) {
					log.warn("post-turn housekeeping failed for {}: {}", id, e.getMessage());
				}
				completePendingSystemTurn(id, pendingSystemText.toString(), null);
			}
			case "error" -> {
				if (event.path("fatal").asBoolean(false)) {
					completePendingSystemTurn(id, null,
							new IllegalStateException(event.path("message").asText("system session error")));
				}
			}
			default -> { /* journaled above; no state effect */ }
		}
	}

	/** Text of the last assistant_message in a turn (there may be several around a tool call). */
	private static String extractText(JsonNode content) {
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

	private void completePendingSystemTurn(UUID id, String result, Throwable error) {
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

	private void onSidecarExit(UUID id, int code, List<String> stderrTail, boolean shutdownRequested) {
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
			completePendingSystemTurn(id, null, new IllegalStateException("system session crashed (exit " + code + ")"));
		}
	}

	// ------------------------------------------------------------------ internals

	private void spawn(SessionEntity session, boolean resume) {
		String extraSystemPrompt = java.util.stream.Stream
				.of(memorySystemPromptBlock(session), orchestrationSystemPromptBlock(session))
				.filter(java.util.Objects::nonNull)
				.collect(java.util.stream.Collectors.joining("\n\n"));
		sidecars.spawn(session, mcpConfigPath(session.id()), resume, extraSystemPrompt.isBlank() ? null : extraSystemPrompt,
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

	private void becomeIdleAndDrainQueue(UUID id) {
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
	 * The Codex adapter always reports {@code costUsd: 0} (it has no per-turn USD figure —
	 * see docs/plan/phase-5.13-codex-provider.md Decision 2); this rewrites the journaled
	 * event's costUsd in place from the raw {@code usage} token counts against the
	 * Settings-editable price table, so the cost budget guard and usage dashboard don't
	 * need to know the number is estimated. No-op for non-Codex sessions.
	 */
	private void applyCodexCostEstimate(UUID id, ObjectNode turnComplete) {
		SessionEntity session = sessions.find(id).orElse(null);
		if (session == null || !"codex".equals(session.provider())) {
			return;
		}
		try {
			JsonNode pricing = mapper.readTree(settings.codexPricing());
			BigDecimal estimated = CodexCostEstimator.estimate(pricing, turnComplete.path("model").asText(""),
					turnComplete.path("usage"));
			turnComplete.put("costUsd", estimated);
		} catch (RuntimeException e) {
			log.warn("codex cost estimate failed for session {}: {}", id, e.getMessage());
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

	private void transition(UUID id, SessionState state) {
		sessions.updateState(id, state);
		record(id, "state_changed", mapper.createObjectNode().put("state", state.name()));
	}

	/** Journal + fan out. The journal assigns seq; subscribers see exactly what replay will. */
	private void record(UUID id, String type, JsonNode payload) {
		bus.publish(id, journal.append(id, type, payload));
	}

	private void recordQueue(UUID id) {
		ObjectNode payload = mapper.createObjectNode();
		payload.set("queued", mapper.valueToTree(sessions.queued(id)));
		record(id, "queue_updated", payload);
	}

	private void enforceSessionLimit() {
		long live = sessions.countByStates(List.copyOf(SessionState.LIVE));
		if (live >= props.maxSessions()) {
			throw new IllegalStateException("max concurrent sessions reached (" + props.maxSessions() + ")");
		}
	}

	/**
	 * Merges a template's live-linked library assets (skipping ARCHIVED ones, which are reported
	 * back via {@code warnings}) with any free-form sources already in the config's own key.
	 */
	private ArrayNode combineTemplateSources(JsonNode existing, List<TemplateRepository.TemplateAsset> templateAssets,
											 String kind, String sourceType, List<String> warnings) {
		ArrayNode combined = mapper.createArrayNode();
		if (existing != null && existing.isArray()) {
			combined.addAll((ArrayNode) existing);
		}
		for (TemplateRepository.TemplateAsset asset : templateAssets) {
			if (!kind.equals(asset.kind())) {
				continue;
			}
			if ("ARCHIVED".equals(asset.status())) {
				warnings.add(kind + " '" + asset.name() + "' is archived and was skipped");
				continue;
			}
			combined.add(mapper.createObjectNode().put("type", sourceType).put("ref", asset.location()));
		}
		return combined;
	}

	/** Snapshots a session's effective config into a new session on a new branch. */
	public SessionEntity duplicate(UUID sourceId, String branch, String name, boolean syncBaseBranch) {
		SessionEntity source = sessions.get(sourceId);
		if ("system".equals(source.kind())) {
			throw new IllegalArgumentException("cannot duplicate a system session");
		}
		ObjectNode overrides = mapper.createObjectNode();
		if (source.model() != null) {
			overrides.put("model", source.model());
		}
		overrides.put("permissionMode", source.permissionMode());
		if (!source.allowedTools().isEmpty()) {
			overrides.set("allowedTools", mapper.valueToTree(source.allowedTools()));
		}
		if (!source.disallowedTools().isEmpty()) {
			overrides.set("disallowedTools", mapper.valueToTree(source.disallowedTools()));
		}
		if (source.mcpConfig() != null && !source.mcpConfig().isNull()) {
			overrides.set("mcpConfig", source.mcpConfig());
		}
		if (source.envVars() != null && !source.envVars().isNull()) {
			overrides.set("envVars", source.envVars());
		}
		if (source.skillSources() != null && source.skillSources().isArray() && !source.skillSources().isEmpty()) {
			overrides.set("skillSources", source.skillSources());
		}
		if (source.agentSources() != null && source.agentSources().isArray() && !source.agentSources().isEmpty()) {
			overrides.set("agentSources", source.agentSources());
		}
		if (source.instructions() != null) {
			overrides.put("instructions", source.instructions());
		}
		if (source.ecosystemPath() != null) {
			overrides.put("ecosystemPath", source.ecosystemPath());
		}
		if (!source.contextDirs().isEmpty()) {
			overrides.set("contextDirs", mapper.valueToTree(source.contextDirs()));
		}
		if (source.thinking() != null) {
			overrides.put("thinking", source.thinking());
		}
		if (source.effort() != null) {
			overrides.put("effort", source.effort());
		}
		if (source.maxTurns() != null) {
			overrides.put("maxTurns", source.maxTurns());
		}
		if (source.fallbackModel() != null) {
			overrides.put("fallbackModel", source.fallbackModel());
		}
		if (source.costBudgetUsd() != null) {
			overrides.put("costBudgetUsd", source.costBudgetUsd().toPlainString());
		}
		overrides.put("reflectionEnabled", source.reflectionEnabled());
		String sessionName = name != null && !name.isBlank() ? name : branch;
		return create(sessionName, branch, source.baseBranch(), source.repoPath(), null, overrides, null, syncBaseBranch);
	}

	private ObjectNode mergedConfig(UUID templateId, JsonNode overrides) {
		ObjectNode config = mapper.createObjectNode();
		if (templateId != null) {
			JsonNode templateConfig = templates.get(templateId).config();
			if (templateConfig instanceof ObjectNode t) {
				config.setAll(t);
			}
		}
		if (overrides instanceof ObjectNode o) {
			config.setAll(o);
		}
		return config;
	}

	private String fillPlaceholders(String prompt, Map<String, String> values) {
		if (prompt == null || values == null) {
			return prompt;
		}
		String filled = prompt;
		for (var entry : values.entrySet()) {
			filled = filled.replace("{{" + entry.getKey() + "}}", entry.getValue());
		}
		return filled;
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

	private void writeMcpConfig(SessionEntity session) {
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

	private Object lock(UUID id) {
		return locks.computeIfAbsent(id, k -> new Object());
	}

	// --- config JSON accessors ---

	private static String text(ObjectNode node, String field, String fallback) {
		return node.hasNonNull(field) ? node.get(field).asText() : fallback;
	}

	private static String nullableText(ObjectNode node, String field) {
		return node.hasNonNull(field) ? node.get(field).asText() : null;
	}

	private static String nullableIfBlank(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	private List<String> stringList(ObjectNode node, String field) {
		if (!node.hasNonNull(field) || !node.get(field).isArray()) {
			return List.of();
		}
		return mapper.convertValue(node.get(field),
				mapper.getTypeFactory().constructCollectionType(List.class, String.class));
	}

	private JsonNode arrayOrEmpty(ObjectNode node, String field) {
		return node.hasNonNull(field) && node.get(field).isArray() ? node.get(field) : mapper.createArrayNode();
	}

	// ------------------------------------------------------------------ parking

	/** IDLE sessions whose sidecar has been quiet past the timeout are parked. */
	@org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60_000)
	void parkIdleSessions() {
		var cutoff = java.time.Instant.now().minus(java.time.Duration.ofMinutes(props.idleParkMinutes()));
		for (SessionEntity session : sessions.findByStates(List.of(SessionState.IDLE))) {
			if (session.updatedAt() != null && session.updatedAt().isBefore(cutoff)
					&& session.providerSessionId() != null && sidecars.hasLiveHandle(session.id())) {
				synchronized (lock(session.id())) {
					if (sessions.get(session.id()).state() != SessionState.IDLE) {
						continue;
					}
					log.info("parking idle session {}", session.id());
					transition(session.id(), SessionState.PARKED);
					sidecars.terminate(session.id());
				}
			}
		}
	}

	private void wake(SessionEntity session) {
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

	// ------------------------------------------------------------------ auto-titling

	/** After the first turn, name sessions still carrying their default (= branch) name. */
	private void maybeAutoTitle(UUID id) {
		SessionEntity session = sessions.find(id).orElse(null);
		if (session == null || !session.name().equals(session.branch())) {
			return;
		}
		var events = journal.readAfter(id, 0);
		long turns = events.stream().filter(e -> e.type().equals("turn_complete")).count();
		if (turns != 1) {
			return;
		}
		String userText = events.stream().filter(e -> e.type().equals("user_message")).findFirst()
				.map(e -> e.payload().path("text").asText()).orElse("");
		if (userText.isBlank()) {
			return;
		}
		Thread.ofVirtual().name("auto-title-" + id).start(() -> {
			try {
				Process p = new ProcessBuilder("claude", "-p", "--model", "haiku",
						"Generate a short title (max 6 words) for a coding session that starts with this request. "
								+ "Output ONLY the title, no quotes:\n\n" + userText.substring(0, Math.min(500, userText.length())))
						.redirectErrorStream(false).start();
				String title = new String(p.getInputStream().readAllBytes()).strip();
				if (p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0
						&& !title.isBlank() && title.length() <= 80) {
					SessionEntity current = sessions.find(id).orElse(null);
					if (current != null && current.name().equals(current.branch())) {
						sessions.updateName(id, title);
						record(id, "session_renamed", mapper.createObjectNode().put("name", title).put("auto", true));
					}
				} else {
					p.destroyForcibly();
				}
			} catch (Exception e) {
				log.debug("auto-title failed for {}: {}", id, e.getMessage());
			}
		});
	}

	// ------------------------------------------------------------------ startup sweep

	@EventListener(ApplicationReadyEvent.class)
	void markOrphanedSessionsCrashed() {
		for (SessionEntity session : sessions.findByStates(List.copyOf(SessionState.LIVE))) {
			killOrphanSidecar(session);
			log.info("startup sweep: session {} was {} -> CRASHED", session.id(), session.state());
			record(session.id(), "error", mapper.createObjectNode()
					.put("message", "backend restarted while session was live").put("fatal", true));
			transition(session.id(), SessionState.CRASHED);
		}
	}

	/** A kill -9'd backend leaves sidecars running; their PID files let us reap them. */
	private void killOrphanSidecar(SessionEntity session) {
		Path pidFile = de.pamir.claude.ui.process.SidecarManager.pidFile(session);
		try {
			if (!Files.exists(pidFile)) {
				return;
			}
			long pid = Long.parseLong(Files.readString(pidFile).strip());
			ProcessHandle.of(pid).ifPresent(handle -> {
				String cmd = handle.info().commandLine().orElse("");
				if (cmd.contains("dist/index.js")) {
					log.info("killing orphan sidecar pid {} for session {}", pid, session.id());
					handle.descendants().forEach(ProcessHandle::destroyForcibly);
					handle.destroyForcibly();
				}
			});
			Files.deleteIfExists(pidFile);
		} catch (IOException | NumberFormatException e) {
			log.warn("orphan check for {} failed: {}", session.id(), e.getMessage());
		}
	}
}
