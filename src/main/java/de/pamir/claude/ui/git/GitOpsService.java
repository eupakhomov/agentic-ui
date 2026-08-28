package de.pamir.claude.ui.git;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Read/write git operations on a session worktree, plus PR creation/status via gh. */
@Service
public class GitOpsService {

	private static final Logger log = LoggerFactory.getLogger(GitOpsService.class);

	/**
	 * aheadOfBase: commits on this branch not on baseBranch (-1 if it couldn't be computed,
	 * e.g. baseBranch no longer exists — callers should treat that as "unknown", not "none").
	 */
	public record GitStatus(String branch, List<String> dirty, String upstream, int ahead, int behind, int aheadOfBase) {
	}

	public record LogEntry(String hash, String subject, String author, String date) {
	}

	public enum PrCheckStatus { PENDING, SUCCESS, FAILURE, MERGED, CLOSED, ERROR }

	public record PrCheckResult(PrCheckStatus status, String headSha) {
	}

	private static final Set<String> FAILING_CONCLUSIONS =
			Set.of("FAILURE", "CANCELLED", "TIMED_OUT", "ACTION_REQUIRED", "STARTUP_FAILURE");
	private static final Set<String> FAILING_STATES = Set.of("FAILURE", "ERROR");

	private final GitCommandRunner git;
	private final GitWorktreeService worktrees;
	private final ObjectMapper mapper;

	public GitOpsService(GitCommandRunner git, GitWorktreeService worktrees, ObjectMapper mapper) {
		this.git = git;
		this.worktrees = worktrees;
		this.mapper = mapper;
	}

	public GitStatus status(Path worktree, String baseBranch) {
		String branch = git.runOrThrow(worktree, "branch", "--show-current").stdout();
		List<String> dirty = worktrees.dirtyFiles(worktree);
		String upstream = null;
		int ahead = 0;
		int behind = 0;
		var up = git.run(worktree, "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}");
		if (up.ok()) {
			upstream = up.stdout();
			var counts = git.run(worktree, "rev-list", "--left-right", "--count", "@{upstream}...HEAD");
			if (counts.ok()) {
				String[] parts = counts.stdout().split("\\s+");
				behind = Integer.parseInt(parts[0]);
				ahead = Integer.parseInt(parts[1]);
			}
		}
		// commits not on baseBranch: what a fresh push/PR would actually carry, independent of
		// whether this branch has ever been pushed (ahead/behind above is upstream-relative only)
		int aheadOfBase = -1;
		var baseCount = git.run(worktree, "rev-list", "--count", baseBranch + "..HEAD");
		if (baseCount.ok()) {
			aheadOfBase = Integer.parseInt(baseCount.stdout());
		}
		return new GitStatus(branch, dirty, upstream, ahead, behind, aheadOfBase);
	}

	/** Tracked changes vs HEAD plus content of untracked files. */
	public String diff(Path worktree) {
		StringBuilder out = new StringBuilder(git.runOrThrow(worktree, "diff", "HEAD").stdout());
		var untracked = git.runOrThrow(worktree, "ls-files", "--others", "--exclude-standard");
		for (String file : untracked.stdout().isBlank() ? List.<String>of() : untracked.stdout().lines().toList()) {
			var d = git.run(worktree, "diff", "--no-index", "/dev/null", file);
			out.append('\n').append(d.stdout());
		}
		return out.toString();
	}

	/** Diff of everything this branch would carry into a PR against baseBranch (merge-base diff). */
	public String diffVsBase(Path worktree, String baseBranch) {
		var result = git.run(worktree, "diff", baseBranch + "...HEAD");
		return result.ok() ? result.stdout() : "";
	}

	public List<LogEntry> log(Path worktree, int limit) {
		return parseLog(git.runOrThrow(worktree, "log", "--format=%h%x1f%s%x1f%an%x1f%ad", "--date=relative",
				"-" + limit));
	}

	/** Commits on this branch not on baseBranch, newest first — what a PR against it would contain. */
	public List<LogEntry> logVsBase(Path worktree, String baseBranch, int limit) {
		var result = git.run(worktree, "log", baseBranch + "..HEAD", "--format=%h%x1f%s%x1f%an%x1f%ad",
				"--date=relative", "-" + limit);
		return result.ok() ? parseLog(result) : List.of();
	}

	private List<LogEntry> parseLog(GitCommandRunner.GitResult result) {
		if (result.stdout().isBlank()) {
			return List.of();
		}
		return result.stdout().lines().map(line -> {
			String[] parts = line.split("\\u001f", -1);
			return new LogEntry(parts[0], parts[1], parts[2], parts[3]);
		}).toList();
	}

	public void commitAll(Path worktree, String message) {
		worktrees.commitAll(worktree, message);
	}

	public String headSha(Path worktree) {
		return git.runOrThrow(worktree, "rev-parse", "HEAD").stdout();
	}

	public String push(Path worktree, String branch) {
		var remotes = git.runOrThrow(worktree, "remote");
		if (remotes.stdout().isBlank()) {
			throw new GitException("this repository has no git remote configured");
		}
		git.runOrThrow(worktree, "push", "-u", "origin", branch);
		return "pushed " + branch + " to origin";
	}

	/** Pushes, then creates a PR via the gh CLI (uses the user's gh auth). Returns the PR URL. */
	public String createPullRequest(Path worktree, String branch, String title, String body) {
		push(worktree, branch);
		try {
			Process process = new ProcessBuilder("gh", "pr", "create",
					"--title", title, "--body", body, "--head", branch)
					.directory(worktree.toFile()).start();
			String stdout = new String(process.getInputStream().readAllBytes()).strip();
			String stderr = new String(process.getErrorStream().readAllBytes()).strip();
			if (!process.waitFor(60, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				throw new IllegalStateException("gh pr create timed out");
			}
			if (process.exitValue() != 0) {
				throw new IllegalStateException("gh pr create failed: " + (stderr.isBlank() ? stdout : stderr));
			}
			// gh prints the PR URL as the last stdout line
			return stdout.lines().reduce((a, b) -> b).orElse(stdout);
		} catch (IOException e) {
			throw new IllegalStateException(
					"gh CLI not available (" + e.getMessage() + "); install gh and run `gh auth login`");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while creating PR");
		}
	}

	/** Aggregate check-suite + merge status for a PR, via the same ambient gh auth used to create it. */
	public PrCheckResult checkPrStatus(Path worktree, String prUrl) {
		try {
			Process process = new ProcessBuilder("gh", "pr", "view", prUrl,
					"--json", "state,headRefOid,statusCheckRollup")
					.directory(worktree.toFile()).start();
			String stdout = new String(process.getInputStream().readAllBytes()).strip();
			String stderr = new String(process.getErrorStream().readAllBytes()).strip();
			if (!process.waitFor(30, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				throw new IllegalStateException("gh pr view timed out");
			}
			if (process.exitValue() != 0) {
				throw new IllegalStateException("gh pr view failed: " + (stderr.isBlank() ? stdout : stderr));
			}
			JsonNode json = mapper.readTree(stdout);
			String headSha = json.path("headRefOid").asText(null);
			String state = json.path("state").asText("OPEN");
			if ("MERGED".equals(state)) {
				return new PrCheckResult(PrCheckStatus.MERGED, headSha);
			}
			if ("CLOSED".equals(state)) {
				return new PrCheckResult(PrCheckStatus.CLOSED, headSha);
			}
			return new PrCheckResult(aggregateChecks(json.path("statusCheckRollup")), headSha);
		} catch (IOException | RuntimeException e) {
			log.warn("gh pr view failed for {}: {}", prUrl, e.getMessage());
			return new PrCheckResult(PrCheckStatus.ERROR, null);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new PrCheckResult(PrCheckStatus.ERROR, null);
		}
	}

	/**
	 * Each rollup entry is either a CheckRun (status/conclusion) or a legacy StatusContext
	 * (state only). An empty rollup means no checks have reported yet — treated as pending
	 * rather than success, since a repo with real CI will populate it shortly.
	 */
	private PrCheckStatus aggregateChecks(JsonNode rollup) {
		if (!rollup.isArray() || rollup.isEmpty()) {
			return PrCheckStatus.PENDING;
		}
		boolean anyPending = false;
		for (JsonNode check : rollup) {
			String conclusion = check.path("conclusion").asText(null);
			if (conclusion != null) {
				if (FAILING_CONCLUSIONS.contains(conclusion)) {
					return PrCheckStatus.FAILURE;
				}
				if (!"COMPLETED".equals(check.path("status").asText(null))) {
					anyPending = true;
				}
				continue;
			}
			String state = check.path("state").asText(null);
			if (FAILING_STATES.contains(state)) {
				return PrCheckStatus.FAILURE;
			}
			if (state == null || "PENDING".equals(state) || "EXPECTED".equals(state)) {
				anyPending = true;
			}
		}
		return anyPending ? PrCheckStatus.PENDING : PrCheckStatus.SUCCESS;
	}
}
