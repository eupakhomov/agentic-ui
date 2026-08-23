package de.pamir.claude.ui.session;

import java.util.EnumSet;
import java.util.Set;

public enum SessionState {
	CREATING,
	PROVISIONING,
	STARTING,
	IDLE,
	RUNNING,
	WAITING_INPUT,
	CRASHED,
	CLOSING,
	CLOSED,
	FAILED;

	/** States in which a sidecar process is expected to be alive. */
	public static final Set<SessionState> LIVE = EnumSet.of(STARTING, IDLE, RUNNING, WAITING_INPUT);

	public boolean acceptsMessages() {
		return LIVE.contains(this);
	}
}
