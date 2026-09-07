package de.pamir.claude.ui.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fakes SettingsRepository with an in-memory map (same pattern SessionServiceTest uses for
 * SettingsService itself) — the logic under test never touches AppProperties/ObjectMapper, so both
 * are left null. See docs/plan/phase-10-review-followups.md R8c for the typed Settings/SettingsPatch
 * shape these tests exercise, and phase-9-production-hardening.md P1/P3 for why
 * memoryReflectionModel/serviceDiscoveryModel normalize a legacy raw Claude alias to a tier.
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

			@Override
			public Map<String, String> all() {
				return new HashMap<>(store);
			}
		};
		return new SettingsService(repo, null, null);
	}

	@Test
	void memoryReflectionModelDefaultsToCheap() {
		assertThat(newService().current().memoryReflectionModel()).isEqualTo("cheap");
	}

	@Test
	void memoryReflectionModelAcceptsATierNameAsIs() {
		SettingsService settings = newService();
		settings.apply(SettingsPatch.builder().memoryReflectionModel("standard").build());
		assertThat(settings.current().memoryReflectionModel()).isEqualTo("standard");
	}

	@Test
	void memoryReflectionModelMapsALegacyClaudeAliasToItsTier() {
		SettingsService settings = newService();
		settings.apply(SettingsPatch.builder().memoryReflectionModel("sonnet").build());
		assertThat(settings.current().memoryReflectionModel()).isEqualTo("standard");
	}

	@Test
	void memoryReflectionModelFallsBackToCheapForGarbageInput() {
		SettingsService settings = newService();
		settings.apply(SettingsPatch.builder().memoryReflectionModel("gpt-5").build());
		assertThat(settings.current().memoryReflectionModel()).isEqualTo("cheap");
	}

	@Test
	void serviceDiscoveryModelMapsALegacyHaikuAliasToCheap() {
		SettingsService settings = newService();
		settings.apply(SettingsPatch.builder().serviceDiscoveryModel("haiku").build());
		assertThat(settings.current().serviceDiscoveryModel()).isEqualTo("cheap");
	}

	@Test
	void systemProviderDefaultsToFollowingDefaultProvider() {
		SettingsService settings = newService();
		settings.apply(SettingsPatch.builder().defaultProvider("codex").build());
		assertThat(settings.systemProvider()).isEqualTo("codex");
	}

	@Test
	void systemProviderOverridesDefaultProviderWhenExplicitlySet() {
		SettingsService settings = newService();
		settings.apply(SettingsPatch.builder().defaultProvider("codex").build());
		settings.apply(SettingsPatch.builder().systemProvider("claude").build());
		assertThat(settings.systemProvider()).isEqualTo("claude");
	}

	@Test
	void systemProviderOverrideStaysBlankUntilExplicitlySet() {
		SettingsService settings = newService();
		settings.apply(SettingsPatch.builder().defaultProvider("codex").build());
		assertThat(settings.current().systemProvider()).isEmpty();
		settings.apply(SettingsPatch.builder().systemProvider("claude").build());
		assertThat(settings.current().systemProvider()).isEqualTo("claude");
	}

	@Test
	void applyIsANoOpForNullPatchFields() {
		SettingsService settings = newService();
		settings.apply(SettingsPatch.builder().memoryEnabled(false).build());
		Settings before = settings.current();
		settings.apply(SettingsPatch.builder().build());
		assertThat(settings.current()).isEqualTo(before);
	}

	@Test
	void currentCachesUntilApply() {
		SettingsService settings = newService();
		Settings first = settings.current();
		Settings second = settings.current();
		assertThat(second).isSameAs(first);
		settings.apply(SettingsPatch.builder().memoryEnabled(false).build());
		Settings third = settings.current();
		assertThat(third).isNotSameAs(first);
	}
}
