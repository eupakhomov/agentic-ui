package de.pamir.claude.ui.session;

import de.pamir.claude.ui.config.AppProperties;
import de.pamir.claude.ui.config.Settings;
import de.pamir.claude.ui.config.SettingsService;
import de.pamir.claude.ui.git.GitWorktreeService;
import de.pamir.claude.ui.integration.GraphifyService;
import de.pamir.claude.ui.integration.SerenaService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
		// prepare() evaluates several of these eagerly as fallback-argument expressions even when
		// the config already supplies its own value — never actually used in that case, but Java
		// evaluates method arguments before the callee can short-circuit, so current() must not
		// touch the (null in these tests) SettingsRepository regardless — overriding current() to
		// return a fixed snapshot sidesteps that entirely.
		Settings fixed = new Settings(linearOAuth, "", "", "", true, true, 180, "", "", false, true, 60, "claude", "", "",
				memoryEnabled, false, "cheap", 5, 0, true, serviceDiscoveryEnabled, 14, "cheap", 70, "", "uv", "", "none");
		return new SettingsService(null, null, null) {
			@Override
			public Settings current() {
				return fixed;
			}
		};
	}

	/** Same fixed-snapshot fake, with the phase-13 code-intelligence selector set (docs/plan/phase-13-graphify.md decision 1). */
	private static SettingsService fakeSettingsWithCodeIntel(String codeIntel) {
		Settings fixed = new Settings(false, "", "", "", true, true, 180, "", "", false, true, 60, "claude", "", "",
				false, false, "cheap", 5, 0, true, false, 14, "cheap", 70, "", "uv", "", codeIntel);
		return new SettingsService(null, null, null) {
			@Override
			public Settings current() {
				return fixed;
			}
		};
	}

	private SessionConfigFactory factoryWith(AppProperties props, SettingsService settings) {
		return new SessionConfigFactory(props, settings, null, mapper, null, 8080, null, null, null, null);
	}

	/** A {@link GitWorktreeService} whose git-touching methods are stubbed — see docs/plan/phase-11-monorepo.md Step 3. */
	private static GitWorktreeService fakeWorktrees(Map<String, Path> repoRootByServicePath,
													  List<GitWorktreeService.ServiceInfo> knownServices) {
		return new GitWorktreeService(null) {
			@Override
			public Optional<Path> repoRootOf(Path servicePath) {
				return Optional.ofNullable(repoRootByServicePath.get(servicePath.toString()));
			}

			@Override
			public List<GitWorktreeService.ServiceInfo> findServices(Path ecosystemRoot, List<String> fallbackGlobs,
																	   boolean monorepoDetectionEnabled) {
				return knownServices;
			}
		};
	}

	/** A fixed, non-file-backed ProviderCatalog — see {@link ProviderCatalog#fixedForTest}. */
	private static ProviderCatalog fakeCatalog(Map<String, ProviderCapabilities> byProvider) {
		return ProviderCatalog.fixedForTest(byProvider);
	}

	private static ProviderCapabilities fullCapabilities() {
		return new ProviderCapabilities(List.of("default", "acceptEdits", "plan", "bypassPermissions"),
				true, true, true, true, true, true, true, true, true, true, true, List.of(), true, true, true,
				"claude-code");
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

	// --- prepare() rejects unsupported fields by capability, not by provider name ---
	// See docs/plan/phase-10-review-followups.md R1's DoD: a fabricated provider id (not
	// claude/codex) declaring a field unsupported must still be rejected — proving the
	// rejection path only ever consults ProviderCatalog, never a hardcoded provider string.

	@Test
	void prepareRejectsAFieldTheFabricatedProviderDeclaresUnsupported() {
		AppProperties props = propsWithLinearKey("", "authtoken");
		SessionConfigFactory factory = new SessionConfigFactory(props, fakeSettings(false, false, false),
				null, mapper, null, 8080, fakeCatalog(Map.of("widget", new ProviderCapabilities(
						List.of("default"), true, true, true, true, true, true, true, true, true, true, true,
						List.of("maxTurns"), true, true, true, "claude-code"))), null, null, null);
		ObjectNode overrides = mapper.createObjectNode().put("provider", "widget").put("maxTurns", 5);
		SessionService.CreateOptions options = new SessionService.CreateOptions(
				"s", "branch", "main", System.getProperty("user.dir"), null, overrides, Map.of(), false);

		assertThat(org.assertj.core.api.Assertions.catchThrowable(
						() -> factory.prepare(UUID.randomUUID(), java.nio.file.Path.of("/worktree"), options)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("widget").hasMessageContaining("maxTurns");
	}

	@Test
	void prepareAcceptsTheSameFieldForAProviderThatSupportsIt() {
		AppProperties props = propsWithLinearKey("", "authtoken");
		SessionConfigFactory factory = new SessionConfigFactory(props, fakeSettings(false, false, false),
				null, mapper, null, 8080, fakeCatalog(Map.of("widget", fullCapabilities())), null, null, null);
		ObjectNode overrides = mapper.createObjectNode().put("provider", "widget").put("maxTurns", 5);
		SessionService.CreateOptions options = new SessionService.CreateOptions(
				"s", "branch", "main", System.getProperty("user.dir"), null, overrides, Map.of(), false);

		SessionConfigFactory.Prepared prepared = factory.prepare(UUID.randomUUID(), java.nio.file.Path.of("/worktree"), options);

		assertThat(prepared.entity().maxTurns()).isEqualTo(5);
	}

	// --- prepare() servicePath resolution (docs/plan/phase-11-monorepo.md Step 3) ---

	private static SettingsService fakeSettingsWithEcosystem(String ecosystemRoot) {
		Settings fixed = new Settings(false, "", ecosystemRoot, "packages/*,services/*,apps/*,libs/*", true, true, 180,
				"", "", false, true, 60, "claude", "", "", false, false, "cheap", 5, 0, true, false, 14, "cheap", 70, "", "uv", "", "none");
		return new SettingsService(null, null, null) {
			@Override
			public Settings current() {
				return fixed;
			}
		};
	}

	@Test
	void prepareResolvesRepoPathFromServicePathAloneViaRepoRootOf() {
		String servicePath = "/repo/packages/foo";
		GitWorktreeService worktrees = fakeWorktrees(Map.of(servicePath, Path.of("/repo")),
				List.of(new GitWorktreeService.ServiceInfo("packages/foo", servicePath, "/repo")));
		SessionConfigFactory factory = new SessionConfigFactory(propsWithLinearKey("", "authtoken"),
				fakeSettingsWithEcosystem("/eco"), null, mapper, null, 8080,
				fakeCatalog(Map.of("claude", fullCapabilities())), worktrees, null, null);
		SessionService.CreateOptions options = new SessionService.CreateOptions(
				"s", "branch", "main", null, null, mapper.createObjectNode(), Map.of(), false)
				.withServicePath(servicePath);

		SessionConfigFactory.Prepared prepared = factory.prepare(UUID.randomUUID(), Path.of("/worktree"), options);

		assertThat(prepared.entity().repoPath()).isEqualTo("/repo");
		assertThat(prepared.entity().servicePath()).isEqualTo(servicePath);
	}

	@Test
	void prepareRejectsAServicePathNotInsideAnyGitRepo() {
		GitWorktreeService worktrees = fakeWorktrees(Map.of(), List.of()); // repoRootOf always empty
		SessionConfigFactory factory = new SessionConfigFactory(propsWithLinearKey("", "authtoken"),
				fakeSettings(false, false, false), null, mapper, null, 8080,
				fakeCatalog(Map.of("claude", fullCapabilities())), worktrees, null, null);
		SessionService.CreateOptions options = new SessionService.CreateOptions(
				"s", "branch", "main", null, null, mapper.createObjectNode(), Map.of(), false)
				.withServicePath("/not-a-repo/foo");

		assertThat(org.assertj.core.api.Assertions.catchThrowable(
						() -> factory.prepare(UUID.randomUUID(), Path.of("/worktree"), options)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("not inside a git repository");
	}

	@Test
	void prepareRejectsAServicePathThatIsNotAKnownService() {
		String servicePath = "/repo/packages/unknown";
		GitWorktreeService worktrees = fakeWorktrees(Map.of(servicePath, Path.of("/repo")), List.of());
		// ecosystemRoot blank (fakeSettings default) -> isKnownService short-circuits to false for
		// anything that isn't the repo root itself, without even calling the (empty) findServices fake.
		SessionConfigFactory factory = new SessionConfigFactory(propsWithLinearKey("", "authtoken"),
				fakeSettings(false, false, false), null, mapper, null, 8080,
				fakeCatalog(Map.of("claude", fullCapabilities())), worktrees, null, null);
		SessionService.CreateOptions options = new SessionService.CreateOptions(
				"s", "branch", "main", null, null, mapper.createObjectNode(), Map.of(), false)
				.withServicePath(servicePath);

		assertThat(org.assertj.core.api.Assertions.catchThrowable(
						() -> factory.prepare(UUID.randomUUID(), Path.of("/worktree"), options)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("not a known service");
	}

	@Test
	void prepareWithRepoPathAloneLeavesServicePathNullAndOverridesOmitTheKey() {
		String repo = System.getProperty("user.dir");
		SessionConfigFactory factory = new SessionConfigFactory(propsWithLinearKey("", "authtoken"),
				fakeSettings(false, false, false), null, mapper, null, 8080,
				fakeCatalog(Map.of("claude", fullCapabilities())), null, null, null);
		SessionService.CreateOptions options = new SessionService.CreateOptions(
				"s", "branch", "main", repo, null, mapper.createObjectNode(), Map.of(), false);

		SessionConfigFactory.Prepared prepared = factory.prepare(UUID.randomUUID(), Path.of("/worktree"), options);

		assertThat(prepared.entity().repoPath()).isEqualTo(repo);
		// resolved accessor falls back to repoPath — byte-identical polyrepo behavior
		assertThat(prepared.entity().servicePath()).isEqualTo(repo);
		assertThat(factory.configOverridesFrom(prepared.entity()).has("servicePath")).isFalse();
	}

	// --- Serena (docs/plan/phase-12-linear-cache-serena-context.md Track B) ---

	/** Sidecar-codex's actual capabilities.json shape — serenaContext "codex", no prompt override. */
	private static ProviderCapabilities codexLikeCapabilities() {
		return new ProviderCapabilities(List.of("default", "bypassPermissions"),
				false, true, false, true, true, false, true, true, false, false, true,
				List.of("allowedTools", "disallowedTools", "thinking", "maxTurns", "fallbackModel"),
				false, false, true, "codex");
	}

	/** Hand-written fake (this file's convention — no mocking framework): overrides the public
	 * accessors {@link SessionConfigFactory} actually calls, sidesteps real process execution. */
	private static SerenaService fakeSerena(boolean configured, String root, String uvCommand, String promptOverride) {
		return new SerenaService(null) {
			@Override
			public boolean configured() {
				return configured;
			}

			@Override
			public String root() {
				return root;
			}

			@Override
			public String uvCommand() {
				return uvCommand;
			}

			@Override
			public String ccSystemPromptOverride() {
				return promptOverride;
			}
		};
	}

	private static List<String> serenaArgs(SessionEntity entity) {
		List<String> args = new ArrayList<>();
		entity.mcpConfig().path("serena").path("args").forEach(n -> args.add(n.asText()));
		return args;
	}

	@Test
	void prepareLayersASerenaMcpEntryWhenEnabledAndConfigured() {
		SerenaService serena = fakeSerena(true, "/mnt/d/projects/serena", "uv", null);
		SessionConfigFactory factory = new SessionConfigFactory(propsWithLinearKey("", "authtoken"),
				fakeSettingsWithCodeIntel("serena"), null, mapper, null, 8080,
				fakeCatalog(Map.of("claude", fullCapabilities())), null, serena, null);
		ObjectNode overrides = mapper.createObjectNode().put("codeIntelEnabled", true);
		SessionService.CreateOptions options = new SessionService.CreateOptions(
				"s", "branch", "main", System.getProperty("user.dir"), null, overrides, Map.of(), false);

		SessionConfigFactory.Prepared prepared = factory.prepare(UUID.randomUUID(), Path.of("/worktree"), options);

		assertThat(prepared.entity().codeIntel()).isEqualTo("serena");
		assertThat(prepared.entity().mcpConfig().path("serena").path("command").asText()).isEqualTo("uv");
		assertThat(serenaArgs(prepared.entity())).containsExactly("run", "--directory", "/mnt/d/projects/serena",
				"serena", "start-mcp-server", "--context", "claude-code", "--project", "/worktree",
				"--open-web-dashboard", "false");
	}

	@Test
	void prepareLeavesMcpConfigUnchangedWhenSerenaIsNotEnabled() {
		SerenaService serena = fakeSerena(true, "/mnt/d/projects/serena", "uv", null);
		SessionConfigFactory factory = new SessionConfigFactory(propsWithLinearKey("", "authtoken"),
				fakeSettingsWithCodeIntel("serena"), null, mapper, null, 8080,
				fakeCatalog(Map.of("claude", fullCapabilities())), null, serena, null);
		SessionService.CreateOptions options = new SessionService.CreateOptions(
				"s", "branch", "main", System.getProperty("user.dir"), null, mapper.createObjectNode(), Map.of(), false);

		SessionConfigFactory.Prepared prepared = factory.prepare(UUID.randomUUID(), Path.of("/worktree"), options);

		assertThat(prepared.entity().codeIntel()).isNull();
		assertThat(prepared.entity().mcpConfig()).isNull();
	}

	@Test
	void prepareKeepsAnExplicitSerenaMcpEntryUntouched() {
		// deliberately the phase-12 `serenaEnabled` key: templates already in the DB carry it (decision 5)
		SerenaService serena = fakeSerena(true, "/mnt/d/projects/serena", "uv", null);
		SessionConfigFactory factory = new SessionConfigFactory(propsWithLinearKey("", "authtoken"),
				fakeSettingsWithCodeIntel("serena"), null, mapper, null, 8080,
				fakeCatalog(Map.of("claude", fullCapabilities())), null, serena, null);
		ObjectNode overrides = mapper.createObjectNode().put("serenaEnabled", true);
		overrides.putObject("mcpConfig").putObject("serena").put("command", "custom-uv");
		SessionService.CreateOptions options = new SessionService.CreateOptions(
				"s", "branch", "main", System.getProperty("user.dir"), null, overrides, Map.of(), false);

		SessionConfigFactory.Prepared prepared = factory.prepare(UUID.randomUUID(), Path.of("/worktree"), options);

		assertThat(prepared.entity().mcpConfig().path("serena").path("command").asText()).isEqualTo("custom-uv");
	}

	@Test
	void prepareUsesTheProvidersDeclaredSerenaContext() {
		SerenaService serena = fakeSerena(true, "/mnt/d/projects/serena", "uv", null);
		SessionConfigFactory factory = new SessionConfigFactory(propsWithLinearKey("", "authtoken"),
				fakeSettingsWithCodeIntel("serena"), null, mapper, null, 8080,
				fakeCatalog(Map.of("codex", codexLikeCapabilities())), null, serena, null);
		ObjectNode overrides = mapper.createObjectNode().put("provider", "codex").put("serenaEnabled", true);
		SessionService.CreateOptions options = new SessionService.CreateOptions(
				"s", "branch", "main", System.getProperty("user.dir"), null, overrides, Map.of(), false);

		SessionConfigFactory.Prepared prepared = factory.prepare(UUID.randomUUID(), Path.of("/worktree"), options);

		assertThat(serenaArgs(prepared.entity())).contains("--context", "codex");
	}

	@Test
	void prepareRejectsSerenaEnabledWhenNotConfigured() {
		SerenaService serena = fakeSerena(false, "", "uv", null);
		SessionConfigFactory factory = new SessionConfigFactory(propsWithLinearKey("", "authtoken"),
				fakeSettingsWithCodeIntel("serena"), null, mapper, null, 8080,
				fakeCatalog(Map.of("claude", fullCapabilities())), null, serena, null);
		ObjectNode overrides = mapper.createObjectNode().put("serenaEnabled", true);
		SessionService.CreateOptions options = new SessionService.CreateOptions(
				"s", "branch", "main", System.getProperty("user.dir"), null, overrides, Map.of(), false);

		assertThat(org.assertj.core.api.Assertions.catchThrowable(
						() -> factory.prepare(UUID.randomUUID(), Path.of("/worktree"), options)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Serena");
	}

	@Test
	void extraSystemPromptIncludesSerenaOverrideOnlyForClaudeCodeContext() {
		SerenaService serena = fakeSerena(true, "/mnt/d/projects/serena", "uv", "SERENA OVERRIDE TEXT");
		SessionConfigFactory factory = new SessionConfigFactory(propsWithLinearKey("", "authtoken"),
				fakeSettingsWithCodeIntel("serena"), null, mapper, null, 8080,
				fakeCatalog(Map.of("claude", fullCapabilities(), "codex", codexLikeCapabilities())), null, serena, null);
		SessionEntity claudeSession = SessionEntity.builder().id(UUID.randomUUID()).name("s").provider("claude")
				.repoPath("/repo").branch("b").baseBranch("main").worktreePath("/wt")
				.state(SessionState.CREATING).kind("user").codeIntel("serena").build();
		SessionEntity codexSession = claudeSession.toBuilder().provider("codex").build();

		assertThat(factory.extraSystemPrompt(claudeSession)).contains("SERENA OVERRIDE TEXT");
		assertThat(factory.extraSystemPrompt(codexSession)).isNull();
	}

	// --- phase 13: graphify as the selected code-intelligence tool ---

	private static GraphifyService fakeGraphify(boolean configured, String root, String uvCommand) {
		AppProperties props = new AppProperties("/repo", "/home/u/claude-worktrees", "", "", 4, "", "", "", "logs", 30,
				65536, 1048576, Map.of());
		return new GraphifyService(null, props, null, null) {
			@Override
			public boolean configured() {
				return configured;
			}

			@Override
			public String root() {
				return root;
			}

			@Override
			public String uvCommand() {
				return uvCommand;
			}
		};
	}

	private static List<String> graphifyArgs(SessionEntity entity) {
		List<String> args = new ArrayList<>();
		entity.mcpConfig().path("graphify").path("args").forEach(n -> args.add(n.asText()));
		return args;
	}

	private SessionConfigFactory graphifyFactory(String codeIntel, SerenaService serena, GraphifyService graphify) {
		return new SessionConfigFactory(propsWithLinearKey("", "authtoken"), fakeSettingsWithCodeIntel(codeIntel),
				null, mapper, null, 8080,
				fakeCatalog(Map.of("claude", fullCapabilities(), "codex", codexLikeCapabilities())), null, serena,
				graphify);
	}

	private static SessionService.CreateOptions codeIntelOptions(ObjectNode overrides) {
		return new SessionService.CreateOptions("s", "branch", "main", System.getProperty("user.dir"), null,
				overrides, Map.of(), false);
	}

	@Test
	void prepareLayersAGraphifyMcpEntryPointingAtTheSessionsOwnGraphWhenSelected() {
		SessionConfigFactory factory = graphifyFactory("graphify", fakeSerena(true, "/serena", "uv", null),
				fakeGraphify(true, "/mnt/d/projects/graphify", "/opt/uv"));
		UUID id = UUID.fromString("00000000-0000-0000-0000-000000000042");

		SessionConfigFactory.Prepared prepared = factory.prepare(id, Path.of("/worktree"),
				codeIntelOptions(mapper.createObjectNode().put("codeIntelEnabled", true)));

		assertThat(prepared.entity().codeIntel()).isEqualTo("graphify");
		assertThat(prepared.entity().mcpConfig().has("serena")).isFalse();
		assertThat(prepared.entity().mcpConfig().path("graphify").path("command").asText()).isEqualTo("/opt/uv");
		assertThat(prepared.entity().mcpConfig().path("graphify").has("env")).isFalse();
		assertThat(graphifyArgs(prepared.entity())).containsExactly("run", "--directory", "/mnt/d/projects/graphify",
				"--no-dev", "--extra", "mcp", "--extra", "sql", "graphify-mcp",
				"/home/u/claude-worktrees/.graphify/00000000-0000-0000-0000-000000000042/graph.json");
	}

	@Test
	void prepareLayersSerenaNotGraphifyWhenSerenaIsSelectedEvenWithAGraphifyRoot() {
		SessionConfigFactory factory = graphifyFactory("serena", fakeSerena(true, "/serena", "uv", null),
				fakeGraphify(true, "/graphify", "uv"));

		SessionConfigFactory.Prepared prepared = factory.prepare(UUID.randomUUID(), Path.of("/worktree"),
				codeIntelOptions(mapper.createObjectNode().put("codeIntelEnabled", true)));

		assertThat(prepared.entity().codeIntel()).isEqualTo("serena");
		assertThat(prepared.entity().mcpConfig().has("serena")).isTrue();
		assertThat(prepared.entity().mcpConfig().has("graphify")).isFalse();
	}

	@Test
	void prepareKeepsAnExplicitGraphifyMcpEntryUntouched() {
		SessionConfigFactory factory = graphifyFactory("graphify", fakeSerena(false, "", "uv", null),
				fakeGraphify(true, "/graphify", "uv"));
		ObjectNode overrides = mapper.createObjectNode().put("codeIntelEnabled", true);
		overrides.putObject("mcpConfig").putObject("graphify").put("command", "custom-graphify");

		SessionConfigFactory.Prepared prepared = factory.prepare(UUID.randomUUID(), Path.of("/worktree"),
				codeIntelOptions(overrides));

		assertThat(prepared.entity().mcpConfig().path("graphify").path("command").asText()).isEqualTo("custom-graphify");
	}

	@Test
	void prepareWithTheFlagOffNeverAttachesATool() {
		SessionConfigFactory factory = graphifyFactory("graphify", fakeSerena(true, "/serena", "uv", null),
				fakeGraphify(true, "/graphify", "uv"));

		SessionConfigFactory.Prepared prepared = factory.prepare(UUID.randomUUID(), Path.of("/worktree"),
				codeIntelOptions(mapper.createObjectNode().put("codeIntelEnabled", false)));

		assertThat(prepared.entity().codeIntel()).isNull();
		assertThat(prepared.entity().mcpConfig()).isNull();
		assertThat(factory.extraSystemPrompt(prepared.entity())).isNull();
	}

	@Test
	void prepareRejectsTheFlagWhenTheSelectorIsNone() {
		SessionConfigFactory factory = graphifyFactory("none", fakeSerena(true, "/serena", "uv", null),
				fakeGraphify(true, "/graphify", "uv"));

		assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> factory.prepare(UUID.randomUUID(),
				Path.of("/worktree"), codeIntelOptions(mapper.createObjectNode().put("codeIntelEnabled", true)))))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Code intelligence is not configured (Settings → MCP servers)");
	}

	@Test
	void prepareRejectsGraphifyWhenSelectedButNotConfigured() {
		SessionConfigFactory factory = graphifyFactory("graphify", fakeSerena(true, "/serena", "uv", null),
				fakeGraphify(false, "", "uv"));

		assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> factory.prepare(UUID.randomUUID(),
				Path.of("/worktree"), codeIntelOptions(mapper.createObjectNode().put("codeIntelEnabled", true)))))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Graphify is not configured");
	}

	@Test
	void legacySerenaEnabledKeyResolvesToWhateverToolIsSelected() {
		SessionConfigFactory factory = graphifyFactory("graphify", fakeSerena(false, "", "uv", null),
				fakeGraphify(true, "/graphify", "uv"));

		SessionConfigFactory.Prepared prepared = factory.prepare(UUID.randomUUID(), Path.of("/worktree"),
				codeIntelOptions(mapper.createObjectNode().put("serenaEnabled", true)));

		assertThat(prepared.entity().codeIntel()).isEqualTo("graphify");
	}

	@Test
	void codeIntelEnabledWinsOverTheLegacyAliasWhenBothArePresent() {
		SessionConfigFactory factory = graphifyFactory("graphify", fakeSerena(false, "", "uv", null),
				fakeGraphify(true, "/graphify", "uv"));

		SessionConfigFactory.Prepared prepared = factory.prepare(UUID.randomUUID(), Path.of("/worktree"),
				codeIntelOptions(mapper.createObjectNode().put("serenaEnabled", true).put("codeIntelEnabled", false)));

		assertThat(prepared.entity().codeIntel()).isNull();
	}

	@Test
	void extraSystemPromptCarriesTheGraphifyBlockForEveryProviderWhileSerenasStaysClaudeOnly() {
		SessionConfigFactory factory = graphifyFactory("graphify", fakeSerena(true, "/serena", "uv", "SERENA OVERRIDE TEXT"),
				fakeGraphify(true, "/graphify", "uv"));
		SessionEntity claudeGraphify = SessionEntity.builder().id(UUID.randomUUID()).name("s").provider("claude")
				.repoPath("/repo").branch("b").baseBranch("main").worktreePath("/wt")
				.state(SessionState.CREATING).kind("user").codeIntel("graphify").build();
		SessionEntity codexGraphify = claudeGraphify.toBuilder().provider("codex").build();
		SessionEntity codexSerena = claudeGraphify.toBuilder().provider("codex").codeIntel("serena").build();

		assertThat(factory.extraSystemPrompt(claudeGraphify)).contains("graphify` MCP server").doesNotContain("SERENA");
		assertThat(factory.extraSystemPrompt(codexGraphify)).contains("graphify` MCP server");
		assertThat(factory.extraSystemPrompt(codexSerena)).isNull();
	}

	@Test
	void configOverridesFromCarriesTheFlagNotTheTool() {
		SessionConfigFactory factory = graphifyFactory("graphify", fakeSerena(false, "", "uv", null),
				fakeGraphify(true, "/graphify", "uv"));
		SessionEntity source = SessionEntity.builder().id(UUID.randomUUID()).name("s").provider("claude")
				.repoPath("/repo").branch("b").baseBranch("main").worktreePath("/wt")
				.state(SessionState.CREATING).kind("user").codeIntel("serena").build();

		ObjectNode overrides = factory.configOverridesFrom(source);

		assertThat(overrides.path("codeIntelEnabled").asBoolean()).isTrue();
		assertThat(overrides.has("serenaEnabled")).isFalse();
		assertThat(factory.configOverridesFrom(source.toBuilder().codeIntel(null).build())
				.path("codeIntelEnabled").asBoolean()).isFalse();
	}
}
