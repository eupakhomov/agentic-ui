package de.pamir.claude.ui.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fakes SettingsRepository with an in-memory map (same pattern SessionServiceTest uses for
 * SettingsService itself) — the tier-normalization logic under test never touches AppProperties/
 * ObjectMapper, so both are left null. See docs/plan/phase-9-production-hardening.md P1/P3: these
 * two settings used to store a raw Claude alias, now a provider-neutral tier name.
 */
class SettingsServiceTest {

	private SettingsService newService() {
		Map<String, String> store = new HashMap<>();
		SettingsRepository repo = new SettingsRepository(null) {
			@Override
			public Optional<String> get(String key) {
				return Optional.ofNullable(store.get(key));
			}

			@Override
			public void set(String key, String value) {
				store.put(key, value);
			}
		};
		return new SettingsService(repo, null, null);
	}

	@Test
	void memoryReflectionModelDefaultsToCheap() {
		assertThat(newService().memoryReflectionModel()).isEqualTo("cheap");
	}

	@Test
	void memoryReflectionModelAcceptsATierNameAsIs() {
		SettingsService settings = newService();
		settings.setMemoryReflectionModel("standard");
		assertThat(settings.memoryReflectionModel()).isEqualTo("standard");
	}

	@Test
	void memoryReflectionModelMapsALegacyClaudeAliasToItsTier() {
		SettingsService settings = newService();
		settings.setMemoryReflectionModel("sonnet");
		assertThat(settings.memoryReflectionModel()).isEqualTo("standard");
	}

	@Test
	void memoryReflectionModelFallsBackToCheapForGarbageInput() {
		SettingsService settings = newService();
		settings.setMemoryReflectionModel("gpt-5");
		assertThat(settings.memoryReflectionModel()).isEqualTo("cheap");
	}

	@Test
	void serviceDiscoveryModelMapsALegacyHaikuAliasToCheap() {
		SettingsService settings = newService();
		settings.setServiceDiscoveryModel("haiku");
		assertThat(settings.serviceDiscoveryModel()).isEqualTo("cheap");
	}

	@Test
	void systemProviderDefaultsToFollowingDefaultProvider() {
		SettingsService settings = newService();
		settings.setDefaultProvider("codex");
		assertThat(settings.systemProvider()).isEqualTo("codex");
	}

	@Test
	void systemProviderOverridesDefaultProviderWhenExplicitlySet() {
		SettingsService settings = newService();
		settings.setDefaultProvider("codex");
		settings.setSystemProvider("claude");
		assertThat(settings.systemProvider()).isEqualTo("claude");
	}

	@Test
	void systemProviderOverrideStaysBlankUntilExplicitlySet() {
		SettingsService settings = newService();
		settings.setDefaultProvider("codex");
		assertThat(settings.systemProviderOverride()).isEmpty();
		settings.setSystemProvider("claude");
		assertThat(settings.systemProviderOverride()).isEqualTo("claude");
	}
}
