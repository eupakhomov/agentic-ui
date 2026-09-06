package de.pamir.claude.ui.config;

import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Persisted, UI-editable settings (Settings dialog → Linear integration). Deliberately not a
 * secret store: the Linear API key stays in AppProperties/env, never touches app_setting.
 */
@Service
public class SettingsService {

	private static final String LINEAR_OAUTH_KEY = "linear.oauth";
	private static final String TICKET_IMPORT_SPEC_KEY = "ticket-import.spec";
	private static final String ECOSYSTEM_ROOT_KEY = "ecosystem.root";
	private static final String PR_CHECKS_ENABLED_KEY = "pr-checks.enabled";
	private static final String PR_CHECKS_POLL_INTERVAL_KEY = "pr-checks.poll-interval-seconds";
	private static final int DEFAULT_PR_CHECK_POLL_INTERVAL_SECONDS = 180;
	private static final int MIN_PR_CHECK_POLL_INTERVAL_SECONDS = 30;
	private static final String LIBRARY_SKILLS_ROOT_KEY = "library.skills-root";
	private static final String LIBRARY_AGENTS_ROOT_KEY = "library.agents-root";
	private static final String LIBRARY_VECTORIZE_KEY = "library.vectorize";
	private static final String LIBRARY_SYNC_ENABLED_KEY = "library.sync-enabled";
	private static final String LIBRARY_SYNC_INTERVAL_KEY = "library.sync-interval-minutes";
	private static final int DEFAULT_LIBRARY_SYNC_INTERVAL_MINUTES = 60;
	private static final int MIN_LIBRARY_SYNC_INTERVAL_MINUTES = 5;
	private static final String DEFAULT_PROVIDER_KEY = "session.default-provider";
	private static final String SYSTEM_PROVIDER_KEY = "session.system-provider";
	private static final String CODEX_PRICING_KEY = "codex.pricing";
	private static final Set<String> VALID_TIERS = Set.of("cheap", "standard", "premium");
	/**
	 * memory.reflection-model / service-discovery.model used to store a raw Claude alias
	 * ("haiku"/"sonnet"/"opus") passed straight into set_model; normalizing legacy values here
	 * keeps a pre-existing install's persisted choice meaningful now that these settings are
	 * provider-neutral tier names (see docs/plan/phase-9-production-hardening.md P1/P3).
	 */
	private static final Map<String, String> LEGACY_MODEL_ALIAS_TIER =
			Map.of("haiku", "cheap", "sonnet", "standard", "opus", "premium");
	/**
	 * Codex reports token counts, never a per-turn USD figure (see
	 * docs/plan/phase-5.13-codex-provider.md Decision 2) — this is a manually
	 * maintained, Settings-editable estimate, not tied to any real billing API.
	 * "default" is the fallback entry for a model with no specific row.
	 */
	private static final String DEFAULT_CODEX_PRICING =
			"{\"default\": {\"inputPer1M\": 2, \"cachedInputPer1M\": 0.5, \"outputPer1M\": 8}}";
	private static final String MEMORY_ROOT_KEY = "memory.root";
	private static final String MEMORY_ENABLED_KEY = "memory.enabled";
	private static final String MEMORY_REFLECTION_DEFAULT_KEY = "memory.reflection-default";
	private static final String MEMORY_REFLECTION_MODEL_KEY = "memory.reflection-model";
	private static final String MEMORY_SYNC_INTERVAL_KEY = "memory.sync-interval-minutes";
	private static final int DEFAULT_MEMORY_SYNC_INTERVAL_MINUTES = 5;
	private static final int MIN_MEMORY_SYNC_INTERVAL_MINUTES = 1;
	private static final String MEMORY_RETENTION_DAYS_KEY = "memory.retention-days";
	private static final String MEMORY_APPROVAL_REQUIRED_KEY = "memory.reflection-approval-required";
	private static final String SERVICE_DISCOVERY_ENABLED_KEY = "service-discovery.enabled";
	private static final String SERVICE_DISCOVERY_STALENESS_DAYS_KEY = "service-discovery.staleness-days";
	private static final int DEFAULT_SERVICE_DISCOVERY_STALENESS_DAYS = 14;
	private static final String SERVICE_DISCOVERY_MODEL_KEY = "service-discovery.model";

	private final SettingsRepository repo;
	private final AppProperties props;
	private final ObjectMapper mapper;

	public SettingsService(SettingsRepository repo, AppProperties props, ObjectMapper mapper) {
		this.repo = repo;
		this.props = props;
		this.mapper = mapper;
	}

	/**
	 * Alternative to props.linearApiKey() for SSO-gated Linear accounts (e.g. Google identity):
	 * omits the Authorization header entirely, trusting the ambient `claude` CLI's own cached
	 * OAuth credential for this MCP server (set up once via `claude mcp add` on the backend
	 * host). Ignored if an explicit API key is set — the key always wins.
	 */
	public boolean linearOAuthEnabled() {
		return repo.get(LINEAR_OAUTH_KEY).map(Boolean::parseBoolean).orElse(false);
	}

	public void setLinearOAuthEnabled(boolean enabled) {
		repo.set(LINEAR_OAUTH_KEY, Boolean.toString(enabled));
	}

	public String ticketImportSpec() {
		return repo.get(TICKET_IMPORT_SPEC_KEY).orElse("");
	}

	public void setTicketImportSpec(String spec) {
		repo.set(TICKET_IMPORT_SPEC_KEY, spec == null ? "" : spec);
	}

	/**
	 * Default read-only context folder + service discovery root (parent of all sibling
	 * services), editable per-session at creation time. Empty = no default wider context.
	 */
	public String ecosystemRoot() {
		return repo.get(ECOSYSTEM_ROOT_KEY).orElse("");
	}

	public void setEcosystemRoot(String path) {
		repo.set(ECOSYSTEM_ROOT_KEY, path == null ? "" : path);
	}

	/** Global on/off switch for background PR CI-status polling and its notifications. */
	public boolean prChecksEnabled() {
		return repo.get(PR_CHECKS_ENABLED_KEY).map(Boolean::parseBoolean).orElse(true);
	}

	public void setPrChecksEnabled(boolean enabled) {
		repo.set(PR_CHECKS_ENABLED_KEY, Boolean.toString(enabled));
	}

	/** How often (seconds) an open PR's checks are re-polled; clamped to a sane floor. */
	public int prCheckPollIntervalSeconds() {
		return repo.get(PR_CHECKS_POLL_INTERVAL_KEY).map(Integer::parseInt)
				.map(v -> Math.max(v, MIN_PR_CHECK_POLL_INTERVAL_SECONDS))
				.orElse(DEFAULT_PR_CHECK_POLL_INTERVAL_SECONDS);
	}

	public void setPrCheckPollIntervalSeconds(int seconds) {
		repo.set(PR_CHECKS_POLL_INTERVAL_KEY, Integer.toString(Math.max(seconds, MIN_PR_CHECK_POLL_INTERVAL_SECONDS)));
	}

	/**
	 * Managed skill folder: import destination AND the root the create-dialog picker scans /
	 * provisioning reads. The old CLAUDE_UI_SKILLS_ROOT config value is the default, so
	 * existing installs keep working with no row present.
	 */
	public String librarySkillsRoot() {
		return repo.get(LIBRARY_SKILLS_ROOT_KEY).filter(v -> !v.isBlank()).orElse(props.skillsRoot());
	}

	public void setLibrarySkillsRoot(String path) {
		repo.set(LIBRARY_SKILLS_ROOT_KEY, path == null ? "" : path);
	}

	/** Managed agent folder — import destination for agent assets. */
	public String libraryAgentsRoot() {
		return repo.get(LIBRARY_AGENTS_ROOT_KEY).filter(v -> !v.isBlank())
				.orElse(System.getProperty("user.home") + "/claude-agents");
	}

	public void setLibraryAgentsRoot(String path) {
		repo.set(LIBRARY_AGENTS_ROOT_KEY, path == null ? "" : path);
	}

	/** Embed skill/agent content on import & sync (needs CLAUDE_UI_VOYAGE_API_KEY). */
	public boolean libraryVectorize() {
		return repo.get(LIBRARY_VECTORIZE_KEY).map(Boolean::parseBoolean).orElse(false);
	}

	public void setLibraryVectorize(boolean enabled) {
		repo.set(LIBRARY_VECTORIZE_KEY, Boolean.toString(enabled));
	}

	/** Global on/off switch for the scheduled library source sync. */
	public boolean librarySyncEnabled() {
		return repo.get(LIBRARY_SYNC_ENABLED_KEY).map(Boolean::parseBoolean).orElse(true);
	}

	public void setLibrarySyncEnabled(boolean enabled) {
		repo.set(LIBRARY_SYNC_ENABLED_KEY, Boolean.toString(enabled));
	}

	/** How often (minutes) a synced source is re-checked; clamped to a sane floor. */
	public int librarySyncIntervalMinutes() {
		return repo.get(LIBRARY_SYNC_INTERVAL_KEY).map(Integer::parseInt)
				.map(v -> Math.max(v, MIN_LIBRARY_SYNC_INTERVAL_MINUTES))
				.orElse(DEFAULT_LIBRARY_SYNC_INTERVAL_MINUTES);
	}

	public void setLibrarySyncIntervalMinutes(int minutes) {
		repo.set(LIBRARY_SYNC_INTERVAL_KEY, Integer.toString(Math.max(minutes, MIN_LIBRARY_SYNC_INTERVAL_MINUTES)));
	}

	/** Provider id the create dialog pre-selects for a new session; per-session choice always wins. */
	public String defaultProvider() {
		return repo.get(DEFAULT_PROVIDER_KEY).filter(v -> !v.isBlank()).orElse("claude");
	}

	public void setDefaultProvider(String provider) {
		repo.set(DEFAULT_PROVIDER_KEY, provider == null || provider.isBlank() ? "claude" : provider);
	}

	/**
	 * Provider the singleton system session (ticket import, library AI-fill, reflection, service
	 * discovery, commit/PR drafting, handoff briefs — see docs/plan/phase-9-production-hardening.md
	 * P1) spawns as; empty (the default) means "follow {@link #defaultProvider()}". Note a Codex
	 * system session gets no MCP tool pre-approval (Codex rejects {@code allowedTools} outright —
	 * decision 10 in phase-5.13-codex-provider.md), so a backend-initiated turn needing Linear/
	 * memory tools has nobody to answer the resulting approval prompt and will simply time out.
	 */
	public String systemProvider() {
		return repo.get(SYSTEM_PROVIDER_KEY).filter(v -> !v.isBlank()).orElseGet(this::defaultProvider);
	}

	/** The raw persisted override (possibly blank = "follow default provider"), for the Settings
	 * dialog to round-trip correctly — {@link #systemProvider()} already resolves the fallback, so
	 * blank would otherwise be indistinguishable from an explicit choice that happens to match. */
	public String systemProviderOverride() {
		return repo.get(SYSTEM_PROVIDER_KEY).orElse("");
	}

	public void setSystemProvider(String provider) {
		repo.set(SYSTEM_PROVIDER_KEY, provider == null ? "" : provider.strip());
	}

	/** Per-model $-per-million-tokens rate table used to estimate Codex turn cost. */
	public String codexPricing() {
		return repo.get(CODEX_PRICING_KEY).filter(v -> !v.isBlank()).orElse(DEFAULT_CODEX_PRICING);
	}

	public void setCodexPricing(String json) {
		if (json == null || json.isBlank()) {
			repo.set(CODEX_PRICING_KEY, DEFAULT_CODEX_PRICING);
			return;
		}
		boolean isObject;
		try {
			isObject = mapper.readTree(json).isObject();
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("codexPricing is not valid JSON: " + e.getMessage());
		}
		if (!isObject) {
			throw new IllegalArgumentException("codexPricing must be a JSON object");
		}
		repo.set(CODEX_PRICING_KEY, json);
	}

	/** Managed semantic-memory root (Markdown files, source of truth — see phase-5.3 doc). */
	public String memoryRoot() {
		return repo.get(MEMORY_ROOT_KEY).filter(v -> !v.isBlank()).orElse(props.memoryRoot());
	}

	public void setMemoryRoot(String path) {
		repo.set(MEMORY_ROOT_KEY, path == null ? "" : path);
	}

	/** Global on/off switch for the memory MCP server injection + episodic window. */
	public boolean memoryEnabled() {
		return repo.get(MEMORY_ENABLED_KEY).map(Boolean::parseBoolean).orElse(true);
	}

	public void setMemoryEnabled(boolean enabled) {
		repo.set(MEMORY_ENABLED_KEY, Boolean.toString(enabled));
	}

	/** Default value of a new session's reflectionEnabled flag (per-session/template always wins). */
	public boolean memoryReflectionDefault() {
		return repo.get(MEMORY_REFLECTION_DEFAULT_KEY).map(Boolean::parseBoolean).orElse(false);
	}

	public void setMemoryReflectionDefault(boolean enabled) {
		repo.set(MEMORY_REFLECTION_DEFAULT_KEY, Boolean.toString(enabled));
	}

	/**
	 * Tier ("cheap"/"standard"/"premium") the reflection system turn runs on — resolved to a
	 * concrete model via {@link de.pamir.claude.ui.session.ModelCatalog#byTier} for whatever
	 * {@link #systemProvider()} is currently set to; raise to "standard" if the cheap tier's
	 * extraction quality disappoints.
	 */
	public String memoryReflectionModel() {
		return normalizeTier(repo.get(MEMORY_REFLECTION_MODEL_KEY).orElse(""));
	}

	public void setMemoryReflectionModel(String tier) {
		repo.set(MEMORY_REFLECTION_MODEL_KEY, normalizeTier(tier));
	}

	/** How often (minutes) the memory root is re-scanned for human edits; clamped to a sane floor. */
	public int memorySyncIntervalMinutes() {
		return repo.get(MEMORY_SYNC_INTERVAL_KEY).map(Integer::parseInt)
				.map(v -> Math.max(v, MIN_MEMORY_SYNC_INTERVAL_MINUTES))
				.orElse(DEFAULT_MEMORY_SYNC_INTERVAL_MINUTES);
	}

	public void setMemorySyncIntervalMinutes(int minutes) {
		repo.set(MEMORY_SYNC_INTERVAL_KEY, Integer.toString(Math.max(minutes, MIN_MEMORY_SYNC_INTERVAL_MINUTES)));
	}

	/** Days a CLOSED-and-reflected session's raw journal is kept before pruning; 0 = never prune. */
	public int memoryRetentionDays() {
		return repo.get(MEMORY_RETENTION_DAYS_KEY).map(Integer::parseInt).map(v -> Math.max(v, 0)).orElse(0);
	}

	public void setMemoryRetentionDays(int days) {
		repo.set(MEMORY_RETENTION_DAYS_KEY, Integer.toString(Math.max(days, 0)));
	}

	/**
	 * When true (default), a reflection is held as a pending proposal for explicit approve/
	 * discard instead of being applied immediately — matches this app's general "ask before
	 * consequential actions" posture (decision 14, phase-5.3 doc). Off = the original
	 * auto-apply behavior.
	 */
	public boolean memoryReflectionApprovalRequired() {
		return repo.get(MEMORY_APPROVAL_REQUIRED_KEY).map(Boolean::parseBoolean).orElse(true);
	}

	public void setMemoryReflectionApprovalRequired(boolean required) {
		repo.set(MEMORY_APPROVAL_REQUIRED_KEY, Boolean.toString(required));
	}

	/**
	 * Central on/off switch for ecosystem service discovery (docs/plan/phase-8-service-discovery.md):
	 * gates the close-triggered/manual discovery runs AND both agent-facing tools (each also
	 * self-gates on this same setting — decision 6), independently of {@link #memoryEnabled()}.
	 */
	public boolean serviceDiscoveryEnabled() {
		return repo.get(SERVICE_DISCOVERY_ENABLED_KEY).map(Boolean::parseBoolean).orElse(true);
	}

	public void setServiceDiscoveryEnabled(boolean enabled) {
		repo.set(SERVICE_DISCOVERY_ENABLED_KEY, Boolean.toString(enabled));
	}

	/** Days before an existing service profile is considered stale and eligible for regeneration. */
	public int serviceDiscoveryStalenessDays() {
		return repo.get(SERVICE_DISCOVERY_STALENESS_DAYS_KEY).map(Integer::parseInt).map(v -> Math.max(v, 1))
				.orElse(DEFAULT_SERVICE_DISCOVERY_STALENESS_DAYS);
	}

	public void setServiceDiscoveryStalenessDays(int days) {
		repo.set(SERVICE_DISCOVERY_STALENESS_DAYS_KEY, Integer.toString(Math.max(days, 1)));
	}

	/**
	 * Tier ("cheap"/"standard"/"premium") the discovery system turn runs on; raise to "standard"
	 * if the cheap tier's descriptions disappoint. See {@link #memoryReflectionModel()} for the
	 * tier→model resolution and provider it follows.
	 */
	public String serviceDiscoveryModel() {
		return normalizeTier(repo.get(SERVICE_DISCOVERY_MODEL_KEY).orElse(""));
	}

	public void setServiceDiscoveryModel(String tier) {
		repo.set(SERVICE_DISCOVERY_MODEL_KEY, normalizeTier(tier));
	}

	/** Accepts a tier name as-is, maps a legacy raw Claude alias to its tier, else defaults to "cheap". */
	private static String normalizeTier(String raw) {
		String v = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
		if (VALID_TIERS.contains(v)) {
			return v;
		}
		return LEGACY_MODEL_ALIAS_TIER.getOrDefault(v, "cheap");
	}
}
