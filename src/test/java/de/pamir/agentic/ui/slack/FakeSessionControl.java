package de.pamir.agentic.ui.slack;

import de.pamir.agentic.ui.session.PendingPermissions;
import de.pamir.agentic.ui.session.PendingPermissions.Pending;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Recording {@link SlackSessionControl} on a real {@link PendingPermissions}, so "still pending" behaves truly. */
final class FakeSessionControl implements SlackSessionControl {

	final PendingPermissions pending = new PendingPermissions();
	final List<ObjectNode> responses = Collections.synchronizedList(new ArrayList<>());
	final List<String> prompts = Collections.synchronizedList(new ArrayList<>());
	final List<UUID> interrupts = Collections.synchronizedList(new ArrayList<>());
	final List<ObjectNode> audits = Collections.synchronizedList(new ArrayList<>());
	final List<UUID> closed = Collections.synchronizedList(new ArrayList<>());
	volatile RuntimeException failClose;
	volatile RuntimeException failPrompt;
	volatile RuntimeException failRespond;

	void addPending(UUID session, String requestId, String tool, JsonNode input) {
		pending.add(session, requestId, tool, input);
	}

	@Override
	public Optional<Pending> pending(UUID sessionId, String requestId) {
		return pending.peek(sessionId, requestId);
	}

	@Override
	public List<Pending> pendingList(UUID sessionId) {
		return pending.list(sessionId);
	}

	@Override
	public boolean respondIfPending(UUID sessionId, ObjectNode permissionResponse) {
		if (failRespond != null) {
			throw failRespond;
		}
		if (pending.take(sessionId, permissionResponse.path("requestId").asText("")).isEmpty()) {
			return false;
		}
		responses.add(permissionResponse);
		return true;
	}

	@Override
	public void sendPrompt(UUID sessionId, String text) {
		if (failPrompt != null) {
			throw failPrompt;
		}
		prompts.add(text);
	}

	@Override
	public void interrupt(UUID sessionId) {
		interrupts.add(sessionId);
	}

	@Override
	public void closeClean(UUID sessionId) {
		if (failClose != null) {
			throw failClose;
		}
		closed.add(sessionId);
	}

	@Override
	public void audit(UUID sessionId, ObjectNode payload) {
		audits.add(payload);
	}
}
