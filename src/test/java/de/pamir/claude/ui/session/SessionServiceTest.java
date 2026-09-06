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
 * Exercises SessionService's config-merge helpers directly. These only touch the
 * {@code props}/{@code settings}/{@code mapper} fields (never sessions/templates/git/
 * sidecars/journal/etc.), so a plain constructor call with nulls for the rest — and a
 * hand-written SettingsService subclass instead of a mocking framework — is a real unit,
 * not an integration test. See docs/plan/phase-9-production-hardening.md T1.
 */
class SessionServiceTest {

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

	private SessionService serviceWith(AppProperties props, SettingsService settings) {
		return new SessionService(props, settings, null, null, null, null, null, null, null, null,
				mapper, null, null, 8080);
	}

	private static AppProperties propsWithLinearKey(String linearApiKey, String authToken) {
		return new AppProperties("/repo", "/worktrees", "/skills", "/memory", 4, authToken,
				linearApiKey, "", "logs", 30, 65536, 1048576, Map.of());
	}

	// --- withDefaultLinearMcp ---

	@Test
	void withDefaultLinearMcpAddsALinearEntryWhenAnApiKeyIsConfigured() {
		SessionService svc = serviceWith(propsWithLinearKey("lin_api_key", "authtoken"), fakeSettings(false, true, false));

		JsonNode result = svc.withDefaultLinearMcp(null);

		assertThat(result.path("linear").path("type").asText()).isEqualTo("http");
		assertThat(result.path("linear").path("headers").path("Authorization").asText()).isEqualTo("Bearer lin_api_key");
	}

	@Test
	void withDefaultLinearMcpLeavesAnExistingLinearEntryUntouched() {
		SessionService svc = serviceWith(propsWithLinearKey("lin_api_key", "authtoken"), fakeSettings(false, true, false));
		ObjectNode existing = mapper.createObjectNode();
		existing.putObject("linear").put("type", "stdio").put("command", "custom");

		JsonNode result = svc.withDefaultLinearMcp(existing);

		assertThat(result.path("linear").path("type").asText()).isEqualTo("stdio");
	}

	@Test
	void withDefaultLinearMcpMergesAlongsideOtherConfiguredServers() {
		SessionService svc = serviceWith(propsWithLinearKey("lin_api_key", "authtoken"), fakeSettings(false, true, false));
		ObjectNode existing = mapper.createObjectNode();
		existing.putObject("github").put("type", "http");

		JsonNode result = svc.withDefaultLinearMcp(existing);

		assertThat(result.has("github")).isTrue();
		assertThat(result.has("linear")).isTrue();
	}

	@Test
	void withDefaultLinearMcpIsANoOpWhenNeitherApiKeyNorOAuthIsConfigured() {
		SessionService svc = serviceWith(propsWithLinearKey("", "authtoken"), fakeSettings(false, true, false));

		assertThat(svc.withDefaultLinearMcp(null)).isNull();
	}

	// --- withDefaultMemoryMcp ---

	@Test
	void withDefaultMemoryMcpAddsAMemoryEntryPointingAtThisBackend() {
		SessionService svc = serviceWith(propsWithLinearKey("", "authtoken"), fakeSettings(false, true, false));

		JsonNode result = svc.withDefaultMemoryMcp(null);

		assertThat(result.path("memory").path("url").asText()).isEqualTo("http://127.0.0.1:8080/api/mcp/memory");
		assertThat(result.path("memory").path("headers").path("Authorization").asText()).isEqualTo("Bearer authtoken");
	}

	@Test
	void withDefaultMemoryMcpIsANoOpWhenBothMemoryAndDiscoveryAreDisabled() {
		SessionService svc = serviceWith(propsWithLinearKey("", "authtoken"), fakeSettings(false, false, false));

		assertThat(svc.withDefaultMemoryMcp(null)).isNull();
	}

	@Test
	void withDefaultMemoryMcpAttachesWhenOnlyServiceDiscoveryIsEnabled() {
		SessionService svc = serviceWith(propsWithLinearKey("", "authtoken"), fakeSettings(false, false, true));

		assertThat(svc.withDefaultMemoryMcp(null)).isNotNull();
	}

	// --- combineTemplateSources ---

	@Test
	void combineTemplateSourcesAppendsActiveAssetsOfTheRequestedKindAndWarnsOnArchived() {
		SessionService svc = serviceWith(propsWithLinearKey("", "authtoken"), fakeSettings(false, true, false));
		List<TemplateRepository.TemplateAsset> assets = List.of(
				new TemplateRepository.TemplateAsset(UUID.randomUUID(), "skill", "Skill A", "/path/a", "ACTIVE"),
				new TemplateRepository.TemplateAsset(UUID.randomUUID(), "skill", "Skill B", "/path/b", "ARCHIVED"),
				new TemplateRepository.TemplateAsset(UUID.randomUUID(), "agent", "Agent C", "/path/c", "ACTIVE"));
		List<String> warnings = new ArrayList<>();

		ArrayNode combined = svc.combineTemplateSources(null, assets, "skill", "dir", warnings);

		assertThat(combined).hasSize(1);
		assertThat(combined.get(0).path("type").asText()).isEqualTo("dir");
		assertThat(combined.get(0).path("ref").asText()).isEqualTo("/path/a");
		assertThat(warnings).hasSize(1);
		assertThat(warnings.get(0)).contains("Skill B").contains("archived");
	}

	@Test
	void combineTemplateSourcesKeepsExistingFreeformEntries() {
		SessionService svc = serviceWith(propsWithLinearKey("", "authtoken"), fakeSettings(false, true, false));
		ArrayNode existing = mapper.createArrayNode();
		existing.add(mapper.createObjectNode().put("type", "dir").put("ref", "/free/form"));

		ArrayNode combined = svc.combineTemplateSources(existing, List.of(), "skill", "dir", new ArrayList<>());

		assertThat(combined).hasSize(1);
		assertThat(combined.get(0).path("ref").asText()).isEqualTo("/free/form");
	}

	// --- fillPlaceholders / extractText ---

	@Test
	void fillPlaceholdersReplacesKnownTokens() {
		String result = SessionService.fillPlaceholders("Hello {{name}}, ticket {{ticket}}",
				Map.of("name", "World", "ticket", "ENG-1"));

		assertThat(result).isEqualTo("Hello World, ticket ENG-1");
	}

	@Test
	void fillPlaceholdersIsANoOpWithoutAPromptOrValues() {
		assertThat(SessionService.fillPlaceholders(null, Map.of("a", "b"))).isNull();
		assertThat(SessionService.fillPlaceholders("unchanged {{x}}", null)).isEqualTo("unchanged {{x}}");
	}

	@Test
	void extractTextJoinsOnlyTextBlocksInOrder() {
		ArrayNode content = mapper.createArrayNode();
		content.add(mapper.createObjectNode().put("type", "text").put("text", "Hello "));
		content.add(mapper.createObjectNode().put("type", "tool_use").put("name", "Bash"));
		content.add(mapper.createObjectNode().put("type", "text").put("text", "World"));

		assertThat(SessionService.extractText(content)).isEqualTo("Hello World");
	}

	@Test
	void extractTextIsEmptyForNullOrNonArrayContent() {
		assertThat(SessionService.extractText(null)).isEmpty();
		assertThat(SessionService.extractText(mapper.createObjectNode())).isEmpty();
	}
}
