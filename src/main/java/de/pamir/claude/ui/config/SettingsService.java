package de.pamir.claude.ui.config;

import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Persisted, UI-editable settings (Settings dialog). Deliberately not a secret store: the Linear
 * API key stays in AppProperties/env, never touches app_setting.
 *
 * <p>{@link #current()} returns one {@link Settings} snapshot, built from a single {@link
 * SettingsRepository#all()} query and cached until the next {@link #apply}. Adding a setting is two
 * edits: a component on {@link Settings}/{@link SettingsPatch}, and a row in {@link #fields} below —
 * {@link #current()} and {@link #apply} both pick it up automatically, and so does the controller's
 * JSON view (see docs/plan/phase-10-review-followups.md R8c). Per-provider pricing ({@link
 * #pricingFor}) is dynamically keyed and stays outside this table, as does {@link #systemProvider()}
 * (the resolved form — {@link Settings#systemProvider()} holds the raw override).
 */
@Service
public class SettingsService {

	private static final String LINEAR_OAUTH_KEY = "linear.oauth";
	private static final String TICKET_IMPORT_SPEC_KEY = "ticket-import.spec";
	private static final String ECOSYSTEM_ROOT_KEY = "ecosystem.root";
	private static final String MONOREPO_SERVICE_GLOBS_KEY = "ecosystem.monorepo-service-globs";
	private static final String DEFAULT_MONOREPO_SERVICE_GLOBS = "packages/*,services/*,apps/*,libs/*";
	private static final String PR_CHECKS_ENABLED_KEY = "pr-checks.enabled";
	private static final String PR_CHECKS_POLL_INTERVAL_KEY = "pr-checks.poll-interval-seconds";
	private static final String LIBRARY_SKILLS_ROOT_KEY = "library.skills-root";
	private static final String LIBRARY_AGENTS_ROOT_KEY = "library.agents-root";
	private static final String LIBRARY_VECTORIZE_KEY = "library.vectorize";
	private static final String LIBRARY_SYNC_ENABLED_KEY = "library.sync-enabled";
	private static final String LIBRARY_SYNC_INTERVAL_KEY = "library.sync-interval-minutes";
	private static final String DEFAULT_PROVIDER_KEY = "session.default-provider";
	private static final String SYSTEM_PROVIDER_KEY = "session.system-provider";
	private static final String PRICING_KEY_SUFFIX = ".pricing";
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
	 * Seed pricing for "codex", the one provider that ships without a per-turn USD figure today
	 * (see docs/plan/phase-5.13-codex-provider.md Decision 2) — a manually maintained,
	 * Settings-editable estimate, not tied to any real billing API. "default" is the fallback
	 * entry for a model with no specific row. Any other provider declaring {@code
	 * reportsCostUsd: false} (docs/plan/phase-10-review-followups.md R1) starts from an empty
	 * table (no default seed) until someone edits it — {@link
	 * de.pamir.claude.ui.session.CodexCostEstimator#estimate} already treats a missing rate as
	 * zero cost, so this is safe.
	 */
	private static final String DEFAULT_CODEX_PRICING =
			"{\"default\": {\"inputPer1M\": 2, \"cachedInputPer1M\": 0.5, \"outputPer1M\": 8}}";
	private static final String EMPTY_PRICING = "{}";
	private static final String MEMORY_ROOT_KEY = "memory.root";
	private static final String MEMORY_ENABLED_KEY = "memory.enabled";
	private static final String MEMORY_REFLECTION_DEFAULT_KEY = "memory.reflection-default";
	private static final String MEMORY_REFLECTION_MODEL_KEY = "memory.reflection-model";
	private static final String MEMORY_SYNC_INTERVAL_KEY = "memory.sync-interval-minutes";
	private static final String MEMORY_RETENTION_DAYS_KEY = "memory.retention-days";
	private static final String MEMORY_APPROVAL_REQUIRED_KEY = "memory.reflection-approval-required";
	private static final String SERVICE_DISCOVERY_ENABLED_KEY = "service-discovery.enabled";
	private static final String SERVICE_DISCOVERY_STALENESS_DAYS_KEY = "service-discovery.staleness-days";
	private static final String SERVICE_DISCOVERY_MODEL_KEY = "service-discovery.model";

	/** One row per {@link Settings}/{@link SettingsPatch} component — see the class doc. */
	private record Field<T>(String key, Supplier<T> defaultValue, Function<String, T> parse,
							 Function<T, String> serialize, UnaryOperator<T> normalize,
							 Function<SettingsPatch, T> fromPatch) {

		T resolve(Map<String, String> raw) {
			String v = raw.get(key);
			T value = (v == null || v.isBlank()) ? defaultValue.get() : parse.apply(v);
			return normalize.apply(value);
		}

		void applyIfPresent(SettingsRepository repo, SettingsPatch patch) {
			T v = fromPatch.apply(patch);
			if (v != null) {
				repo.set(key, serialize.apply(normalize.apply(v)));
			}
		}
	}

	private static Field<Boolean> boolField(String key, boolean def, Function<SettingsPatch, Boolean> fromPatch) {
		return new Field<>(key, () -> def, Boolean::parseBoolean, Object::toString, UnaryOperator.identity(),
				fromPatch);
	}

	private static Field<Integer> intField(String key, int def, int min, Function<SettingsPatch, Integer> fromPatch) {
		return new Field<>(key, () -> def, Integer::parseInt, Object::toString, v -> Math.max(v, min), fromPatch);
	}

	private static Field<String> strField(String key, Supplier<String> def, Function<SettingsPatch, String> fromPatch) {
		return new Field<>(key, def, Function.identity(), Function.identity(), v -> v == null ? "" : v, fromPatch);
	}

	private static Field<String> tierField(String key, Function<SettingsPatch, String> fromPatch) {
		return new Field<>(key, () -> "", Function.identity(), Function.identity(), SettingsService::normalizeTier,
				fromPatch);
	}

	private final Field<Boolean> linearOAuthEnabled = boolField(LINEAR_OAUTH_KEY, false, SettingsPatch::linearOAuthEnabled);
	private final Field<String> ticketImportSpec = strField(TICKET_IMPORT_SPEC_KEY, () -> "", SettingsPatch::ticketImportSpec);
	private final Field<String> ecosystemRoot = strField(ECOSYSTEM_ROOT_KEY, () -> "", SettingsPatch::ecosystemRoot);
	private final Field<String> monorepoServiceGlobs = strField(MONOREPO_SERVICE_GLOBS_KEY,
			() -> DEFAULT_MONOREPO_SERVICE_GLOBS, SettingsPatch::monorepoServiceGlobs);
	private final Field<Boolean> prChecksEnabled = boolField(PR_CHECKS_ENABLED_KEY, true, SettingsPatch::prChecksEnabled);
	private final Field<Integer> prCheckPollIntervalSeconds =
			intField(PR_CHECKS_POLL_INTERVAL_KEY, 180, 30, SettingsPatch::prCheckPollIntervalSeconds);
	private final Field<String> librarySkillsRoot;
	private final Field<String> libraryAgentsRoot =
			strField(LIBRARY_AGENTS_ROOT_KEY, () -> System.getProperty("user.home") + "/claude-agents",
					SettingsPatch::libraryAgentsRoot);
	private final Field<Boolean> libraryVectorize = boolField(LIBRARY_VECTORIZE_KEY, false, SettingsPatch::libraryVectorize);
	private final Field<Boolean> librarySyncEnabled = boolField(LIBRARY_SYNC_ENABLED_KEY, true, SettingsPatch::librarySyncEnabled);
	private final Field<Integer> librarySyncIntervalMinutes =
			intField(LIBRARY_SYNC_INTERVAL_KEY, 60, 5, SettingsPatch::librarySyncIntervalMinutes);
	private final Field<String> defaultProvider = new Field<>(DEFAULT_PROVIDER_KEY, () -> "claude",
			Function.identity(), Function.identity(), v -> v == null || v.isBlank() ? "claude" : v,
			SettingsPatch::defaultProvider);
	private final Field<String> systemProviderField = strField(SYSTEM_PROVIDER_KEY, () -> "", SettingsPatch::systemProvider);
	private final Field<String> memoryRoot;
	private final Field<Boolean> memoryEnabled = boolField(MEMORY_ENABLED_KEY, true, SettingsPatch::memoryEnabled);
	private final Field<Boolean> memoryReflectionDefault =
			boolField(MEMORY_REFLECTION_DEFAULT_KEY, false, SettingsPatch::memoryReflectionDefault);
	private final Field<String> memoryReflectionModel = tierField(MEMORY_REFLECTION_MODEL_KEY, SettingsPatch::memoryReflectionModel);
	private final Field<Integer> memorySyncIntervalMinutes =
			intField(MEMORY_SYNC_INTERVAL_KEY, 5, 1, SettingsPatch::memorySyncIntervalMinutes);
	private final Field<Integer> memoryRetentionDays = intField(MEMORY_RETENTION_DAYS_KEY, 0, 0, SettingsPatch::memoryRetentionDays);
	private final Field<Boolean> memoryReflectionApprovalRequired =
			boolField(MEMORY_APPROVAL_REQUIRED_KEY, true, SettingsPatch::memoryReflectionApprovalRequired);
	private final Field<Boolean> serviceDiscoveryEnabled =
			boolField(SERVICE_DISCOVERY_ENABLED_KEY, true, SettingsPatch::serviceDiscoveryEnabled);
	private final Field<Integer> serviceDiscoveryStalenessDays =
			intField(SERVICE_DISCOVERY_STALENESS_DAYS_KEY, 14, 1, SettingsPatch::serviceDiscoveryStalenessDays);
	private final Field<String> serviceDiscoveryModel = tierField(SERVICE_DISCOVERY_MODEL_KEY, SettingsPatch::serviceDiscoveryModel);

	private final List<Field<?>> fields;

	private final SettingsRepository repo;
	private final AppProperties props;
	private final ObjectMapper mapper;

	/** Cleared by {@link #apply}; single-user/LAN posture makes the invalidation window harmless. */
	private volatile Settings cache;

	public SettingsService(SettingsRepository repo, AppProperties props, ObjectMapper mapper) {
		this.repo = repo;
		this.props = props;
		this.mapper = mapper;
		this.librarySkillsRoot = strField(LIBRARY_SKILLS_ROOT_KEY, () -> props == null ? "" : props.skillsRoot(),
				SettingsPatch::librarySkillsRoot);
		this.memoryRoot = strField(MEMORY_ROOT_KEY, () -> props == null ? "" : props.memoryRoot(), SettingsPatch::memoryRoot);
		this.fields = List.of(linearOAuthEnabled, ticketImportSpec, ecosystemRoot, monorepoServiceGlobs, prChecksEnabled,
				prCheckPollIntervalSeconds, librarySkillsRoot, libraryAgentsRoot, libraryVectorize, librarySyncEnabled,
				librarySyncIntervalMinutes, defaultProvider, systemProviderField, memoryRoot, memoryEnabled,
				memoryReflectionDefault, memoryReflectionModel, memorySyncIntervalMinutes, memoryRetentionDays,
				memoryReflectionApprovalRequired, serviceDiscoveryEnabled, serviceDiscoveryStalenessDays,
				serviceDiscoveryModel);
	}

	/** One snapshot of every setting in {@link #fields}, cached until the next {@link #apply}. */
	public Settings current() {
		Settings c = cache;
		if (c != null) {
			return c;
		}
		Map<String, String> raw = repo.all();
		Settings built = new Settings(
				linearOAuthEnabled.resolve(raw),
				ticketImportSpec.resolve(raw),
				ecosystemRoot.resolve(raw),
				monorepoServiceGlobs.resolve(raw),
				prChecksEnabled.resolve(raw),
				prCheckPollIntervalSeconds.resolve(raw),
				librarySkillsRoot.resolve(raw),
				libraryAgentsRoot.resolve(raw),
				libraryVectorize.resolve(raw),
				librarySyncEnabled.resolve(raw),
				librarySyncIntervalMinutes.resolve(raw),
				defaultProvider.resolve(raw),
				systemProviderField.resolve(raw),
				memoryRoot.resolve(raw),
				memoryEnabled.resolve(raw),
				memoryReflectionDefault.resolve(raw),
				memoryReflectionModel.resolve(raw),
				memorySyncIntervalMinutes.resolve(raw),
				memoryRetentionDays.resolve(raw),
				memoryReflectionApprovalRequired.resolve(raw),
				serviceDiscoveryEnabled.resolve(raw),
				serviceDiscoveryStalenessDays.resolve(raw),
				serviceDiscoveryModel.resolve(raw));
		cache = built;
		return built;
	}

	/** Applies every non-null field of {@code patch}; {@code null} means "leave this setting alone". */
	public void apply(SettingsPatch patch) {
		for (Field<?> field : fields) {
			field.applyIfPresent(repo, patch);
		}
		if (patch.codexPricing() != null) {
			setPricingFor("codex", patch.codexPricing());
		}
		cache = null;
	}

	/**
	 * Provider the singleton system session (ticket import, library AI-fill, reflection, service
	 * discovery, commit/PR drafting, handoff briefs — see docs/plan/phase-9-production-hardening.md
	 * P1) spawns as; an empty override (the default) means "follow {@link Settings#defaultProvider()}".
	 * Note a Codex system session gets no MCP tool pre-approval (Codex rejects {@code allowedTools}
	 * outright — decision 10 in phase-5.13-codex-provider.md), so a backend-initiated turn needing
	 * Linear/memory tools has nobody to answer the resulting approval prompt and will simply time out.
	 */
	public String systemProvider() {
		Settings s = current();
		return s.systemProvider().isBlank() ? s.defaultProvider() : s.systemProvider();
	}

	/**
	 * Per-model $-per-million-tokens rate table used to estimate a turn's cost for any provider
	 * that reports no USD of its own ({@code reportsCostUsd: false} — see {@link
	 * de.pamir.claude.ui.session.SessionService#applyEstimatedCost}). Key is {@code
	 * "<provider>.pricing"} — for {@code "codex"} this is the same literal {@code codex.pricing}
	 * key used before the R1 generalization, so existing installs need no migration.
	 */
	public String pricingFor(String provider) {
		return repo.get(provider + PRICING_KEY_SUFFIX).filter(v -> !v.isBlank())
				.orElse("codex".equals(provider) ? DEFAULT_CODEX_PRICING : EMPTY_PRICING);
	}

	public void setPricingFor(String provider, String json) {
		String key = provider + PRICING_KEY_SUFFIX;
		if (json == null || json.isBlank()) {
			repo.set(key, "codex".equals(provider) ? DEFAULT_CODEX_PRICING : EMPTY_PRICING);
			return;
		}
		boolean isObject;
		try {
			isObject = mapper.readTree(json).isObject();
		} catch (RuntimeException e) {
			throw new IllegalArgumentException(provider + " pricing is not valid JSON: " + e.getMessage());
		}
		if (!isObject) {
			throw new IllegalArgumentException(provider + " pricing must be a JSON object");
		}
		repo.set(key, json);
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
