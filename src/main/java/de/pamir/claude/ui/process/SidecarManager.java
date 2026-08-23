package de.pamir.claude.ui.process;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import de.pamir.claude.ui.config.AppProperties;
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

/** Spawns and tracks one sidecar per session, building CLI args from the session row. */
@Component
public class SidecarManager {

	private static final Logger log = LoggerFactory.getLogger(SidecarManager.class);

	private final AppProperties props;
	private final ObjectMapper mapper;
	private final Map<UUID, SidecarHandle> handles = new ConcurrentHashMap<>();

	public SidecarManager(AppProperties props, ObjectMapper mapper) {
		this.props = props;
		this.mapper = mapper;
	}

	public SidecarHandle spawn(SessionEntity session, Path mcpConfigFile, boolean resume,
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
		command.addAll(buildArgs(session, mcpConfigFile, resume));

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
			handles.put(session.id(), handle);
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

	private List<String> buildArgs(SessionEntity s, Path mcpConfigFile, boolean resume) {
		List<String> args = new ArrayList<>(List.of("--cwd", s.worktreePath()));
		if (resume && s.providerSessionId() != null) {
			args.addAll(List.of("--resume", s.providerSessionId()));
		}
		if (s.model() != null) {
			args.addAll(List.of("--model", s.model()));
		}
		if (s.fallbackModel() != null) {
			args.addAll(List.of("--fallback-model", s.fallbackModel()));
		}
		if (s.permissionMode() != null) {
			args.addAll(List.of("--permission-mode", s.permissionMode()));
		}
		if (!s.allowedTools().isEmpty()) {
			args.addAll(List.of("--allowed-tools", String.join(",", s.allowedTools())));
		}
		if (!s.disallowedTools().isEmpty()) {
			args.addAll(List.of("--disallowed-tools", String.join(",", s.disallowedTools())));
		}
		if (mcpConfigFile != null && Files.exists(mcpConfigFile)) {
			args.addAll(List.of("--mcp-config", mcpConfigFile.toString()));
		}
		if (s.instructions() != null && !s.instructions().isBlank()) {
			args.addAll(List.of("--append-system-prompt", s.instructions()));
		}
		if (s.ecosystemPath() != null && !s.ecosystemPath().isBlank()) {
			args.addAll(List.of("--context-dir", s.ecosystemPath()));
		}
		for (String dir : s.contextDirs()) {
			args.addAll(List.of("--context-dir", dir));
		}
		if (s.thinking() != null) {
			args.addAll(List.of("--thinking", s.thinking()));
		}
		if (s.effort() != null) {
			args.addAll(List.of("--effort", s.effort()));
		}
		if (s.maxTurns() != null) {
			args.addAll(List.of("--max-turns", s.maxTurns().toString()));
		}
		return args;
	}
}
