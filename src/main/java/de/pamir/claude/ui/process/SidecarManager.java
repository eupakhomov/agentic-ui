package de.pamir.claude.ui.process;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import de.pamir.claude.ui.config.AppProperties;
import de.pamir.claude.ui.session.ProviderCapabilities;
import de.pamir.claude.ui.session.ProviderCatalog;
import de.pamir.claude.ui.session.SessionEntity;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Spawns and tracks one sidecar per session, building CLI args from the session row. */
@Component
public class SidecarManager {

	private static final Logger log = LoggerFactory.getLogger(SidecarManager.class);

	private final AppProperties props;
	private final ObjectMapper mapper;
	private final ProviderCatalog catalog;
	private final Map<UUID, SidecarHandle> handles = new ConcurrentHashMap<>();

	public SidecarManager(AppProperties props, ObjectMapper mapper, ProviderCatalog catalog) {
		this.props = props;
		this.mapper = mapper;
		this.catalog = catalog;
	}

	public SidecarHandle spawn(SessionEntity session, Path mcpConfigFile, boolean resume,
							   Consumer<JsonNode> onEvent, BiConsumer<SidecarHandle, Integer> onExit) {
		return spawn(session, mcpConfigFile, resume, null, onEvent, onExit);
	}

	/**
	 * @param extraSystemPrompt appended alongside the session's own {@code instructions} in
	 *                          {@code --append-system-prompt} — used for the memory episodic
	 *                          window (docs/plan/phase-5.3-memory-reflection.md), computed fresh
	 *                          on every spawn (including resume/wake) rather than stored, so a
	 *                          park→wake picks up episodes reflected meanwhile for free.
	 */
	public SidecarHandle spawn(SessionEntity session, Path mcpConfigFile, boolean resume, String extraSystemPrompt,
							   Consumer<JsonNode> onEvent, BiConsumer<SidecarHandle, Integer> onExit) {
		AppProperties.Provider provider = props.providers().get(session.provider());
		if (provider == null) {
			throw new IllegalArgumentException("unknown provider: " + session.provider());
		}
		List<String> command = new ArrayList<>();
		for (String part : provider.command()) {
			// relative launcher paths (e.g. sidecar/dist/index.js) resolve against the app's cwd
			command.add(part.contains("/") && !Path.of(part).isAbsolute()
					? Path.of(part).toAbsolutePath().normalize().toString()
					: part);
		}
		command.addAll(buildArgs(session, mcpConfigFile, resume, extraSystemPrompt));

		ProcessBuilder builder = new ProcessBuilder(command).directory(Path.of(session.worktreePath()).toFile());
		if (session.envVars() != null && session.envVars().isObject()) {
			session.envVars().properties().forEach(e -> builder.environment().put(e.getKey(), e.getValue().asText()));
		}
		try {
			Process process = builder.start();
			Path stderrLog = Path.of(props.logDir(), "sidecar", session.id() + ".log");
			SidecarHandle handle = new SidecarHandle(session.id(), process, mapper, stderrLog, onEvent, (h, code) -> {
				handles.remove(session.id(), h);
				deletePidFile(session);
				onExit.accept(h, code);
			});
			// handles.put before watchExit: a process that's already dead on arrival must not be
			// able to fire the exit callback (removing an entry that was never inserted) before
			// the entry actually exists — see docs/plan/phase-10-review-followups.md R5.
			handles.put(session.id(), handle);
			handle.watchExit();
			writePidFile(session, handle.pid());
			log.info("session {}: sidecar pid {} spawned ({})", session.id(), handle.pid(), String.join(" ", command));
			return handle;
		} catch (IOException e) {
			throw new IllegalStateException("failed to start sidecar: " + e.getMessage()
					+ " (is node on the backend's PATH?)", e);
		}
	}

	/** PID file inside the worktree lets a restarted backend kill orphans from a kill -9'd predecessor. */
	private void writePidFile(SessionEntity session, long pid) {
		try {
			Files.writeString(pidFile(session), Long.toString(pid));
		} catch (IOException e) {
			log.warn("session {}: cannot write pid file: {}", session.id(), e.getMessage());
		}
	}

	private void deletePidFile(SessionEntity session) {
		try {
			Files.deleteIfExists(pidFile(session));
		} catch (IOException ignored) {
			// worktree may already be gone
		}
	}

	public static Path pidFile(SessionEntity session) {
		return Path.of(session.worktreePath(), ".claude-ui.pid");
	}

	public SidecarHandle handle(UUID sessionId) {
		SidecarHandle handle = handles.get(sessionId);
		if (handle == null || !handle.isAlive()) {
			throw new IllegalStateException("no live sidecar for session " + sessionId);
		}
		return handle;
	}

	public boolean hasLiveHandle(UUID sessionId) {
		SidecarHandle handle = handles.get(sessionId);
		return handle != null && handle.isAlive();
	}

	public void terminate(UUID sessionId) {
		SidecarHandle handle = handles.remove(sessionId);
		if (handle != null) {
			handle.terminate();
		}
	}

	@PreDestroy
	void terminateAll() {
		log.info("terminating {} sidecar(s)", handles.size());
		handles.keySet().forEach(this::terminate);
	}

	// package-private (not private): unit-tested directly — see SidecarManagerTest /
	// docs/plan/phase-10-review-followups.md R1's DoD.
	List<String> buildArgs(SessionEntity s, Path mcpConfigFile, boolean resume, String extraSystemPrompt) {
		// Not every adapter understands every flag — see the provider's own capabilities.json
		// (docs/plan/phase-10-review-followups.md R1). SessionConfigFactory.prepare already
		// rejects a session that explicitly set a field its provider doesn't support, so these
		// guards are defense-in-depth, not the primary enforcement point. --mcp-config and
		// --append-system-prompt are supported by every adapter today, so they're passed through
		// unconditionally below.
		ProviderCapabilities caps = catalog.get(s.provider());

		// --cwd is the service's own subfolder (equal to the worktree root for a polyrepo
		// session); --writable-root is unconditionally the worktree root — both adapters honor
		// it (docs/plan/phase-11-monorepo.md Step 4), no capability gate needed.
		List<String> args = new ArrayList<>(List.of("--cwd", s.cwdPath(), "--writable-root", s.worktreePath()));
		if (resume && s.providerSessionId() != null) {
			args.addAll(List.of("--resume", s.providerSessionId()));
		}
		if (s.model() != null) {
			args.addAll(List.of("--model", s.model()));
		}
		if (caps.supports("fallbackModel") && s.fallbackModel() != null) {
			args.addAll(List.of("--fallback-model", s.fallbackModel()));
		}
		if (s.permissionMode() != null) {
			args.addAll(List.of("--permission-mode", s.permissionMode()));
		}
		if (caps.supports("allowedTools") && !s.allowedTools().isEmpty()) {
			args.addAll(List.of("--allowed-tools", String.join(",", s.allowedTools())));
		}
		if (caps.supports("disallowedTools") && !s.disallowedTools().isEmpty()) {
			args.addAll(List.of("--disallowed-tools", String.join(",", s.disallowedTools())));
		}
		if (mcpConfigFile != null && Files.exists(mcpConfigFile)) {
			args.addAll(List.of("--mcp-config", mcpConfigFile.toString()));
		}
		String systemPrompt = Stream.of(s.instructions(), extraSystemPrompt)
				.filter(p -> p != null && !p.isBlank()).collect(Collectors.joining("\n\n"));
		if (!systemPrompt.isBlank()) {
			args.addAll(List.of("--append-system-prompt", systemPrompt));
		}
		if (caps.contextDirs()) {
			// Monorepo (docs/plan/phase-11-monorepo.md Step 4): the worktree is this session's own
			// fresh checkout of the service's repo, so it always replaces (layout (a): ecosystem
			// root IS that repo) or joins (layout (b): ecosystem root is a wider folder) the
			// ecosystem context — using the ecosystem path alone in layout (a) would attach the
			// *original, stale* checkout of the very code this session is editing.
			boolean monorepo = !s.servicePath().equals(s.repoPath());
			boolean ecosystemConfigured = s.ecosystemPath() != null && !s.ecosystemPath().isBlank();
			if (monorepo) {
				args.addAll(List.of("--context-dir", s.worktreePath()));
				if (ecosystemConfigured && !Path.of(s.ecosystemPath()).equals(Path.of(s.repoPath()))) {
					args.addAll(List.of("--context-dir", s.ecosystemPath()));
				}
			} else if (ecosystemConfigured) {
				args.addAll(List.of("--context-dir", s.ecosystemPath()));
			}
			for (String dir : s.contextDirs()) {
				args.addAll(List.of("--context-dir", dir));
			}
		}
		if (caps.supports("thinking") && s.thinking() != null) {
			args.addAll(List.of("--thinking", s.thinking()));
		}
		if (s.effort() != null) {
			args.addAll(List.of("--effort", s.effort()));
		}
		if (caps.supports("maxTurns") && s.maxTurns() != null) {
			args.addAll(List.of("--max-turns", s.maxTurns().toString()));
		}
		return args;
	}
}
