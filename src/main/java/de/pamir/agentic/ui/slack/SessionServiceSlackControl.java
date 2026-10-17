package de.pamir.agentic.ui.slack;

import de.pamir.agentic.ui.session.PendingPermissions.Pending;
import de.pamir.agentic.ui.session.SessionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@link SlackSessionControl} on the real {@link SessionService}. */
@Component
@ConditionalOnProperty(prefix = "artus.slack", name = "enabled", havingValue = "true")
class SessionServiceSlackControl implements SlackSessionControl {

	private final SessionService sessions;

	SessionServiceSlackControl(SessionService sessions) {
		this.sessions = sessions;
	}

	@Override
	public Optional<Pending> pending(UUID sessionId, String requestId) {
		return sessions.pendingPermissions().peek(sessionId, requestId);
	}

	@Override
	public List<Pending> pendingList(UUID sessionId) {
		return sessions.pendingPermissions().list(sessionId);
	}

	@Override
	public boolean respondIfPending(UUID sessionId, ObjectNode permissionResponse) {
		return sessions.respondPermissionIfPending(sessionId, permissionResponse);
	}

	@Override
	public void sendPrompt(UUID sessionId, String text) {
		sessions.sendUserMessage(sessionId, text);
	}

	@Override
	public void interrupt(UUID sessionId) {
		sessions.interrupt(sessionId);
	}

	@Override
	public void closeClean(UUID sessionId) {
		sessions.close(sessionId, "fail", null);
	}

	@Override
	public void audit(UUID sessionId, ObjectNode payload) {
		sessions.recordExternalInput(sessionId, payload);
	}
}
