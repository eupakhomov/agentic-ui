package de.pamir.claude.ui.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "claude-ui")
public record AppProperties(
		String repoPath,
		String worktreeRoot,
		String ecosystemRoot,
		String skillsRoot,
		int maxSessions,
		String authToken,
		/** Linear personal API key (Bearer token for https://mcp.linear.app/mcp); blank/unset = ticket import disabled */
		@DefaultValue("") String linearApiKey,
		/**
		 * Alternative to linearApiKey for SSO-gated Linear accounts (e.g. Google identity): omits the
		 * Authorization header entirely, trusting the ambient `claude` CLI's own cached OAuth credential
		 * for this MCP server (set up once via `claude mcp add` on the backend host). Ignored if
		 * linearApiKey is set — the explicit key always wins.
		 */
		@DefaultValue("false") boolean linearOAuth,
		@DefaultValue("logs") String logDir,
		@DefaultValue("30") int idleParkMinutes,
		@DefaultValue("65536") int journalPayloadCapBytes,
		@DefaultValue("1048576") int wsSendBufferBytes,
		Map<String, Provider> providers
) {

	public record Provider(List<String> command) {
	}
}
