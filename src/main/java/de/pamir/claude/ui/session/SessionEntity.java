package de.pamir.claude.ui.session;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One row of the session table. JSONB columns surface as JsonNode / lists. */
public record SessionEntity(
		UUID id,
		String name,
		String provider,
		JsonNode providerConfig,
		String repoPath,
		String ecosystemPath,
		List<String> contextDirs,
		String branch,
		String baseBranch,
		String worktreePath,
		String providerSessionId,
		JsonNode capabilities,
		String model,
		String permissionMode,
		List<String> allowedTools,
		List<String> disallowedTools,
		JsonNode mcpConfig,
		JsonNode envVars,
		JsonNode skillSources,
		JsonNode agentSources,
		String instructions,
		String thinking,
		String effort,
		Integer maxTurns,
		String fallbackModel,
		BigDecimal costBudgetUsd,
		String kickoffPrompt,
		SessionState state,
		/** 'user' (default) or 'system' — backend-initiated tasks (ticket import, ...), hidden by default in the UI */
		String kind,
		Instant createdAt,
		Instant updatedAt
) {
}
