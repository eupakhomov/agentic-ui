package de.pamir.claude.ui.session;

import de.pamir.claude.ui.git.GitOpsService;
import de.pamir.claude.ui.journal.JournalPublisher;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * Review sessions' single write path back to GitHub (docs/plan/phase-15-review-sessions.md
 * proposal 9): posts a whole review (summary + inline file/line comments) atomically via {@code gh
 * api}. Same in-process MCP server as memory/orchestration (decision 12a,
 * phase-5.3-memory-reflection.md) — deliberately NOT pre-approved in {@code allowedTools} (unlike
 * the read-only memory tools), so every call passes the normal tool-permission prompt, which is
 * the human gate the design relies on instead of any sidecar-level enforcement.
 */
@Component
public class ReviewMcpTools {

	public record ReviewCommentInput(String path, int line, String side, Integer startLine, String body) {
	}

	private final SessionRepository sessions;
	private final GitOpsService gitOps;
	private final JournalPublisher journalPublisher;
	private final ObjectMapper mapper;

	public ReviewMcpTools(SessionRepository sessions, GitOpsService gitOps, JournalPublisher journalPublisher,
						   ObjectMapper mapper) {
		this.sessions = sessions;
		this.gitOps = gitOps;
		this.journalPublisher = journalPublisher;
		this.mapper = mapper;
	}

	@McpTool(name = "submit_pr_review",
			annotations = @McpTool.McpAnnotations(title = "Submit PR review", readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = true),
			description = "Submit your completed code review to GitHub as a single PR review: a summary plus "
					+ "optional inline file/line comments, posted atomically. Only usable from a review session. "
					+ "If no PR is attached to this session, resolves one from the reviewed branch, or errors "
					+ "readably if none exists — in that case, report your findings in the transcript instead.")
	public String submitPrReview(
			@McpToolParam(required = true, description = "Your session id, given in your system prompt")
			String sessionId,
			@McpToolParam(required = false, description = "COMMENT (default), REQUEST_CHANGES, or APPROVE")
			String event,
			@McpToolParam(required = true, description = "The review summary")
			String body,
			@McpToolParam(required = false, description = "Inline comments: [{path, line, side?, startLine?, body}]; side defaults to RIGHT")
			List<ReviewCommentInput> comments) {
		SessionEntity session = sessionOf(sessionId);
		if (!"review".equals(session.sessionType())) {
			throw new IllegalStateException("submit_pr_review is only usable from a review session");
		}
		String resolvedEvent = event == null || event.isBlank() ? "COMMENT" : event.toUpperCase(java.util.Locale.ROOT);
		if (!java.util.Set.of("COMMENT", "REQUEST_CHANGES", "APPROVE").contains(resolvedEvent)) {
			throw new IllegalArgumentException("event must be one of COMMENT, REQUEST_CHANGES, APPROVE: " + event);
		}
		String prUrl = resolvePrUrl(session);
		GitOpsService.PrRef pr = GitOpsService.parsePrUrl(prUrl);
		List<GitOpsService.ReviewComment> reviewComments = (comments == null ? List.<ReviewCommentInput>of() : comments)
				.stream()
				.map(c -> new GitOpsService.ReviewComment(c.path(), c.line(), c.side(), c.startLine(), c.body()))
				.toList();
		Path worktree = Path.of(session.worktreePath());
		gitOps.submitPrReview(worktree, pr, resolvedEvent, body, reviewComments);
		ObjectNode payload = mapper.createObjectNode()
				.put("event", resolvedEvent)
				.put("commentCount", reviewComments.size())
				.put("prUrl", prUrl);
		journalPublisher.record(session.id(), "pr_review_submitted", payload);
		return "submitted " + resolvedEvent + " review with " + reviewComments.size() + " inline comment(s) to " + prUrl;
	}

	/** {@code session.prUrl} when attached, else lazily resolved from the reviewed branch (proposal 1's fallback). */
	private String resolvePrUrl(SessionEntity session) {
		if (session.prUrl() != null && !session.prUrl().isBlank()) {
			return session.prUrl();
		}
		Optional<GitOpsService.PrInfo> resolved = gitOps.resolvePr(Path.of(session.worktreePath()), session.branch());
		GitOpsService.PrInfo pr = resolved.orElseThrow(() -> new IllegalStateException(
				"no PR found for branch '" + session.branch() + "' — report your findings in the transcript instead"));
		sessions.attachPr(session.id(), pr.url(), gitOps.headSha(Path.of(session.worktreePath())));
		return pr.url();
	}

	private SessionEntity sessionOf(String sessionId) {
		UUID id;
		try {
			id = UUID.fromString(sessionId);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("sessionId is not a valid session id: " + sessionId);
		}
		try {
			return sessions.get(id);
		} catch (NoSuchElementException e) {
			throw new NoSuchElementException("no session found for sessionId: " + sessionId);
		}
	}
}
