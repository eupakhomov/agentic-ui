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
								String ecosystemRoot, boolean prChecksEnabled, int prCheckPollIntervalSeconds) {
	}

	public record SettingsUpdate(Boolean linearOAuthEnabled, String ticketImportSpec, String ecosystemRoot,
								  Boolean prChecksEnabled, Integer prCheckPollIntervalSeconds) {
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
		return view();
	}

	private SettingsView view() {
		boolean apiKeyConfigured = props.linearApiKey() != null && !props.linearApiKey().isBlank();
		return new SettingsView(settings.linearOAuthEnabled(), settings.ticketImportSpec(), apiKeyConfigured,
				settings.ecosystemRoot(), settings.prChecksEnabled(), settings.prCheckPollIntervalSeconds());
	}
}
