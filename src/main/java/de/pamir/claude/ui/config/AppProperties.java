package de.pamir.claude.ui.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "claude-ui")
public record AppProperties(
		String repoPath,
		String worktreeRoot,
		String skillsRoot,
		String memoryRoot,
		int maxSessions,
		String authToken,
		/** Linear personal API key (Bearer token for https://mcp.linear.app/mcp); blank/unset = ticket import disabled */
		@DefaultValue("") String linearApiKey,
		/** Voyage AI API key for library embeddings; blank/unset = vectorize & semantic search disabled */
		@DefaultValue("") String voyageApiKey,
		@DefaultValue("logs") String logDir,
		@DefaultValue("30") int idleParkMinutes,
		@DefaultValue("65536") int journalPayloadCapBytes,
		@DefaultValue("1048576") int wsSendBufferBytes,
		Map<String, Provider> providers
) {

	public record Provider(List<String> command) {
	}
}
