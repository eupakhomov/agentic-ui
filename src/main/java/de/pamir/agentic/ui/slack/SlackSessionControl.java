package de.pamir.agentic.ui.slack;

import de.pamir.agentic.ui.session.PendingPermissions.Pending;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What Slack may do to a session — the same operations the dashboard has, and nothing else. A small
 * interface (rather than {@code SessionService} itself) so the inbound handler can be tested against
 * a fake and so its reach is visible in one place.
 */
interface SlackSessionControl {

	Optional<Pending> pending(UUID sessionId, String requestId);

	/** All requests the session is blocked on, oldest first. */
	List<Pending> pendingList(UUID sessionId);

	/** Delivers a permission decision only if the request is still pending; false = already decided. */
	boolean respondIfPending(UUID sessionId, ObjectNode permissionResponse);

	/** A prompt typed in the thread: queued behind a running turn, like the dashboard's composer. */
	void sendPrompt(UUID sessionId, String text);

	void interrupt(UUID sessionId);

	/**
	 * Closes the session and removes its worktree, but only if that loses nothing: with uncommitted changes it
	 * throws {@link de.pamir.agentic.ui.session.DirtyWorktreeException} (listing the files) and leaves everything as it
	 * was. Never commits, stashes or discards — those choices stay on the dashboard.
	 */
	void closeClean(UUID sessionId);

	/** Journals who asked for what, for anything done from Slack. */
	void audit(UUID sessionId, ObjectNode payload);
}
