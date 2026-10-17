package de.pamir.agentic.ui.web;

import de.pamir.agentic.ui.git.ChangesService;
import de.pamir.agentic.ui.session.SessionEntity;
import de.pamir.agentic.ui.session.SessionRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.UUID;

/**
 * The changes panel's read-only view of a session's branch (phase 17 C). No write endpoints, and
 * no session-state or session-type restriction: reading a review session's checkout is the point.
 */
@RestController
@RequestMapping("/api/sessions/{id}/changes")
public class ChangesController {

	private final SessionRepository sessions;
	private final ChangesService changes;

	public ChangesController(SessionRepository sessions, ChangesService changes) {
		this.sessions = sessions;
		this.changes = changes;
	}

	@GetMapping
	public ChangesService.ChangeList list(@PathVariable UUID id) {
		SessionEntity session = sessions.get(id);
		return changes.list(worktree(session), session.baseBranch());
	}

	@GetMapping("/file")
	public ChangesService.FileView file(@PathVariable UUID id, @RequestParam String path) {
		SessionEntity session = sessions.get(id);
		return changes.file(worktree(session), session.baseBranch(), path);
	}

	private static Path worktree(SessionEntity session) {
		if (session.worktreePath() == null) {
			throw new IllegalStateException("session has no worktree yet");
		}
		return Path.of(session.worktreePath());
	}
}
