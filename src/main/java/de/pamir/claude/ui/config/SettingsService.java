package de.pamir.claude.ui.config;

import org.springframework.stereotype.Service;

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

	private final SettingsRepository repo;

	public SettingsService(SettingsRepository repo) {
		this.repo = repo;
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
}
