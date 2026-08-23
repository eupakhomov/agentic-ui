package de.pamir.claude.ui.session;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import de.pamir.claude.ui.config.AppProperties;
import de.pamir.claude.ui.git.GitCommandRunner;
import de.pamir.claude.ui.git.GitWorktreeService;
import de.pamir.claude.ui.journal.EventJournal;
import de.pamir.claude.ui.journal.SessionEventBus;
import de.pamir.claude.ui.process.SidecarManager;
import de.pamir.claude.ui.provision.AssetProvisioningService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SessionService {

	private static final Logger log = LoggerFactory.getLogger(SessionService.class);

	private final AppProperties props;
	private final SessionRepository sessions;
	private final TemplateRepository templates;
	private final GitWorktreeService worktrees;
	private final GitCommandRunner git;
	private final AssetProvisioningService assets;
	private final SidecarManager sidecars;
	private final EventJournal journal;
	private final SessionEventBus bus;
	private final ObjectMapper mapper;
	private final Map<UUID, Object> locks = new ConcurrentHashMap<>();

	public SessionService(AppProperties props, SessionRepository sessions, TemplateRepository templates,
						  GitWorktreeService worktrees, GitCommandRunner git, AssetProvisioningService assets,
						  SidecarManager sidecars, EventJournal journal, SessionEventBus bus, ObjectMapper mapper) {
		this.props = props;
		this.sessions = sessions;
		this.templates = templates;
		this.worktrees = worktrees;
		this.git = git;
		this.assets = assets;
		this.sidecars = sidecars;
		this.journal = journal;
		this.bus = bus;
		this.mapper = mapper;
	}

	// ------------------------------------------------------------------ creation

	public SessionEntity create(String name, String branch, String baseBranch, String repoPath, UUID templateId,
								JsonNode overrides, Map<String, String> kickoffValues) {
		enforceSessionLimit();
		String repo = repoPath == null || repoPath.isBlank() ? props.repoPath() : repoPath;
		if (!Files.exists(Path.of(repo).resolve(".git"))) {
			throw new IllegalArgumentException("not a git repository: " + repo);
		}
		ObjectNode config = mergedConfig(templateId, overrides);
		UUID id = UUID.randomUUID();
		Path worktree = Path.of(props.worktreeRoot()).resolve(id.toString());

		SessionEntity entity = new SessionEntity(
				id, name,
				text(config, "provider", "claude"),
				config.get("providerConfig"),
				repo,
				config.has("ecosystemPath") ? nullableText(config, "ecosystemPath") : props.ecosystemRoot(),
				stringList(config, "contextDirs"),
				branch, baseBranch, worktree.toString(),
				null, null,
				nullableText(config, "model"),
				text(config, "permissionMode", "default"),
				stringList(config, "allowedTools"),
				stringList(config, "disallowedTools"),
				config.get("mcpConfig"),
				config.get("envVars"),
				arrayOrEmpty(config, "skillSources"),
				arrayOrEmpty(config, "agentSources"),
				nullableText(config, "instructions"),
				nullableText(config, "thinking"),
				nullableText(config, "effort"),
				config.hasNonNull("maxTurns") ? config.get("maxTurns").asInt() : null,
				nullableText(config, "fallbackModel"),
				config.hasNonNull("costBudgetUsd") ? new BigDecimal(config.get("costBudgetUsd").asText()) : null,
				fillPlaceholders(nullableText(config, "kickoffPrompt"), kickoffValues),
				SessionState.CREATING, null, null);
		sessions.insert(entity);
		record(id, "state_changed", mapper.createObjectNode().put("state", "CREATING"));

		try {
			transition(id, SessionState.PROVISIONING);
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
			if (session.providerSessionId() == null) {
				throw new IllegalStateException("session has no provider session id to resume from");
			}
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
				dispatch(id, text);
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
		}
	}

	// ------------------------------------------------------------------ sidecar event handling

	private void onSidecarEvent(UUID id, JsonNode event) {
		String type = event.path("type").asText("unknown");
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
			case "system_init" -> sessions.updateProviderSessionId(id, event.path("providerSessionId").asText());
			case "permission_request" -> transition(id, SessionState.WAITING_INPUT);
			case "permission_mode_changed" -> sessions.updatePermissionMode(id, event.path("mode").asText());
			case "turn_complete" -> {
				synchronized (lock(id)) {
					becomeIdleAndDrainQueue(id);
				}
			}
			default -> { /* journaled above; no state effect */ }
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
		}
	}

	// ------------------------------------------------------------------ internals

	private void spawn(SessionEntity session, boolean resume) {
		sidecars.spawn(session, mcpConfigPath(session.id()), resume,
				event -> onSidecarEvent(session.id(), event),
				(handle, code) -> onSidecarExit(session.id(), code, handle.stderrTail(), handle.isShutdownRequested()));
	}

	private void dispatch(UUID id, String text) {
		record(id, "user_message", mapper.createObjectNode().put("text", text));
		sidecars.handle(id).send(mapper.createObjectNode()
				.put("type", "user_message").put("text", text).toString());
		transition(id, SessionState.RUNNING);
	}

	private void becomeIdleAndDrainQueue(UUID id) {
		transition(id, SessionState.IDLE);
		sessions.pollQueue(id).ifPresent(next -> {
			recordQueue(id);
			dispatch(id, next.text());
		});
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
				Files.writeString(exclude, "\n.claude/skills/\n.claude/agents/\n",
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

	// ------------------------------------------------------------------ startup sweep

	@EventListener(ApplicationReadyEvent.class)
	void markOrphanedSessionsCrashed() {
		for (SessionEntity session : sessions.findByStates(List.copyOf(SessionState.LIVE))) {
			log.info("startup sweep: session {} was {} -> CRASHED", session.id(), session.state());
			record(session.id(), "error", mapper.createObjectNode()
					.put("message", "backend restarted while session was live").put("fatal", true));
			transition(session.id(), SessionState.CRASHED);
		}
	}
}
