package de.pamir.claude.ui.config;

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
		String serviceDiscoveryModel) {
}
