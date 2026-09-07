package de.pamir.claude.ui.session;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the test-only fixed-map constructor (see ProviderCatalog's package-private
 * constructor) — the real file-backed constructor is covered end to end by every
 * SessionConfigFactory/SidecarManager test that runs against the checked-in sidecar/
 * sidecar-codex capabilities.json files via a real Spring context (ApplicationTests).
 */
class ProviderCatalogTest {

	private static ProviderCapabilities caps(List<String> unsupported) {
		return new ProviderCapabilities(List.of("default"), true, true, true, true, true, true, true, true,
				true, true, true, unsupported, true, true);
	}

	@Test
	void getRoundTripsAFabricatedProvidersCapabilities() {
		ProviderCatalog catalog = ProviderCatalog.fixedForTest(Map.of("widget", caps(List.of("maxTurns"))));

		assertThat(catalog.get("widget").supports("maxTurns")).isFalse();
		assertThat(catalog.get("widget").supports("thinking")).isTrue();
	}

	@Test
	void getThrowsForAnUnknownProvider() {
		ProviderCatalog catalog = ProviderCatalog.fixedForTest(Map.of("widget", caps(List.of())));

		assertThatThrownBy(() -> catalog.get("gadget")).isInstanceOf(IllegalStateException.class);
	}
}
