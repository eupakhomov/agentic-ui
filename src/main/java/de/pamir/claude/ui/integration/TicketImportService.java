package de.pamir.claude.ui.integration;

import tools.jackson.databind.JsonNode;
import de.pamir.claude.ui.config.AppProperties;
import de.pamir.claude.ui.config.SettingsService;
import de.pamir.claude.ui.session.SystemTurnClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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

	public record TicketImportResult(String branchName, String prompt, String recommendedModel, String ticketRef) {
	}

	public record TicketSummary(String ref, String title, String status) {
	}

	private final SystemTurnClient systemTurnClient;
	private final AppProperties props;
	private final SettingsService settings;

	public TicketImportService(SystemTurnClient systemTurnClient, AppProperties props, SettingsService settings) {
		this.systemTurnClient = systemTurnClient;
		this.props = props;
		this.settings = settings;
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
				+ "(it may be a short identifier like ENG-123 or a full Linear issue URL), along with its "
				+ "comments. Read the comment thread for any clarifications, scope changes, decisions, or "
				+ "blocking questions/answers raised after the ticket was created — the description alone may "
				+ "be stale — and factor anything relevant into the prompt you write. "
				+ "%s"
				+ "Then respond with ONLY a single JSON object — no markdown fences, no commentary — "
				+ "of the form: {\"branchName\": \"kebab-case-git-safe-branch-name\", \"prompt\": \"a clear, "
				+ "actionable initial instruction for an engineer/agent implementing this ticket, including its "
				+ "key requirements\", \"recommendedModel\": \"sonnet|opus|haiku\", \"ticketRef\": "
				+ "\"the issue's own short identifier, e.g. ENG-123, uppercased\"}. branchName must be short, "
				+ "kebab-case, git-ref-safe, and include the ticket identifier, e.g. \"ENG-123-fix-login-bug\" OR "
				+ "\"eng-123-fix-login-bug\". recommendedModel must be exactly one of \"sonnet\", \"opus\", or "
				+ "\"haiku\", chosen by the ticket's apparent complexity: \"haiku\" for trivial/mechanical changes "
				+ "(typo, copy tweak, a config value, a tiny well-defined fix); \"sonnet\" for typical, "
				+ "well-scoped feature or bug work (the default for most tickets); \"opus\" for complex, "
				+ "ambiguous, or high-risk work (architecture/design changes, tricky concurrency or security "
				+ "issues, large multi-system refactors).").formatted(ticketRef.strip(), guidance);
		return parse(systemTurnClient.json(prompt, TIMEOUT));
	}

	public List<TicketSummary> listMyTickets() {
		if (!enabled()) {
			throw new IllegalStateException(
					"Linear integration is not configured (set CLAUDE_UI_LINEAR_API_KEY, or enable OAuth in Settings)");
		}
		String prompt = "You have access to Linear via MCP tools. List up to 20 issues currently assigned to me "
				+ "(the authenticated Linear user), ordered by most recently updated first, excluding any issue "
				+ "in a completed or canceled state. Then respond with ONLY a JSON array — no markdown fences, "
				+ "no commentary — of objects of the form {\"ref\": \"ENG-123\", \"title\": \"...\", \"status\": "
				+ "\"...\"}. If there are no matching issues, respond with an empty array [].";
		return parseTickets(systemTurnClient.json(prompt, TIMEOUT));
	}

	// package-private (not private): unit-tested directly without mocking the system-session
	// dependencies parse()/parseTickets() don't touch — see docs/plan/phase-9-production-hardening.md T1
	List<TicketSummary> parseTickets(JsonNode node) {
		if (!node.isArray()) {
			node = node.path("tickets");
		}
		List<TicketSummary> tickets = new ArrayList<>();
		for (JsonNode item : node) {
			String ref = item.path("ref").asText("").strip();
			String title = item.path("title").asText("").strip();
			if (ref.isBlank() || title.isBlank()) {
				continue;
			}
			tickets.add(new TicketSummary(ref, title, item.path("status").asText("").strip()));
		}
		return tickets;
	}

	TicketImportResult parse(JsonNode node) {
		String branchName = sanitizeBranch(node.path("branchName").asText(""));
		String promptText = node.path("prompt").asText("").strip();
		if (branchName.isBlank() || promptText.isBlank()) {
			throw new IllegalStateException("ticket import response missing branchName/prompt: " + node);
		}
		String recommendedModel = node.path("recommendedModel").asText("").strip().toLowerCase(Locale.ROOT);
		if (!VALID_MODELS.contains(recommendedModel)) {
			recommendedModel = null;
		}
		String canonicalRef = node.path("ticketRef").asText("").strip().toUpperCase(Locale.ROOT);
		return new TicketImportResult(branchName, promptText, recommendedModel, canonicalRef.isBlank() ? null : canonicalRef);
	}

	static String sanitizeBranch(String s) {
		String out = s.strip().replaceAll("[^A-Za-z0-9/_-]", "-").replaceAll("-{2,}", "-");
		out = out.replaceAll("^[-/]+", "").replaceAll("[-/]+$", "");
		return out.length() > 60 ? out.substring(0, 60) : out;
	}
}
