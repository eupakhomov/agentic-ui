package de.pamir.agentic.ui.slack;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Whether the dashboard should offer Slack at all. {@code enabled} is the server switch;
 * {@code connected} says the Socket Mode connection is currently up. Reveals no token, channel or
 * user ID. Always registered (the Slack beans themselves exist only when enabled).
 */
@RestController
@RequestMapping("/api/slack")
public class SlackStatusController {

	public record SlackStatus(boolean enabled, boolean connected) {
	}

	private final SlackProperties props;
	private final ObjectProvider<SlackSocket> socket;

	public SlackStatusController(SlackProperties props, ObjectProvider<SlackSocket> socket) {
		this.props = props;
		this.socket = socket;
	}

	@GetMapping("/status")
	public SlackStatus status() {
		SlackSocket s = socket.getIfAvailable();
		return new SlackStatus(props.enabled(), s != null && s.connected());
	}
}
