package de.pamir.agentic.ui.session;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The permission requests (tool approvals, plan approvals, questions) a live session is currently
 * blocked on, in arrival order. It exists so that two places deciding the same request — the
 * dashboard and Slack (phase 18) — can tell who got there first: {@link #take} removes a request
 * atomically and says whether it was still pending, so exactly one decision wins and the other can
 * report "already decided" instead of sending a second response into the sidecar.
 *
 * <p>In memory only, on purpose: a pending request lives inside a sidecar process, and when that
 * process (or the backend) goes away the request is gone with it. Cleared when a turn ends, the
 * sidecar exits, or the session closes.
 */
public final class PendingPermissions {

	public record Pending(String requestId, String toolName, JsonNode input) {
	}

	private final Map<UUID, Map<String, Pending>> bySession = new ConcurrentHashMap<>();

	public void add(UUID sessionId, String requestId, String toolName, JsonNode input) {
		if (requestId == null || requestId.isBlank()) {
			return;
		}
		Map<String, Pending> pending = bySession.computeIfAbsent(sessionId, id -> new LinkedHashMap<>());
		synchronized (pending) {
			pending.put(requestId, new Pending(requestId, toolName, input));
		}
	}

	/** Removes and returns the request if it was still pending; empty if it was already decided or never existed. */
	public Optional<Pending> take(UUID sessionId, String requestId) {
		Map<String, Pending> pending = bySession.get(sessionId);
		if (pending == null || requestId == null) {
			return Optional.empty();
		}
		synchronized (pending) {
			return Optional.ofNullable(pending.remove(requestId));
		}
	}

	public Optional<Pending> peek(UUID sessionId, String requestId) {
		Map<String, Pending> pending = bySession.get(sessionId);
		if (pending == null || requestId == null) {
			return Optional.empty();
		}
		synchronized (pending) {
			return Optional.ofNullable(pending.get(requestId));
		}
	}

	/** Pending requests oldest first. */
	public List<Pending> list(UUID sessionId) {
		Map<String, Pending> pending = bySession.get(sessionId);
		if (pending == null) {
			return List.of();
		}
		synchronized (pending) {
			return new ArrayList<>(pending.values());
		}
	}

	public void clear(UUID sessionId) {
		bySession.remove(sessionId);
	}
}
