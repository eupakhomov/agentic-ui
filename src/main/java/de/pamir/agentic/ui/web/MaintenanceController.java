package de.pamir.agentic.ui.web;

import de.pamir.agentic.ui.config.AppProperties;
import de.pamir.agentic.ui.git.GitWorktreeService;
import de.pamir.agentic.ui.integration.GraphifyService;
import de.pamir.agentic.ui.session.SessionEntity;
import de.pamir.agentic.ui.session.SessionRepository;
import de.pamir.agentic.ui.session.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Orphan-worktree inspection and explicit cleanup — never deletes silently. */
@RestController
@RequestMapping("/api/maintenance")
public class MaintenanceController {

	private static final Logger log = LoggerFactory.getLogger(MaintenanceController.class);

	private final AppProperties props;
	private final SessionRepository sessions;
	private final GitWorktreeService worktrees;
	private final GraphifyService graphify;

	public MaintenanceController(AppProperties props, SessionRepository sessions, GitWorktreeService worktrees,
								 GraphifyService graphify) {
		this.props = props;
		this.sessions = sessions;
		this.worktrees = worktrees;
		this.graphify = graphify;
	}

	@GetMapping("/orphans")
	public List<String> orphans() {
		Path root = Path.of(props.worktreeRoot());
		if (!Files.isDirectory(root)) {
			return List.of();
		}
		Set<String> active = sessions.findAll().stream()
				.filter(s -> s.state() != SessionState.CLOSED && s.state() != SessionState.FAILED)
				.map(SessionEntity::worktreePath)
				.collect(Collectors.toSet());
		try (Stream<Path> children = Files.list(root)) {
			return children
					.filter(Files::isDirectory)
					.filter(p -> !p.getFileName().toString().startsWith("."))
					// the system session's scratch dirs live at _system/<id> — the container itself
					// never matches a session's worktreePath, so it would always read as an orphan
					.filter(p -> !p.getFileName().toString().equals("_system"))
					.map(Path::toString)
					.filter(p -> !active.contains(p))
					.sorted()
					.toList();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Removes orphan worktrees plus leftover per-session graph dirs under {@code <worktree-root>/
	 * .graphify/} (docs/plan/phase-13-graphify.md decision 6 — dot-prefixed, so {@link #orphans}
	 * never lists them as worktrees) whose session is closed, failed or gone.
	 */
	@PostMapping("/orphans/clean")
	public List<String> clean() {
		List<String> removed = new ArrayList<>(orphans());
		for (String orphan : orphans()) {
			Path worktree = Path.of(orphan);
			// find the repo this worktree belongs to via its session row if any, else best-effort by all known repos
			String repo = sessions.findAll().stream()
					.filter(s -> s.worktreePath().equals(orphan))
					.map(SessionEntity::repoPath)
					.findFirst()
					.orElse(props.repoPath());
			try {
				worktrees.removeWorktree(Path.of(repo), worktree);
			} catch (RuntimeException e) {
				log.warn("orphan {} removal via git failed ({}), deleting directory", orphan, e.getMessage());
				deleteRecursively(worktree);
			}
		}
		Set<UUID> activeIds = sessions.findAll().stream()
				.filter(s -> s.state() != SessionState.CLOSED && s.state() != SessionState.FAILED)
				.map(SessionEntity::id)
				.collect(Collectors.toSet());
		removed.addAll(graphify.cleanOrphanGraphs(activeIds));
		return removed;
	}

	private void deleteRecursively(Path dir) {
		try (Stream<Path> walk = Files.walk(dir)) {
			walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (IOException ignored) {
				}
			});
		} catch (IOException ignored) {
		}
	}
}
