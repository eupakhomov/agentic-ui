package de.pamir.claude.ui.integration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import de.pamir.claude.ui.config.AppProperties;
import de.pamir.claude.ui.session.SessionService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Locale;

/**
 * Generates a branch name + kickoff prompt from a ticket, via the singleton system session
 * (Haiku + the Linear MCP server). Provider-specific today (Linear only); named generically
 * since "ticket system" naming, not "linear", is what should survive a second provider later.
 */
@Service
public class TicketImportService {

	private static final Duration TIMEOUT = Duration.ofSeconds(45);

	public record TicketImportResult(String branchName, String prompt) {
	}

	private final SessionService sessionService;
	private final AppProperties props;
	private final ObjectMapper mapper;

	public TicketImportService(SessionService sessionService, AppProperties props, ObjectMapper mapper) {
		this.sessionService = sessionService;
		this.props = props;
		this.mapper = mapper;
	}

	public boolean enabled() {
		return (props.linearApiKey() != null && !props.linearApiKey().isBlank()) || props.linearOAuth();
	}

	public TicketImportResult importTicket(String ticketRef) {
		if (!enabled()) {
			throw new IllegalStateException(
					"Linear integration is not configured (set CLAUDE_UI_LINEAR_API_KEY or CLAUDE_UI_LINEAR_OAUTH)");
		}
		if (ticketRef == null || ticketRef.isBlank()) {
			throw new IllegalArgumentException("ticketRef is required");
		}
		String prompt = ("You have access to Linear via MCP tools. Fetch the Linear issue referenced by \"%s\" "
				+ "(it may be a short identifier like ENG-123 or a full Linear issue URL). "
				+ "Then respond with ONLY a single JSON object — no markdown fences, no commentary — "
				+ "of the form: {\"branchName\": \"kebab-case-git-safe-branch-name\", \"prompt\": \"a clear, "
				+ "actionable initial instruction for an engineer/agent implementing this ticket, including its "
				+ "key requirements\"}. branchName must be short, lowercase, kebab-case, git-ref-safe, and "
				+ "include the ticket identifier, e.g. \"eng-123-fix-login-bug\".").formatted(ticketRef.strip());
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
		return new TicketImportResult(branchName, promptText);
	}

	private static String sanitizeBranch(String s) {
		String out = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/_-]", "-").replaceAll("-{2,}", "-");
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
