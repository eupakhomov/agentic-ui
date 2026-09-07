package de.pamir.claude.ui.session;

import de.pamir.claude.ui.process.FakeSidecar;
import de.pamir.claude.ui.process.SidecarHandle;
import de.pamir.claude.ui.process.SidecarManager;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * In-memory {@link SidecarManager} double backed by {@link FakeSidecar}'s fake-Process handles —
 * no real OS process spawn, no Mockito. See docs/plan/phase-9-production-hardening.md T2.
 */
final class FakeSidecarManager extends SidecarManager {

	private final Map<UUID, SidecarHandle> handles = new ConcurrentHashMap<>();
	private final Map<UUID, List<String>> sentLines = new ConcurrentHashMap<>();
	/** Session ids whose handle() should throw, simulating a dead/broken sidecar. */
	final Set<UUID> brokenHandles = ConcurrentHashMap.newKeySet();

	FakeSidecarManager() {
		super(null, null, null);
	}

	@Override
	public SidecarHandle spawn(SessionEntity session, Path mcpConfigFile, boolean resume, String extraSystemPrompt,
							   Consumer<JsonNode> onEvent, BiConsumer<SidecarHandle, Integer> onExit) {
		List<String> lines = sentLines.computeIfAbsent(session.id(), k -> new CopyOnWriteArrayList<>());
		SidecarHandle handle = FakeSidecar.newHandle(session.id(), lines, onEvent, onExit);
		handles.put(session.id(), handle);
		return handle;
	}

	@Override
	public SidecarHandle handle(UUID sessionId) {
		if (brokenHandles.contains(sessionId)) {
			throw new IllegalStateException("no live sidecar for session " + sessionId);
		}
		SidecarHandle handle = handles.get(sessionId);
		if (handle == null) {
			throw new IllegalStateException("no live sidecar for session " + sessionId);
		}
		return handle;
	}

	@Override
	public boolean hasLiveHandle(UUID sessionId) {
		return !brokenHandles.contains(sessionId) && handles.containsKey(sessionId);
	}

	@Override
	public void terminate(UUID sessionId) {
		SidecarHandle handle = handles.remove(sessionId);
		if (handle != null) {
			handle.terminate();
		}
	}

	List<String> sentTo(UUID sessionId) {
		return sentLines.getOrDefault(sessionId, List.of());
	}
}
