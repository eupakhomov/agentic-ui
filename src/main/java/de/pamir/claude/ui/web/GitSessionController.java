package de.pamir.claude.ui.web;

import de.pamir.claude.ui.git.GitOpsService;
import de.pamir.claude.ui.session.GitAssistService;
import de.pamir.claude.ui.session.SessionEntity;
import de.pamir.claude.ui.session.SessionRepository;
import de.pamir.claude.ui.session.SessionState;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Git panel operations on a session's worktree. */
@RestController
@RequestMapping("/api/sessions/{id}/git")
public class GitSessionController {

	public record CommitRequest(String message) {
	}

	public record PrRequest(String title, String body) {
	}

	private final SessionRepository sessions;
	private final GitOpsService gitOps;
	private final GitAssistService assist;

	public GitSessionController(SessionRepository sessions, GitOpsService gitOps, GitAssistService assist) {
		this.sessions = sessions;
		this.gitOps = gitOps;
		this.assist = assist;
	}

	@GetMapping("/status")
	public GitOpsService.GitStatus status(@PathVariable UUID id) {
		return gitOps.status(worktree(id, false), sessions.get(id).baseBranch());
	}

	@GetMapping("/diff")
	public Map<String, String> diff(@PathVariable UUID id) {
		return Map.of("diff", gitOps.diff(worktree(id, false)));
	}

	@GetMapping("/log")
	public List<GitOpsService.LogEntry> log(@PathVariable UUID id) {
		return gitOps.log(worktree(id, false), 20);
	}

	@PostMapping("/commit")
	public GitOpsService.GitStatus commit(@PathVariable UUID id, @RequestBody CommitRequest request) {
		if (request.message() == null || request.message().isBlank()) {
			throw new IllegalArgumentException("commit message is required");
		}
		Path worktree = worktree(id, true);
		gitOps.commitAll(worktree, request.message().strip());
		return gitOps.status(worktree, sessions.get(id).baseBranch());
	}

	@PostMapping("/commit-message/suggest")
	public GitAssistService.CommitSuggestion suggestCommitMessage(@PathVariable UUID id) {
		return assist.suggestCommitMessage(id);
	}

	@PostMapping("/pr/suggest")
	public GitAssistService.PrSuggestion suggestPr(@PathVariable UUID id) {
		return assist.suggestPr(id);
	}

	@PostMapping("/push")
	public Map<String, String> push(@PathVariable UUID id) {
		SessionEntity session = sessions.get(id);
		return Map.of("result", gitOps.push(worktree(id, true), session.branch()));
	}

	@PostMapping("/pr")
	public Map<String, String> createPr(@PathVariable UUID id, @RequestBody PrRequest request) {
		SessionEntity session = sessions.get(id);
		String title = request.title() == null || request.title().isBlank() ? session.name() : request.title().strip();
		String body = request.body() == null ? "" : request.body();
		return Map.of("url", gitOps.createPullRequest(worktree(id, true), session.branch(), title, body));
	}

	/** Writes are refused while the agent may be mid-tool-execution. */
	private Path worktree(UUID id, boolean forWrite) {
		SessionEntity session = sessions.get(id);
		if (forWrite && (session.state() == SessionState.RUNNING || session.state() == SessionState.WAITING_INPUT)) {
			throw new IllegalStateException("session is " + session.state()
					+ "; wait for the turn to finish before committing or pushing");
		}
		return Path.of(session.worktreePath());
	}
}
