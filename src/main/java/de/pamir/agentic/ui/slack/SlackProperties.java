package de.pamir.agentic.ui.slack;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Slack integration config (phase 18). Env / yaml only: the tokens are secrets and the allowlist
 * is a security boundary, so none of this is editable from the dashboard.
 *
 * @param enabled      master switch; off = no Slack beans, no connection
 * @param botToken     {@code xoxb-…} bot token (Web API)
 * @param appToken     {@code xapp-…} app-level token with {@code connections:write} (Socket Mode)
 * @param channel      channel <em>ID</em> (not name) sessions are mirrored into
 * @param allowedUsers Slack user IDs whose messages and button clicks are accepted; empty = nobody
 * @param verbose      true = tool inputs are posted unmasked
 * @param publicUrl    base URL of the dashboard for the Open links (e.g. a Tailscale hostname); blank = no links
 * @param startEnabled whether an allowlisted user may start review/development sessions by mentioning the bot
 *                     (a bigger power than approving, so it has its own switch); off by default
 */
@ConfigurationProperties(prefix = "agentic-ui.slack")
public record SlackProperties(
		@DefaultValue("false") boolean enabled,
		@DefaultValue("") String botToken,
		@DefaultValue("") String appToken,
		@DefaultValue("") String channel,
		@DefaultValue List<String> allowedUsers,
		@DefaultValue("false") boolean verbose,
		@DefaultValue("") String publicUrl,
		@DefaultValue("false") boolean startEnabled) {

	public boolean hasTokens() {
		return !botToken.isBlank() && !appToken.isBlank();
	}

	/** Never prints the tokens — this record is reachable from logging and actuator paths. */
	@Override
	public String toString() {
		return "SlackProperties[enabled=" + enabled + ", botToken=" + (botToken.isBlank() ? "<unset>" : "<set>")
				+ ", appToken=" + (appToken.isBlank() ? "<unset>" : "<set>") + ", channel=" + channel
				+ ", allowedUsers=" + allowedUsers.size() + ", verbose=" + verbose + ", publicUrl=" + publicUrl + ", startEnabled=" + startEnabled + "]";
	}
}
