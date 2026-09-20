package de.pamir.agentic.ui.config;

/**
 * Typed snapshot of every persisted, UI-editable setting (Settings dialog) except per-provider
 * pricing ({@link SettingsService#pricingFor}, dynamically keyed on the provider id, so it can't
 * live as a fixed component here — see docs/plan/phase-10-review-followups.md R8c). {@code
 * systemProvider} holds the raw override (blank = "follow {@code defaultProvider}"), matching what
 * the Settings dialog needs to round-trip — {@link SettingsService#systemProvider()} resolves the
 * fallback for internal callers.
 */
public record Settings(
		boolean linearOAuthEnabled,
		String ticketImportSpec,
		String ecosystemRoot,
		String monorepoServiceGlobs,
		boolean monorepoDetectionEnabled,
		boolean prChecksEnabled,
		int prCheckPollIntervalSeconds,
		String librarySkillsRoot,
		String libraryAgentsRoot,
		boolean libraryVectorize,
		boolean librarySyncEnabled,
		int librarySyncIntervalMinutes,
		String defaultProvider,
		String systemProvider,
		String memoryRoot,
		boolean memoryEnabled,
		boolean memoryReflectionDefault,
		String memoryReflectionModel,
		int memorySyncIntervalMinutes,
		int memoryRetentionDays,
		boolean memoryReflectionApprovalRequired,
		boolean serviceDiscoveryEnabled,
		int serviceDiscoveryStalenessDays,
		String serviceDiscoveryModel,
		/** Widget chip warn threshold, 30-95 (docs/plan/phase-12-linear-cache-serena-context.md decision 10) */
		int contextWarnPercent,
		/** Serena checkout root; empty = Serena unavailable (docs/plan/phase-12-linear-cache-serena-context.md Track B) */
		String mcpSerenaRoot,
		/** {@code uv} command/path, for hosts where it isn't on the backend's PATH; defaults to "uv" */
		String mcpUvPath,
		/** graphify checkout root; empty = graphify unavailable (docs/plan/phase-13-graphify.md Step 1) */
		String mcpGraphifyRoot,
		/**
		 * Which code-intelligence MCP tool sessions may opt into: {@code none}/{@code serena}/{@code
		 * graphify} — one per install (phase-13 decision 1). Always resolved here: an unset selector
		 * reads {@code serena} when a Serena root is configured, else {@code none} (decision 12).
		 */
		String codeIntel) {
}
