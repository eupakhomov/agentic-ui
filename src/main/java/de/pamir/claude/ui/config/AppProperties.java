package de.pamir.claude.ui.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
		Map<String, Provider> providers
) {

	public record Provider(List<String> command) {
	}
}
