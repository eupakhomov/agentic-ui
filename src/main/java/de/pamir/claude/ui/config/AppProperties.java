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
		@DefaultValue("logs") String logDir,
		@DefaultValue("30") int idleParkMinutes,
		@DefaultValue("65536") int journalPayloadCapBytes,
		@DefaultValue("1048576") int wsSendBufferBytes,
		Map<String, Provider> providers
) {

	public record Provider(List<String> command) {
	}
}
