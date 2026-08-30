package de.pamir.claude.ui.web;

import de.pamir.claude.ui.config.AppProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Static per-provider capability announcements, used by the create dialog/template
 * editor to gate controls (hide "plan mode", grey out "thinking", …) for a provider
 * that has no session running yet to ask via its live {@code ready} event — once a
 * session exists, the widget uses the authoritative live value from
 * {@code SessionEntity.capabilities} instead (see docs/PROTOCOL.md's capabilities
 * handshake). These constants must stay in sync with each adapter's own
 * {@code ready.capabilities} — see sidecar/src/protocol.ts's CLAUDE_CAPABILITIES and
 * sidecar-codex/src/protocol.ts's CODEX_CAPABILITIES.
 */
@RestController
@RequestMapping("/api/providers")
public class ProviderController {

	public record Capabilities(List<String> permissionModes, boolean thinking, boolean effort, boolean planMode,
								boolean resume, boolean skills, boolean agents, boolean mcp, boolean interrupt,
								boolean fallbackModel, boolean updatedInput, boolean modelSwitch) {
	}

	public record ProviderView(String id, Capabilities capabilities) {
	}

	private static final Capabilities CLAUDE_CAPABILITIES = new Capabilities(
			List.of("default", "acceptEdits", "plan", "bypassPermissions"),
			true, true, true, true, true, true, true, true, true, true, true);

	private static final Capabilities CODEX_CAPABILITIES = new Capabilities(
			List.of("default", "bypassPermissions"),
			false, true, false, true, false, false, false, true, false, false, true);

	private static final java.util.Map<String, Capabilities> KNOWN = java.util.Map.of(
			"claude", CLAUDE_CAPABILITIES,
			"codex", CODEX_CAPABILITIES);

	private final AppProperties props;

	public ProviderController(AppProperties props) {
		this.props = props;
	}

	@GetMapping
	public List<ProviderView> list() {
		return props.providers().keySet().stream()
				.map(id -> new ProviderView(id, KNOWN.getOrDefault(id, CLAUDE_CAPABILITIES)))
				.sorted((a, b) -> a.id().compareTo(b.id()))
				.toList();
	}
}
