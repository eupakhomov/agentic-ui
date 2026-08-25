package de.pamir.claude.ui.session;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import de.pamir.claude.ui.git.GitOpsService;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Drafts a commit message / PR title+description via the singleton system session (same
 * runSystemTurn mechanism TicketImportService uses to turn a ticket into a branch name).
 */
@Service
public class GitAssistService {

	private static final Duration TIMEOUT = Duration.ofSeconds(45);
	private static final int MAX_DIFF_CHARS = 20_000;
	private static final Pattern TICKET_PATTERN = Pattern.compile("[A-Za-z]{2,10}-\\d{1,6}");

	public record CommitSuggestion(String message) {
	}

	public record PrSuggestion(String title, String body) {
	}

	private final SessionService sessionService;
	private final SessionRepository sessions;
	private final GitOpsService gitOps;
	private final ObjectMapper mapper;

	public GitAssistService(SessionService sessionService, SessionRepository sessions, GitOpsService gitOps,
							ObjectMapper mapper) {
		this.sessionService = sessionService;
		this.sessions = sessions;
		this.gitOps = gitOps;
		this.mapper = mapper;
	}

	public CommitSuggestion suggestCommitMessage(UUID sessionId) {
		SessionEntity session = sessions.get(sessionId);
		Path worktree = Path.of(session.worktreePath());
		String diff = gitOps.diff(worktree);
		if (diff.isBlank()) {
			throw new IllegalStateException("no uncommitted changes to describe");
		}
		String ticketRef = resolveTicketRef(session);
		String prefixInstruction = ticketRef != null
				? "Prefix the subject with \"" + ticketRef + ": \" (exactly, including the colon and one space). "
				: "";
		String prompt = ("Write a git commit message subject line for the following diff of currently "
				+ "staged/unstaged changes in a worktree. Use imperative mood (e.g. \"Fix\", \"Add\", not "
				+ "\"Fixed\"/\"Added\"), describe what changed and why at a glance, and keep it under 72 "
				+ "characters excluding any ticket prefix. %sRespond with ONLY a single JSON object — no markdown "
				+ "fences, no commentary — of the form {\"message\": \"...\"}.\n\nDiff:\n```diff\n%s\n```")
				.formatted(prefixInstruction, truncate(diff, MAX_DIFF_CHARS));
		JsonNode node = parseJson(sessionService.runSystemTurn(prompt, TIMEOUT), "commit message");
		String message = node.path("message").asText("").strip();
		if (message.isBlank()) {
			throw new IllegalStateException("commit message suggestion was empty");
		}
		return new CommitSuggestion(message);
	}

	public PrSuggestion suggestPr(UUID sessionId) {
		SessionEntity session = sessions.get(sessionId);
		Path worktree = Path.of(session.worktreePath());
		List<GitOpsService.LogEntry> log = gitOps.logVsBase(worktree, session.baseBranch(), 20);
		String diff = gitOps.diffVsBase(worktree, session.baseBranch());
		if (log.isEmpty() && diff.isBlank()) {
			throw new IllegalStateException("nothing to describe yet — commit or make some changes first");
		}
		String ticketRef = resolveTicketRef(session);
		String logSection = log.isEmpty()
				? "(no commits ahead of the base branch yet; describing the current diff instead)\n"
				: "Commits on this branch, newest first:\n"
						+ log.stream().map(e -> "- " + e.subject()).collect(Collectors.joining("\n")) + "\n";
		String ticketNote = ticketRef != null
				? "This branch implements ticket " + ticketRef + "; you don't need to repeat the ticket ID in the "
						+ "title or body unless it adds clarity. "
				: "";
		String prompt = ("Draft a GitHub pull request description for the following branch, based on its commits "
				+ "and its diff against the base branch.\n\n" + logSection + "\nDiff vs base branch:\n```diff\n%s"
				+ "\n```\n\n%sRespond with ONLY a single JSON object — no markdown fences, no commentary — of the "
				+ "form {\"title\": \"a concise PR title\", \"body\": \"a short GitHub-flavored markdown "
				+ "description: a 1-2 sentence summary followed by a bullet list of the key changes, no "
				+ "top-level heading\"}.").formatted(truncate(diff, MAX_DIFF_CHARS), ticketNote);
		JsonNode node = parseJson(sessionService.runSystemTurn(prompt, TIMEOUT), "PR description");
		String body = node.path("body").asText("").strip();
		String llmTitle = node.path("title").asText("").strip();
		// deterministic: a PR's title is the newest commit's subject when one exists, rather than a
		// separately-generated title — only fall back to the model's own title with no commits yet
		String title = !log.isEmpty() ? log.get(0).subject() : llmTitle;
		if (title.isBlank() || body.isBlank()) {
			throw new IllegalStateException("PR suggestion missing title/body");
		}
		return new PrSuggestion(title, body);
	}

	private String resolveTicketRef(SessionEntity session) {
		if (session.ticketRef() != null && !session.ticketRef().isBlank()) {
			return session.ticketRef();
		}
		if (session.branch() == null) {
			return null;
		}
		Matcher m = TICKET_PATTERN.matcher(session.branch());
		return m.find() ? m.group().toUpperCase(Locale.ROOT) : null;
	}

	private JsonNode parseJson(String raw, String what) {
		try {
			return mapper.readTree(stripFences(raw));
		} catch (RuntimeException e) {
			throw new IllegalStateException("could not parse " + what + " response: " + truncate(raw, 300));
		}
	}

	private static String stripFences(String raw) {
		String cleaned = raw == null ? "" : raw.strip();
		if (cleaned.startsWith("```")) {
			cleaned = cleaned.replaceFirst("^```(json)?", "").replaceFirst("```$", "").strip();
		}
		return cleaned;
	}

	private static String truncate(String s, int max) {
		if (s == null) {
			return "";
		}
		return s.length() > max ? s.substring(0, max) + "\n… (truncated)" : s;
	}
}
