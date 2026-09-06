package de.pamir.claude.ui.session;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

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
		/** Source session this one carried a handoff summary/digest from (see docs/plan/phase-7-ux-and-orchestration.md 7.3); null otherwise */
		UUID continuedFromId,
		/** Parent session this one was spawned by via spawn_child_session (7.4); null for ordinary/parent sessions. Depth 1 — a child's own parentSessionId is never set on ITS children, because it can't have any */
		UUID parentSessionId,
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

	public static Builder builder() {
		return new Builder();
	}

	/** A builder pre-seeded with this entity's own fields, for a "copy with one field changed" update. */
	public Builder toBuilder() {
		return builder().id(id).name(name).provider(provider).providerConfig(providerConfig)
				.repoPath(repoPath).ecosystemPath(ecosystemPath).contextDirs(contextDirs)
				.branch(branch).baseBranch(baseBranch).worktreePath(worktreePath)
				.providerSessionId(providerSessionId).capabilities(capabilities).model(model)
				.permissionMode(permissionMode).allowedTools(allowedTools).disallowedTools(disallowedTools)
				.mcpConfig(mcpConfig).envVars(envVars).skillSources(skillSources).agentSources(agentSources)
				.instructions(instructions).thinking(thinking).effort(effort).maxTurns(maxTurns)
				.fallbackModel(fallbackModel).costBudgetUsd(costBudgetUsd).kickoffPrompt(kickoffPrompt)
				.state(state).kind(kind).ticketRef(ticketRef).continuedFromId(continuedFromId)
				.parentSessionId(parentSessionId).prUrl(prUrl).prHeadSha(prHeadSha).prCheckStatus(prCheckStatus)
				.prCheckedAt(prCheckedAt).reflectionEnabled(reflectionEnabled).reflectedSeq(reflectedSeq)
				.createdAt(createdAt).updatedAt(updatedAt);
	}

	/**
	 * Named-setter alternative to the record's ~35-arg positional constructor (see
	 * docs/plan/phase-9-production-hardening.md S3) — used at the two call sites that build a
	 * fresh entity from scratch ({@code SessionConfigFactory.build}, {@code
	 * SystemSessionService.createSystemSession}); {@code SessionRepository.mapRow} keeps the
	 * positional constructor since it's already an unambiguous 1:1 column mapping in field order.
	 * Fields default to {@code null}/empty exactly as the equivalent positional call's long
	 * {@code null, null, ...} runs did.
	 */
	public static final class Builder {
		private UUID id;
		private String name;
		private String provider;
		private JsonNode providerConfig;
		private String repoPath;
		private String ecosystemPath;
		private List<String> contextDirs = List.of();
		private String branch;
		private String baseBranch;
		private String worktreePath;
		private String providerSessionId;
		private JsonNode capabilities;
		private String model;
		private String permissionMode = "default";
		private List<String> allowedTools = List.of();
		private List<String> disallowedTools = List.of();
		private JsonNode mcpConfig;
		private JsonNode envVars;
		// column is NOT NULL — default to empty rather than making every caller (tests especially)
		// spell out ".skillSources(mapper.createArrayNode())" for the common "no sources" case
		private JsonNode skillSources = JsonNodeFactory.instance.arrayNode();
		private JsonNode agentSources = JsonNodeFactory.instance.arrayNode();
		private String instructions;
		private String thinking;
		private String effort;
		private Integer maxTurns;
		private String fallbackModel;
		private BigDecimal costBudgetUsd;
		private String kickoffPrompt;
		private SessionState state = SessionState.CREATING;
		private String kind = "user";
		private String ticketRef;
		private UUID continuedFromId;
		private UUID parentSessionId;
		private String prUrl;
		private String prHeadSha;
		private String prCheckStatus;
		private Instant prCheckedAt;
		private boolean reflectionEnabled;
		private Long reflectedSeq;
		private Instant createdAt;
		private Instant updatedAt;

		private Builder() {
		}

		public Builder id(UUID v) { this.id = v; return this; }
		public Builder name(String v) { this.name = v; return this; }
		public Builder provider(String v) { this.provider = v; return this; }
		public Builder providerConfig(JsonNode v) { this.providerConfig = v; return this; }
		public Builder repoPath(String v) { this.repoPath = v; return this; }
		public Builder ecosystemPath(String v) { this.ecosystemPath = v; return this; }
		public Builder contextDirs(List<String> v) { this.contextDirs = v; return this; }
		public Builder branch(String v) { this.branch = v; return this; }
		public Builder baseBranch(String v) { this.baseBranch = v; return this; }
		public Builder worktreePath(String v) { this.worktreePath = v; return this; }
		public Builder providerSessionId(String v) { this.providerSessionId = v; return this; }
		public Builder capabilities(JsonNode v) { this.capabilities = v; return this; }
		public Builder model(String v) { this.model = v; return this; }
		public Builder permissionMode(String v) { this.permissionMode = v; return this; }
		public Builder allowedTools(List<String> v) { this.allowedTools = v; return this; }
		public Builder disallowedTools(List<String> v) { this.disallowedTools = v; return this; }
		public Builder mcpConfig(JsonNode v) { this.mcpConfig = v; return this; }
		public Builder envVars(JsonNode v) { this.envVars = v; return this; }
		public Builder skillSources(JsonNode v) { this.skillSources = v; return this; }
		public Builder agentSources(JsonNode v) { this.agentSources = v; return this; }
		public Builder instructions(String v) { this.instructions = v; return this; }
		public Builder thinking(String v) { this.thinking = v; return this; }
		public Builder effort(String v) { this.effort = v; return this; }
		public Builder maxTurns(Integer v) { this.maxTurns = v; return this; }
		public Builder fallbackModel(String v) { this.fallbackModel = v; return this; }
		public Builder costBudgetUsd(BigDecimal v) { this.costBudgetUsd = v; return this; }
		public Builder kickoffPrompt(String v) { this.kickoffPrompt = v; return this; }
		public Builder state(SessionState v) { this.state = v; return this; }
		public Builder kind(String v) { this.kind = v; return this; }
		public Builder ticketRef(String v) { this.ticketRef = v; return this; }
		public Builder continuedFromId(UUID v) { this.continuedFromId = v; return this; }
		public Builder parentSessionId(UUID v) { this.parentSessionId = v; return this; }
		public Builder prUrl(String v) { this.prUrl = v; return this; }
		public Builder prHeadSha(String v) { this.prHeadSha = v; return this; }
		public Builder prCheckStatus(String v) { this.prCheckStatus = v; return this; }
		public Builder prCheckedAt(Instant v) { this.prCheckedAt = v; return this; }
		public Builder reflectionEnabled(boolean v) { this.reflectionEnabled = v; return this; }
		public Builder reflectedSeq(Long v) { this.reflectedSeq = v; return this; }
		public Builder createdAt(Instant v) { this.createdAt = v; return this; }
		public Builder updatedAt(Instant v) { this.updatedAt = v; return this; }

		public SessionEntity build() {
			return new SessionEntity(id, name, provider, providerConfig, repoPath, ecosystemPath, contextDirs,
					branch, baseBranch, worktreePath, providerSessionId, capabilities, model, permissionMode,
					allowedTools, disallowedTools, mcpConfig, envVars, skillSources, agentSources, instructions,
					thinking, effort, maxTurns, fallbackModel, costBudgetUsd, kickoffPrompt, state, kind, ticketRef,
					continuedFromId, parentSessionId, prUrl, prHeadSha, prCheckStatus, prCheckedAt, reflectionEnabled,
					reflectedSeq, createdAt, updatedAt);
		}
	}
}
