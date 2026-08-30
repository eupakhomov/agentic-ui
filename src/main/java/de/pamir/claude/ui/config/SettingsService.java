package de.pamir.claude.ui.config;

import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

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
	private static final String CODEX_PRICING_KEY = "codex.pricing";
	/**
	 * Codex reports token counts, never a per-turn USD figure (see
	 * docs/plan/phase-5.13-codex-provider.md Decision 2) — this is a manually
	 * maintained, Settings-editable estimate, not tied to any real billing API.
	 * "default" is the fallback entry for a model with no specific row.
	 */
	private static final String DEFAULT_CODEX_PRICING =
			"{\"default\": {\"inputPer1M\": 2, \"cachedInputPer1M\": 0.5, \"outputPer1M\": 8}}";

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
}
