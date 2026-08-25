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
}
