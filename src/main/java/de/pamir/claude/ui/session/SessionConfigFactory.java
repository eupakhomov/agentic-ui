package de.pamir.claude.ui.session;

import de.pamir.claude.ui.config.AppProperties;
import de.pamir.claude.ui.config.SettingsService;
import de.pamir.claude.ui.memory.MemoryEpisodeRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything about turning a {@link SessionService.CreateOptions} (or an existing session, for
 * "duplicate"/"last config") into resolved, validated config: template merge,
 * capability-driven unsupported-field validation (see {@link ProviderCatalog}), default
 * MCP-server layering, and the memory/orchestration system-prompt blocks a spawn needs.
 * Extracted from SessionService (see docs/plan/phase-9-production-hardening.md S1) so
 * create-config parsing is one place instead of tangled with provisioning/spawn orchestration —
 * SessionService still owns the actual worktree creation, DB insert, and sidecar spawn.
 */
@Component
public class SessionConfigFactory {

	private final AppProperties props;
	private final SettingsService settings;
	private final TemplateRepository templates;
	private final ObjectMapper mapper;
	private final MemoryEpisodeRepository episodes;
	private final int serverPort;
	private final ProviderCatalog catalog;

	public SessionConfigFactory(AppProperties props, SettingsService settings, TemplateRepository templates,
								 ObjectMapper mapper, MemoryEpisodeRepository episodes,
								 @Value("${server.port:8080}") int serverPort, ProviderCatalog catalog) {
		this.props = props;
		this.settings = settings;
		this.templates = templates;
		this.mapper = mapper;
		this.episodes = episodes;
		this.serverPort = serverPort;
		this.catalog = catalog;
	}

	/** The resolved entity (still transient — not yet inserted) plus any non-fatal warnings to journal. */
	public record Prepared(SessionEntity entity, List<String> warnings) {
	}

	/** Builds a fresh 'user' session entity (state CREATING) from the given options; does not persist it. */
	public Prepared prepare(UUID id, Path worktree, SessionService.CreateOptions options) {
		String repo = options.repoPath() == null || options.repoPath().isBlank() ? props.repoPath() : options.repoPath();
		if (!Files.exists(Path.of(repo).resolve(".git"))) {
			throw new IllegalArgumentException("not a git repository: " + repo);
		}
		ObjectNode config = mergedConfig(options.templateId(), options.overrides());
		List<String> warnings = new ArrayList<>();
		if (options.templateId() != null) {
			List<TemplateRepository.TemplateAsset> templateAssets = templates.get(options.templateId()).assets();
			if (options.overrides() == null || !options.overrides().has("skillSources")) {
				config.set("skillSources", combineTemplateSources(config.get("skillSources"), templateAssets,
						"skill", "dir", warnings));
			}
			if (options.overrides() == null || !options.overrides().has("agentSources")) {
				config.set("agentSources", combineTemplateSources(config.get("agentSources"), templateAssets,
						"agent", "file", warnings));
			}
		}
		String provider = text(config, "provider", settings.defaultProvider());
		String permissionMode = text(config, "permissionMode", "default");
		JsonNode explicitMcpConfig = config.get("mcpConfig");
		List<String> allowedTools = stringList(config, "allowedTools");
		List<String> disallowedTools = stringList(config, "disallowedTools");
		String thinking = nullableText(config, "thinking");
		Integer maxTurns = config.hasNonNull("maxTurns") ? config.get("maxTurns").asInt() : null;
		String fallbackModel = nullableText(config, "fallbackModel");
		String ecosystemPath = config.has("ecosystemPath") ? nullableText(config, "ecosystemPath")
				: nullableIfBlank(settings.ecosystemRoot());
		List<String> contextDirs = stringList(config, "contextDirs");
		ProviderCapabilities caps = catalog.get(provider);
		// Unsupported controls are rejected at creation time, not silently downgraded (DoD from
		// docs/plan/phase-5.13-codex-provider.md, generalized in
		// docs/plan/phase-10-review-followups.md R1 to any provider's declared capabilities —
		// no provider name appears below, only capability lookups). mcpConfig is never rejected
		// here — the 2026-08-30 MCP follow-up confirmed sidecar-codex can translate the same
		// Claude-shaped config every other session already gets, including the default Linear
		// MCP layering, and no other provider has needed a different answer since.
		if (!caps.permissionModes().contains(permissionMode)) {
			throw new IllegalArgumentException(
					"provider '" + provider + "' does not support permission mode '" + permissionMode + "'");
		}
		if (!caps.supports("allowedTools") && (!allowedTools.isEmpty() || !disallowedTools.isEmpty())) {
			throw new IllegalArgumentException("provider '" + provider + "' does not support allowedTools/disallowedTools");
		}
		if (!caps.supports("thinking") && thinking != null) {
			throw new IllegalArgumentException(
					"provider '" + provider + "' does not support the thinking control (use effort)");
		}
		if (!caps.supports("maxTurns") && maxTurns != null) {
			throw new IllegalArgumentException("provider '" + provider + "' does not support maxTurns");
		}
		if (!caps.supports("fallbackModel") && fallbackModel != null) {
			throw new IllegalArgumentException("provider '" + provider + "' does not support fallbackModel");
		}
		if (!caps.supports("agentSources") && arrayOrEmpty(config, "agentSources").size() > 0) {
			throw new IllegalArgumentException("provider '" + provider + "' does not support agentSources");
		}
		if (!caps.contextDirs()) {
			// Ecosystem/context dirs commonly come from a global default (settings.ecosystemRoot()),
			// not explicit per-session intent, so this degrades with a visible warning rather than
			// rejecting creation outright.
			if (ecosystemPath != null || !contextDirs.isEmpty()) {
				warnings.add("provider '" + provider + "' does not support context directories — skipped");
			}
			ecosystemPath = null;
			contextDirs = List.of();
		}
		if (caps.supports("allowedTools")) {
			// allowedTools/disallowedTools are additive presets (bypass or block specific tools
			// without switching the session into allow-list-only mode) — safe to append to
			// regardless of whether the session configured any of its own. A provider that
			// rejects allowedTools outright (checked above) goes through the normal approval
			// flow instead (decision 10, phase-5.13-codex-provider.md). Named explicitly (not
			// the blanket "mcp__memory" server-level grant) since 7.4's orchestration tools —
			// and now 8's service-discovery tools — live on the same MCP server and must NOT be
			// pre-approved by a blanket grant.
			if (settings.memoryEnabled()) {
				allowedTools = new ArrayList<>(allowedTools);
				allowedTools.add("mcp__memory__memory_tags");
				allowedTools.add("mcp__memory__memory_search");
				allowedTools.add("mcp__memory__memory_read");
			}
			if (settings.serviceDiscoveryEnabled()) {
				// docs/plan/phase-8-service-discovery.md decision 7 — pre-approved independently
				// of memory.enabled, since the two features are toggled separately
				allowedTools = new ArrayList<>(allowedTools);
				allowedTools.add("mcp__memory__service_description");
				allowedTools.add("mcp__memory__find_service");
				allowedTools.add("mcp__memory__list_discovered_services");
			}
		}
		JsonNode mcpConfig = withDefaultMemoryMcp(withDefaultLinearMcp(explicitMcpConfig));

		SessionEntity entity = SessionEntity.builder()
				.id(id).name(options.name())
				.provider(provider).providerConfig(config.get("providerConfig"))
				.repoPath(repo).ecosystemPath(ecosystemPath).contextDirs(contextDirs)
				.branch(options.branch()).baseBranch(options.baseBranch()).worktreePath(worktree.toString())
				.model(nullableText(config, "model")).permissionMode(permissionMode)
				.allowedTools(allowedTools).disallowedTools(disallowedTools)
				.mcpConfig(mcpConfig).envVars(config.get("envVars"))
				.skillSources(arrayOrEmpty(config, "skillSources")).agentSources(arrayOrEmpty(config, "agentSources"))
				.instructions(nullableText(config, "instructions")).thinking(thinking)
				.effort(nullableText(config, "effort")).maxTurns(maxTurns).fallbackModel(fallbackModel)
				.costBudgetUsd(config.hasNonNull("costBudgetUsd") ? new BigDecimal(config.get("costBudgetUsd").asText()) : null)
				.kickoffPrompt(fillPlaceholders(nullableText(config, "kickoffPrompt"), options.kickoffValues()))
				.state(SessionState.CREATING).kind("user").ticketRef(nullableText(config, "ticketRef"))
				.continuedFromId(options.continuedFromId()).parentSessionId(options.parentSessionId())
				.reflectionEnabled(config.path("reflectionEnabled").asBoolean(settings.memoryReflectionDefault()))
				.build();
		return new Prepared(entity, warnings);
	}

	private ObjectNode mergedConfig(UUID templateId, JsonNode overrides) {
		ObjectNode config = mapper.createObjectNode();
		if (templateId != null) {
			JsonNode templateConfig = templates.get(templateId).config();
			if (templateConfig instanceof ObjectNode t) {
				config.setAll(t);
			}
		}
		if (overrides instanceof ObjectNode o) {
			config.setAll(o);
		}
		return config;
	}

	/**
	 * Merges a template's live-linked library assets (skipping ARCHIVED ones, which are reported
	 * back via {@code warnings}) with any free-form sources already in the config's own key.
	 */
	ArrayNode combineTemplateSources(JsonNode existing, List<TemplateRepository.TemplateAsset> templateAssets,
									  String kind, String sourceType, List<String> warnings) {
		ArrayNode combined = mapper.createArrayNode();
		if (existing != null && existing.isArray()) {
			combined.addAll((ArrayNode) existing);
		}
		for (TemplateRepository.TemplateAsset asset : templateAssets) {
			if (!kind.equals(asset.kind())) {
				continue;
			}
			if ("ARCHIVED".equals(asset.status())) {
				warnings.add(kind + " '" + asset.name() + "' is archived and was skipped");
				continue;
			}
			combined.add(mapper.createObjectNode().put("type", sourceType).put("ref", asset.location()));
		}
		return combined;
	}

	/** The Linear MCP server block ({"linear": {...}}), or null if Linear integration isn't configured. */
	ObjectNode linearMcpServer() {
		boolean apiKey = props.linearApiKey() != null && !props.linearApiKey().isBlank();
		if (!apiKey && !settings.linearOAuthEnabled()) {
			return null;
		}
		ObjectNode servers = mapper.createObjectNode();
		ObjectNode linear = servers.putObject("linear");
		linear.put("type", "http").put("url", "https://mcp.linear.app/mcp");
		if (apiKey) {
			// explicit key wins even if OAuth is also enabled in Settings
			linear.putObject("headers").put("Authorization", "Bearer " + props.linearApiKey());
		}
		// else: no headers — relies on the ambient `claude` CLI's own cached OAuth credential for
		// this server URL (set up once via `claude mcp add` on the backend host, e.g. Google-SSO Linear)
		return servers;
	}

	/**
	 * Layers the Linear MCP server into a regular session's mcpConfig by default when Linear
	 * integration is configured, so the agent can read/update tickets without the user having to
	 * wire it up per session — unless the session's own config already defines a "linear" entry,
	 * which wins. Regular sessions go through the normal permission-approval flow for its tools
	 * (unlike the system session, which pre-approves them — see SystemSessionService).
	 */
	// package-private (not private): unit-tested directly — see docs/plan/phase-9-production-hardening.md T1
	JsonNode withDefaultLinearMcp(JsonNode configured) {
		return withDefaultServer(configured, "linear", linearMcpServer());
	}

	/**
	 * The shared in-process MCP server block ({"memory": {...}}) — still keyed "memory" for
	 * backward compatibility, but it now also carries the service-discovery tools (decision 6,
	 * docs/plan/phase-8-service-discovery.md) alongside 7.4's orchestration tools, all on the same
	 * Spring AI MCP server (docs/plan/phase-5.3-memory-reflection.md decision 12a). Null only when
	 * BOTH memory and service discovery are disabled — each tool set still self-gates on its own
	 * setting inside its method bodies, so one feature being off holds even when the other keeps
	 * this entry attached. Pointed at ourselves and authenticated with the same dashboard bearer
	 * token (decision 11).
	 */
	private ObjectNode memoryMcpServer() {
		if (!settings.memoryEnabled() && !settings.serviceDiscoveryEnabled()) {
			return null;
		}
		ObjectNode servers = mapper.createObjectNode();
		ObjectNode memory = servers.putObject("memory");
		memory.put("type", "http").put("url", "http://127.0.0.1:" + serverPort + "/api/mcp/memory");
		if (props.authToken() != null && !props.authToken().isBlank()) {
			memory.putObject("headers").put("Authorization", "Bearer " + props.authToken());
		}
		return servers;
	}

	/** Layers the memory MCP server into a session's mcpConfig, same merge rule as {@link #withDefaultLinearMcp}. */
	JsonNode withDefaultMemoryMcp(JsonNode configured) {
		return withDefaultServer(configured, "memory", memoryMcpServer());
	}

	/**
	 * Merges a default MCP {@code serverBlock} (itself a one-key {@code {"key": {...}}} object,
	 * or null if that default isn't configured/enabled) into a session's own {@code configured}
	 * mcpConfig under {@code key} — unless the session already defines that key itself, which
	 * always wins. Shared merge rule behind {@link #withDefaultLinearMcp}/{@link
	 * #withDefaultMemoryMcp} (docs/plan/phase-9-production-hardening.md G2).
	 */
	private JsonNode withDefaultServer(JsonNode configured, String key, ObjectNode serverBlock) {
		if (serverBlock == null) {
			return configured;
		}
		if (configured == null || configured.isNull()) {
			return serverBlock;
		}
		if (!(configured instanceof ObjectNode existing) || existing.has(key)) {
			return configured;
		}
		ObjectNode merged = mapper.createObjectNode();
		merged.setAll(existing);
		merged.setAll(serverBlock);
		return merged;
	}

	/**
	 * The episodic-window system-prompt block (docs/plan/phase-5.3-memory-reflection.md
	 * "Automatic episodic window" / decision 12b): announces the session's own id (needed for
	 * every memory tool call) and the last few episodes recorded for this service, if any. Null
	 * when memory is disabled or this isn't a real-repo user session (system sessions skip it).
	 */
	private String memorySystemPromptBlock(SessionEntity session) {
		if (!settings.memoryEnabled() || !"user".equals(session.kind())) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		sb.append("Long-term memory tools are available (memory_tags, memory_search, memory_read). ")
				.append("Pass this as `sessionId` in every call: ").append(session.id());
		var recent = episodes.recentByService(session.repoPath(), 5);
		if (!recent.isEmpty()) {
			sb.append("\n\nRecent activity on this service, most recent first:\n");
			for (var ep : recent) {
				sb.append("- ").append(ep.summary()).append('\n');
			}
		}
		return sb.toString();
	}

	/**
	 * 7.4's orchestration-tools announcement — same MCP server as memory (decision 12a), so it's
	 * gated on the same {@code settings.memoryEnabled()}. A child session gets the report_result
	 * reminder instead of the spawn tools (it can't spawn — depth 1, enforced in the tool body
	 * too); a plain session with no ecosystem configured gets nothing, since list_services would
	 * just error for it.
	 */
	private String orchestrationSystemPromptBlock(SessionEntity session) {
		if (!settings.memoryEnabled() || !"user".equals(session.kind())) {
			return null;
		}
		if (session.parentSessionId() != null) {
			return "You are a child session (sessionId: " + session.id() + ") spawned to work on this service "
					+ "as part of a larger cross-service task. When your task is complete, call report_result "
					+ "with sessionId=" + session.id() + " and a concise summary of what you did — this reaches "
					+ "the parent session automatically.";
		}
		if (session.ecosystemPath() == null || session.ecosystemPath().isBlank()) {
			return null;
		}
		return "Multi-service orchestration tools are available (list_services, spawn_child_session, "
				+ "check_children) for tasks that span several services under this session's ecosystem folder. "
				+ "Pass this as `sessionId` in every call: " + session.id();
	}

	/** The extra system-prompt text a spawn should append (memory + orchestration blocks), or null if both are empty. */
	String extraSystemPrompt(SessionEntity session) {
		String joined = java.util.stream.Stream
				.of(memorySystemPromptBlock(session), orchestrationSystemPromptBlock(session))
				.filter(java.util.Objects::nonNull)
				.collect(java.util.stream.Collectors.joining("\n\n"));
		return joined.isBlank() ? null : joined;
	}

	/** Copies a session's tunable config (not its identity: name/branch/repo/provider) into an overrides object. */
	ObjectNode configOverridesFrom(SessionEntity source) {
		ObjectNode overrides = mapper.createObjectNode();
		if (source.model() != null) {
			overrides.put("model", source.model());
		}
		overrides.put("permissionMode", source.permissionMode());
		if (!source.allowedTools().isEmpty()) {
			overrides.set("allowedTools", mapper.valueToTree(source.allowedTools()));
		}
		if (!source.disallowedTools().isEmpty()) {
			overrides.set("disallowedTools", mapper.valueToTree(source.disallowedTools()));
		}
		if (source.mcpConfig() != null && !source.mcpConfig().isNull()) {
			overrides.set("mcpConfig", source.mcpConfig());
		}
		if (source.envVars() != null && !source.envVars().isNull()) {
			overrides.set("envVars", source.envVars());
		}
		if (source.skillSources() != null && source.skillSources().isArray() && !source.skillSources().isEmpty()) {
			overrides.set("skillSources", source.skillSources());
		}
		if (source.agentSources() != null && source.agentSources().isArray() && !source.agentSources().isEmpty()) {
			overrides.set("agentSources", source.agentSources());
		}
		if (source.instructions() != null) {
			overrides.put("instructions", source.instructions());
		}
		if (source.ecosystemPath() != null) {
			overrides.put("ecosystemPath", source.ecosystemPath());
		}
		if (!source.contextDirs().isEmpty()) {
			overrides.set("contextDirs", mapper.valueToTree(source.contextDirs()));
		}
		if (source.thinking() != null) {
			overrides.put("thinking", source.thinking());
		}
		if (source.effort() != null) {
			overrides.put("effort", source.effort());
		}
		if (source.maxTurns() != null) {
			overrides.put("maxTurns", source.maxTurns());
		}
		if (source.fallbackModel() != null) {
			overrides.put("fallbackModel", source.fallbackModel());
		}
		if (source.costBudgetUsd() != null) {
			overrides.put("costBudgetUsd", source.costBudgetUsd().toPlainString());
		}
		overrides.put("reflectionEnabled", source.reflectionEnabled());
		return overrides;
	}

	// package-private static (not private): unit-tested directly — see docs/plan/phase-9-production-hardening.md T1
	static String fillPlaceholders(String prompt, Map<String, String> values) {
		if (prompt == null || values == null) {
			return prompt;
		}
		String filled = prompt;
		for (var entry : values.entrySet()) {
			filled = filled.replace("{{" + entry.getKey() + "}}", entry.getValue());
		}
		return filled;
	}

	// --- config JSON accessors ---

	private static String text(ObjectNode node, String field, String fallback) {
		return node.hasNonNull(field) ? node.get(field).asText() : fallback;
	}

	private static String nullableText(ObjectNode node, String field) {
		return node.hasNonNull(field) ? node.get(field).asText() : null;
	}

	private static String nullableIfBlank(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	private List<String> stringList(ObjectNode node, String field) {
		if (!node.hasNonNull(field) || !node.get(field).isArray()) {
			return List.of();
		}
		return mapper.convertValue(node.get(field),
				mapper.getTypeFactory().constructCollectionType(List.class, String.class));
	}

	private JsonNode arrayOrEmpty(ObjectNode node, String field) {
		return node.hasNonNull(field) && node.get(field).isArray() ? node.get(field) : mapper.createArrayNode();
	}
}
