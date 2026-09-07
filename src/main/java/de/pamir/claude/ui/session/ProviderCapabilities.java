package de.pamir.claude.ui.session;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * A provider adapter's declared capabilities, loaded by {@link ProviderCatalog} from its
 * {@code capabilities.json} (mirrors {@code Capabilities} in both {@code protocol.ts} copies —
 * see docs/PROTOCOL.md's "Capabilities" section). {@code unsupportedSessionFields}/
 * {@code contextDirs}/{@code reportsCostUsd} are what let {@link SessionConfigFactory},
 * {@link de.pamir.claude.ui.process.SidecarManager}, and {@link SessionService} branch on
 * capability instead of provider name (docs/plan/phase-10-review-followups.md R1).
 * {@code models} is intentionally not a field here — that stays sourced from
 * {@link ModelCatalog}, not duplicated — so an unknown JSON key is ignored, not rejected.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProviderCapabilities(List<String> permissionModes, boolean thinking, boolean effort,
									boolean planMode, boolean resume, boolean skills, boolean agents,
									boolean mcp, boolean interrupt, boolean fallbackModel,
									boolean updatedInput, boolean modelSwitch,
									List<String> unsupportedSessionFields, boolean contextDirs,
									boolean reportsCostUsd) {

	/** True unless this session/create-option field is named in {@link #unsupportedSessionFields}. */
	public boolean supports(String field) {
		return !unsupportedSessionFields.contains(field);
	}
}
