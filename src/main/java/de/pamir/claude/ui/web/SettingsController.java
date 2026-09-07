package de.pamir.claude.ui.web;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import de.pamir.claude.ui.config.AppProperties;
import de.pamir.claude.ui.config.Settings;
import de.pamir.claude.ui.config.SettingsPatch;
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

	public record SettingsView(@JsonUnwrapped Settings settings, boolean linearApiKeyConfigured,
								boolean voyageConfigured, String codexPricing) {
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
	public SettingsView update(@RequestBody SettingsPatch patch) {
		settings.apply(patch);
		return view();
	}

	private SettingsView view() {
		boolean apiKeyConfigured = props.linearApiKey() != null && !props.linearApiKey().isBlank();
		boolean voyageConfigured = props.voyageApiKey() != null && !props.voyageApiKey().isBlank();
		return new SettingsView(settings.current(), apiKeyConfigured, voyageConfigured, settings.pricingFor("codex"));
	}
}
