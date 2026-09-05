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
		/** Canonical ticket identifier (e.g. "ENG-123") if this session was created via ticket import; null otherwise */
		String ticketRef,
		/** GitHub PR URL opened from this session's branch, if any; one PR tracked per session */
		String prUrl,
		/** Head commit the last check result applies to — a mismatch on the next poll means new commits were pushed */
		String prHeadSha,
		/** PENDING | SUCCESS | FAILURE | MERGED | CLOSED | ERROR; null when prUrl is null */
		String prCheckStatus,
		Instant prCheckedAt,
		/** Opt-in end-of-session memory retrospective (see docs/plan/phase-5.3-memory-reflection.md) */
		boolean reflectionEnabled,
		/** Journal seq covered by the last reflection; null = never reflected */
		Long reflectedSeq,
		Instant createdAt,
		Instant updatedAt
) {
}
