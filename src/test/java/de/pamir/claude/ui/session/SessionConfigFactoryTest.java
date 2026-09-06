package de.pamir.claude.ui.session;

import de.pamir.claude.ui.config.AppProperties;
import de.pamir.claude.ui.config.SettingsService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises SessionConfigFactory's config-merge helpers directly. These only touch the
 * {@code props}/{@code settings}/{@code mapper} fields (never templates/episodes), so a plain
 * constructor call with nulls for the rest — and a hand-written SettingsService subclass instead
 * of a mocking framework — is a real unit, not an integration test. See
 * docs/plan/phase-9-production-hardening.md T1/S1.
 */
class SessionConfigFactoryTest {

	private final ObjectMapper mapper = new JsonMapper();

	private static SettingsService fakeSettings(boolean linearOAuth, boolean memoryEnabled,
												 boolean serviceDiscoveryEnabled) {
		return new SettingsService(null, null, null) {
			@Override
			public boolean linearOAuthEnabled() {
				return linearOAuth;
			}

			@Override
			public boolean memoryEnabled() {
				return memoryEnabled;
			}

			@Override
			public boolean serviceDiscoveryEnabled() {
				return serviceDiscoveryEnabled;
			}
		};
	}

	private SessionConfigFactory factoryWith(AppProperties props, SettingsService settings) {
		return new SessionConfigFactory(props, settings, null, mapper, null, 8080);
	}

	private static AppProperties propsWithLinearKey(String linearApiKey, String authToken) {
		return new AppProperties("/repo", "/worktrees", "/skills", "/memory", 4, authToken,
				linearApiKey, "", "logs", 30, 65536, 1048576, Map.of());
	}

	// --- withDefaultLinearMcp ---

	@Test
	void withDefaultLinearMcpAddsALinearEntryWhenAnApiKeyIsConfigured() {
		SessionConfigFactory factory = factoryWith(propsWithLinearKey("lin_api_key", "authtoken"), fakeSettings(false, true, false));

		JsonNode result = factory.withDefaultLinearMcp(null);

		assertThat(result.path("linear").path("type").asText()).isEqualTo("http");
		assertThat(result.path("linear").path("headers").path("Authorization").asText()).isEqualTo("Bearer lin_api_key");
	}

	@Test
	void withDefaultLinearMcpLeavesAnExistingLinearEntryUntouched() {
		SessionConfigFactory factory = factoryWith(propsWithLinearKey("lin_api_key", "authtoken"), fakeSettings(false, true, false));
		ObjectNode existing = mapper.createObjectNode();
		existing.putObject("linear").put("type", "stdio").put("command", "custom");

		JsonNode result = factory.withDefaultLinearMcp(existing);

		assertThat(result.path("linear").path("type").asText()).isEqualTo("stdio");
	}

	@Test
	void withDefaultLinearMcpMergesAlongsideOtherConfiguredServers() {
		SessionConfigFactory factory = factoryWith(propsWithLinearKey("lin_api_key", "authtoken"), fakeSettings(false, true, false));
		ObjectNode existing = mapper.createObjectNode();
		existing.putObject("github").put("type", "http");

		JsonNode result = factory.withDefaultLinearMcp(existing);

		assertThat(result.has("github")).isTrue();
		assertThat(result.has("linear")).isTrue();
	}

	@Test
	void withDefaultLinearMcpIsANoOpWhenNeitherApiKeyNorOAuthIsConfigured() {
		SessionConfigFactory factory = factoryWith(propsWithLinearKey("", "authtoken"), fakeSettings(false, true, false));

		assertThat(factory.withDefaultLinearMcp(null)).isNull();
	}

	// --- withDefaultMemoryMcp ---

	@Test
	void withDefaultMemoryMcpAddsAMemoryEntryPointingAtThisBackend() {
		SessionConfigFactory factory = factoryWith(propsWithLinearKey("", "authtoken"), fakeSettings(false, true, false));

		JsonNode result = factory.withDefaultMemoryMcp(null);

		assertThat(result.path("memory").path("url").asText()).isEqualTo("http://127.0.0.1:8080/api/mcp/memory");
		assertThat(result.path("memory").path("headers").path("Authorization").asText()).isEqualTo("Bearer authtoken");
	}

	@Test
	void withDefaultMemoryMcpIsANoOpWhenBothMemoryAndDiscoveryAreDisabled() {
		SessionConfigFactory factory = factoryWith(propsWithLinearKey("", "authtoken"), fakeSettings(false, false, false));

		assertThat(factory.withDefaultMemoryMcp(null)).isNull();
	}

	@Test
	void withDefaultMemoryMcpAttachesWhenOnlyServiceDiscoveryIsEnabled() {
		SessionConfigFactory factory = factoryWith(propsWithLinearKey("", "authtoken"), fakeSettings(false, false, true));

		assertThat(factory.withDefaultMemoryMcp(null)).isNotNull();
	}

	// --- combineTemplateSources ---

	@Test
	void combineTemplateSourcesAppendsActiveAssetsOfTheRequestedKindAndWarnsOnArchived() {
		SessionConfigFactory factory = factoryWith(propsWithLinearKey("", "authtoken"), fakeSettings(false, true, false));
		List<TemplateRepository.TemplateAsset> assets = List.of(
				new TemplateRepository.TemplateAsset(UUID.randomUUID(), "skill", "Skill A", "/path/a", "ACTIVE"),
				new TemplateRepository.TemplateAsset(UUID.randomUUID(), "skill", "Skill B", "/path/b", "ARCHIVED"),
				new TemplateRepository.TemplateAsset(UUID.randomUUID(), "agent", "Agent C", "/path/c", "ACTIVE"));
		List<String> warnings = new ArrayList<>();

		ArrayNode combined = factory.combineTemplateSources(null, assets, "skill", "dir", warnings);

		assertThat(combined).hasSize(1);
		assertThat(combined.get(0).path("type").asText()).isEqualTo("dir");
		assertThat(combined.get(0).path("ref").asText()).isEqualTo("/path/a");
		assertThat(warnings).hasSize(1);
		assertThat(warnings.get(0)).contains("Skill B").contains("archived");
	}

	@Test
	void combineTemplateSourcesKeepsExistingFreeformEntries() {
		SessionConfigFactory factory = factoryWith(propsWithLinearKey("", "authtoken"), fakeSettings(false, true, false));
		ArrayNode existing = mapper.createArrayNode();
		existing.add(mapper.createObjectNode().put("type", "dir").put("ref", "/free/form"));

		ArrayNode combined = factory.combineTemplateSources(existing, List.of(), "skill", "dir", new ArrayList<>());

		assertThat(combined).hasSize(1);
		assertThat(combined.get(0).path("ref").asText()).isEqualTo("/free/form");
	}

	// --- fillPlaceholders ---

	@Test
	void fillPlaceholdersReplacesKnownTokens() {
		String result = SessionConfigFactory.fillPlaceholders("Hello {{name}}, ticket {{ticket}}",
				Map.of("name", "World", "ticket", "ENG-1"));

		assertThat(result).isEqualTo("Hello World, ticket ENG-1");
	}

	@Test
	void fillPlaceholdersIsANoOpWithoutAPromptOrValues() {
		assertThat(SessionConfigFactory.fillPlaceholders(null, Map.of("a", "b"))).isNull();
		assertThat(SessionConfigFactory.fillPlaceholders("unchanged {{x}}", null)).isEqualTo("unchanged {{x}}");
	}
}
