package de.pamir.claude.ui.web;

import de.pamir.claude.ui.git.GitWorktreeService;
import de.pamir.claude.ui.journal.EventJournal;
import de.pamir.claude.ui.session.SessionEntity;
import de.pamir.claude.ui.session.SessionRepository;
import de.pamir.claude.ui.session.SessionState;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/usage")
public class UsageController {

	/** Sessions past this age since their last state change are surfaced for cleanup. */
	private static final Duration STALE_AFTER = Duration.ofDays(3);

	public record StaleSession(UUID id, String name, String branch, String state, Instant updatedAt,
							   boolean worktreeExists, boolean dirty) {
	}

	private final EventJournal journal;
	private final SessionRepository sessions;
	private final GitWorktreeService worktrees;

	public UsageController(EventJournal journal, SessionRepository sessions, GitWorktreeService worktrees) {
		this.journal = journal;
		this.sessions = sessions;
		this.worktrees = worktrees;
	}

	@GetMapping
	public List<EventJournal.TurnUsage> usage(@RequestParam(defaultValue = "6") int months) {
		int clamped = Math.max(1, Math.min(24, months));
		Instant since = ZonedDateTime.now().minus(Period.ofMonths(clamped)).toInstant();
		return journal.usageSince(since);
	}

	@GetMapping("/stale-sessions")
	public List<StaleSession> staleSessions() {
		Instant cutoff = Instant.now().minus(STALE_AFTER);
		return sessions.findByStates(List.of(SessionState.PARKED, SessionState.CRASHED, SessionState.FAILED))
				.stream()
				.filter(s -> !"system".equals(s.kind()))
				.filter(s -> s.updatedAt() != null && s.updatedAt().isBefore(cutoff))
				.map(this::toStaleSession)
				.toList();
	}

	private StaleSession toStaleSession(SessionEntity s) {
		Path worktree = Path.of(s.worktreePath());
		boolean exists = Files.isDirectory(worktree);
		boolean dirty = exists && !worktrees.dirtyFiles(worktree).isEmpty();
		return new StaleSession(s.id(), s.name(), s.branch(), s.state().name(), s.updatedAt(), exists, dirty);
	}
}
