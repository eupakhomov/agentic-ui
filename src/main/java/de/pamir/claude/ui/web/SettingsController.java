package de.pamir.claude.ui.web;

import de.pamir.claude.ui.config.AppProperties;
import de.pamir.claude.ui.config.SettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Persisted, UI-editable settings (Settings dialog). Secrets (the Linear API key) never appear here. */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

	public record SettingsView(boolean linearOAuthEnabled, String ticketImportSpec, boolean linearApiKeyConfigured,
								String ecosystemRoot, boolean prChecksEnabled, int prCheckPollIntervalSeconds,
								String librarySkillsRoot, String libraryAgentsRoot, boolean libraryVectorize,
								boolean librarySyncEnabled, int librarySyncIntervalMinutes, boolean voyageConfigured,
								String defaultProvider, String codexPricing,
								String memoryRoot, boolean memoryEnabled, boolean memoryReflectionDefault,
								String memoryReflectionModel, int memorySyncIntervalMinutes, int memoryRetentionDays,
								boolean memoryReflectionApprovalRequired) {
	}

	public record SettingsUpdate(Boolean linearOAuthEnabled, String ticketImportSpec, String ecosystemRoot,
								  Boolean prChecksEnabled, Integer prCheckPollIntervalSeconds,
								  String librarySkillsRoot, String libraryAgentsRoot, Boolean libraryVectorize,
								  Boolean librarySyncEnabled, Integer librarySyncIntervalMinutes,
								  String defaultProvider, String codexPricing,
								  String memoryRoot, Boolean memoryEnabled, Boolean memoryReflectionDefault,
								  String memoryReflectionModel, Integer memorySyncIntervalMinutes,
								  Integer memoryRetentionDays, Boolean memoryReflectionApprovalRequired) {
	}

	private final SettingsService settings;
	private final AppProperties props;

	public SettingsController(SettingsService settings, AppProperties props) {
		this.settings = settings;
		this.props = props;
	}

	@GetMapping
	public SettingsView get() {
		return view();
	}

	@PatchMapping
	public SettingsView update(@RequestBody SettingsUpdate update) {
		if (update.linearOAuthEnabled() != null) {
			settings.setLinearOAuthEnabled(update.linearOAuthEnabled());
		}
		if (update.ticketImportSpec() != null) {
			settings.setTicketImportSpec(update.ticketImportSpec());
		}
		if (update.ecosystemRoot() != null) {
			settings.setEcosystemRoot(update.ecosystemRoot());
		}
		if (update.prChecksEnabled() != null) {
			settings.setPrChecksEnabled(update.prChecksEnabled());
		}
		if (update.prCheckPollIntervalSeconds() != null) {
			settings.setPrCheckPollIntervalSeconds(update.prCheckPollIntervalSeconds());
		}
		if (update.librarySkillsRoot() != null) {
			settings.setLibrarySkillsRoot(update.librarySkillsRoot());
		}
		if (update.libraryAgentsRoot() != null) {
			settings.setLibraryAgentsRoot(update.libraryAgentsRoot());
		}
		if (update.libraryVectorize() != null) {
			settings.setLibraryVectorize(update.libraryVectorize());
		}
		if (update.librarySyncEnabled() != null) {
			settings.setLibrarySyncEnabled(update.librarySyncEnabled());
		}
		if (update.librarySyncIntervalMinutes() != null) {
			settings.setLibrarySyncIntervalMinutes(update.librarySyncIntervalMinutes());
		}
		if (update.defaultProvider() != null) {
			settings.setDefaultProvider(update.defaultProvider());
		}
		if (update.codexPricing() != null) {
			settings.setCodexPricing(update.codexPricing());
		}
		if (update.memoryRoot() != null) {
			settings.setMemoryRoot(update.memoryRoot());
		}
		if (update.memoryEnabled() != null) {
			settings.setMemoryEnabled(update.memoryEnabled());
		}
		if (update.memoryReflectionDefault() != null) {
			settings.setMemoryReflectionDefault(update.memoryReflectionDefault());
		}
		if (update.memoryReflectionModel() != null) {
			settings.setMemoryReflectionModel(update.memoryReflectionModel());
		}
		if (update.memorySyncIntervalMinutes() != null) {
			settings.setMemorySyncIntervalMinutes(update.memorySyncIntervalMinutes());
		}
		if (update.memoryRetentionDays() != null) {
			settings.setMemoryRetentionDays(update.memoryRetentionDays());
		}
		if (update.memoryReflectionApprovalRequired() != null) {
			settings.setMemoryReflectionApprovalRequired(update.memoryReflectionApprovalRequired());
		}
		return view();
	}

	private SettingsView view() {
		boolean apiKeyConfigured = props.linearApiKey() != null && !props.linearApiKey().isBlank();
		boolean voyageConfigured = props.voyageApiKey() != null && !props.voyageApiKey().isBlank();
		return new SettingsView(settings.linearOAuthEnabled(), settings.ticketImportSpec(), apiKeyConfigured,
				settings.ecosystemRoot(), settings.prChecksEnabled(), settings.prCheckPollIntervalSeconds(),
				settings.librarySkillsRoot(), settings.libraryAgentsRoot(), settings.libraryVectorize(),
				settings.librarySyncEnabled(), settings.librarySyncIntervalMinutes(), voyageConfigured,
				settings.defaultProvider(), settings.codexPricing(),
				settings.memoryRoot(), settings.memoryEnabled(), settings.memoryReflectionDefault(),
				settings.memoryReflectionModel(), settings.memorySyncIntervalMinutes(), settings.memoryRetentionDays(),
				settings.memoryReflectionApprovalRequired());
	}
}
