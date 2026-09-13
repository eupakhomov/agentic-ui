package de.pamir.claude.ui.web;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import de.pamir.claude.ui.config.AppProperties;
import de.pamir.claude.ui.config.Settings;
import de.pamir.claude.ui.config.SettingsPatch;
import de.pamir.claude.ui.config.SettingsService;
import de.pamir.claude.ui.integration.SerenaService;
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
	private final SerenaService serena;

	public SettingsController(SettingsService settings, AppProperties props, SerenaService serena) {
		this.settings = settings;
		this.props = props;
		this.serena = serena;
	}

	@GetMapping
	public SettingsView get() {
		return view();
	}

	@PatchMapping
	public SettingsView update(@RequestBody SettingsPatch patch) {
		// Validated against the patch's own (not-yet-persisted) values, not settings.current(), so a
		// bad root/uv path never lands in the DB — see SerenaService.validate's own contract (a blank
		// root always passes; clearing/disabling Serena must never be blocked).
		if (patch.mcpSerenaRoot() != null) {
			String uvPath = patch.mcpUvPath() != null ? patch.mcpUvPath() : settings.current().mcpUvPath();
			serena.validate(patch.mcpSerenaRoot(), uvPath);
		}
		settings.apply(patch);
		return view();
	}

	private SettingsView view() {
		boolean apiKeyConfigured = props.linearApiKey() != null && !props.linearApiKey().isBlank();
		boolean voyageConfigured = props.voyageApiKey() != null && !props.voyageApiKey().isBlank();
		return new SettingsView(settings.current(), apiKeyConfigured, voyageConfigured, settings.pricingFor("codex"));
	}
}
