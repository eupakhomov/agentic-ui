package de.pamir.claude.ui.session;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelCatalogTest {

	@Test
	void claudeCatalogCoversAllThreeTiers() {
		assertThat(ModelCatalog.byTier("claude", "cheap")).contains("haiku");
		assertThat(ModelCatalog.byTier("claude", "standard")).contains("sonnet");
		assertThat(ModelCatalog.byTier("claude", "premium")).contains("opus");
	}

	@Test
	void codexHasNoFixedCatalog() {
		assertThat(ModelCatalog.models("codex")).isEmpty();
		assertThat(ModelCatalog.byTier("codex", "cheap")).isEmpty();
	}

	@Test
	void anUnknownProviderFallsBackToClaudesCatalog() {
		assertThat(ModelCatalog.models("some-future-provider")).isEqualTo(ModelCatalog.models("claude"));
	}

	@Test
	void byTierIsCaseInsensitiveAndTolerantOfWhitespace() {
		assertThat(ModelCatalog.byTier("claude", " Cheap ")).contains("haiku");
	}

	@Test
	void byTierMissesOnAnUnknownTier() {
		assertThat(ModelCatalog.byTier("claude", "ultra")).isEmpty();
	}
}
