package de.pamir.claude.ui.integration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import de.pamir.claude.ui.config.AppProperties;
import de.pamir.claude.ui.config.SettingsService;
import de.pamir.claude.ui.session.SessionService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * Generates a branch name + kickoff prompt from a ticket, via the singleton system session
 * (Haiku + the Linear MCP server). Provider-specific today (Linear only); named generically
 * since "ticket system" naming, not "linear", is what should survive a second provider later.
 */
@Service
public class TicketImportService {

	private static final Duration TIMEOUT = Duration.ofSeconds(45);
	private static final Set<String> VALID_MODELS = Set.of("sonnet", "opus", "haiku");

	public record TicketImportResult(String branchName, String prompt, String recommendedModel) {
	}

	private final SessionService sessionService;
	private final AppProperties props;
	private final SettingsService settings;
	private final ObjectMapper mapper;

	public TicketImportService(SessionService sessionService, AppProperties props, SettingsService settings,
								ObjectMapper mapper) {
		this.sessionService = sessionService;
		this.props = props;
		this.settings = settings;
		this.mapper = mapper;
	}

	public boolean enabled() {
		return (props.linearApiKey() != null && !props.linearApiKey().isBlank()) || settings.linearOAuthEnabled();
	}

	public TicketImportResult importTicket(String ticketRef) {
		if (!enabled()) {
			throw new IllegalStateException(
					"Linear integration is not configured (set CLAUDE_UI_LINEAR_API_KEY, or enable OAuth in Settings)");
		}
		if (ticketRef == null || ticketRef.isBlank()) {
			throw new IllegalArgumentException("ticketRef is required");
		}
		String spec = settings.ticketImportSpec();
		String guidance = spec.isBlank() ? "" : "Follow these additional guidelines when choosing the branch name "
				+ "and/or writing the prompt: " + spec.strip() + " ";
		String prompt = ("You have access to Linear via MCP tools. Fetch the Linear issue referenced by \"%s\" "
				+ "(it may be a short identifier like ENG-123 or a full Linear issue URL). "
				+ "%s"
				+ "Then respond with ONLY a single JSON object — no markdown fences, no commentary — "
				+ "of the form: {\"branchName\": \"kebab-case-git-safe-branch-name\", \"prompt\": \"a clear, "
				+ "actionable initial instruction for an engineer/agent implementing this ticket, including its "
				+ "key requirements\", \"recommendedModel\": \"sonnet|opus|haiku\"}. branchName must be short, "
				+ "kebab-case, git-ref-safe, and include the ticket identifier, e.g. \"ENG-123-fix-login-bug\" OR "
				+ "\"eng-123-fix-login-bug\". recommendedModel must be exactly one of \"sonnet\", \"opus\", or "
				+ "\"haiku\", chosen by the ticket's apparent complexity: \"haiku\" for trivial/mechanical changes "
				+ "(typo, copy tweak, a config value, a tiny well-defined fix); \"sonnet\" for typical, "
				+ "well-scoped feature or bug work (the default for most tickets); \"opus\" for complex, "
				+ "ambiguous, or high-risk work (architecture/design changes, tricky concurrency or security "
				+ "issues, large multi-system refactors).").formatted(ticketRef.strip(), guidance);
		String raw = sessionService.runSystemTurn(prompt, TIMEOUT);
		return parse(raw);
	}

	private TicketImportResult parse(String raw) {
		String cleaned = raw == null ? "" : raw.strip();
		if (cleaned.startsWith("```")) {
			cleaned = cleaned.replaceFirst("^```(json)?", "").replaceFirst("```$", "").strip();
		}
		JsonNode node;
		try {
			node = mapper.readTree(cleaned);
		} catch (RuntimeException e) {
			throw new IllegalStateException("could not parse ticket import response: " + truncate(raw));
		}
		String branchName = sanitizeBranch(node.path("branchName").asText(""));
		String promptText = node.path("prompt").asText("").strip();
		if (branchName.isBlank() || promptText.isBlank()) {
			throw new IllegalStateException("ticket import response missing branchName/prompt: " + truncate(raw));
		}
		String recommendedModel = node.path("recommendedModel").asText("").strip().toLowerCase(Locale.ROOT);
		if (!VALID_MODELS.contains(recommendedModel)) {
			recommendedModel = null;
		}
		return new TicketImportResult(branchName, promptText, recommendedModel);
	}

	private static String sanitizeBranch(String s) {
		String out = s.strip().replaceAll("[^A-Za-z0-9/_-]", "-").replaceAll("-{2,}", "-");
		out = out.replaceAll("^[-/]+", "").replaceAll("[-/]+$", "");
		return out.length() > 60 ? out.substring(0, 60) : out;
	}

	private static String truncate(String s) {
		if (s == null) {
			return "";
		}
		return s.length() > 300 ? s.substring(0, 300) + "…" : s;
	}
}
