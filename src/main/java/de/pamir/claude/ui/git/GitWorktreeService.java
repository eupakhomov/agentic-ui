package de.pamir.claude.ui.git;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Service
public class GitWorktreeService {

	private static final Logger log = LoggerFactory.getLogger(GitWorktreeService.class);

	private final GitCommandRunner git;

	public GitWorktreeService(GitCommandRunner git) {
		this.git = git;
	}

	/**
	 * Fast-forwards the local {@code baseBranch} ref to {@code origin/baseBranch} so a new
	 * worktree doesn't branch from stale code. If {@code repo}'s own checkout is on
	 * {@code baseBranch}, fetches and fast-forward-merges it (fails if that would overwrite
	 * local modifications, or the histories have diverged). Otherwise updates the local ref
	 * directly via a fetch refspec, which fails safely on a non-fast-forward without touching
	 * the working tree.
	 */
	public void syncBaseBranch(Path repo, String baseBranch) {
		String current = git.runOrThrow(repo, "rev-parse", "--abbrev-ref", "HEAD").stdout().strip();
		if (current.equals(baseBranch)) {
			git.runOrThrow(repo, "fetch", "origin", baseBranch);
			var merged = git.run(repo, "merge", "--ff-only", "origin/" + baseBranch);
			if (!merged.ok()) {
				throw new GitException("could not fast-forward " + baseBranch + " from origin: " + merged.stderr());
			}
		} else {
			var fetched = git.run(repo, "fetch", "origin", baseBranch + ":" + baseBranch);
			if (!fetched.ok()) {
				throw new GitException("could not update " + baseBranch + " from origin: " + fetched.stderr());
			}
		}
	}

	/**
	 * Creates a worktree at {@code worktreePath} on {@code branch}. A new branch is
	 * created from {@code baseBranch}; an existing branch is checked out as-is (never
	 * reset). Fails if the branch is already checked out in another worktree.
	 */
	public void createWorktree(Path repo, Path worktreePath, String branch, String baseBranch) {
		var created = git.run(repo, "worktree", "add", worktreePath.toString(), "-b", branch, baseBranch);
		if (created.ok()) {
			return;
		}
		if (created.stderr().contains("already exists")) {
			// branch exists: check it out instead of resetting it
			git.runOrThrow(repo, "worktree", "add", worktreePath.toString(), branch);
			log.info("checked out existing branch {} into {}", branch, worktreePath);
			return;
		}
		throw new GitException("worktree add failed: " + created.stderr());
	}

	/** Paths from `git status --porcelain`; empty = clean. */
	public List<String> dirtyFiles(Path worktree) {
		if (!Files.isDirectory(worktree)) {
			return List.of();
		}
		var status = git.runOrThrow(worktree, "status", "--porcelain");
		return status.stdout().isBlank() ? List.of() : status.stdout().lines().toList();
	}

	public void commitAll(Path worktree, String message) {
		git.runOrThrow(worktree, "add", "-A");
		git.runOrThrow(worktree, "commit", "-m", message);
	}

	public void stashAll(Path worktree, String label) {
		git.runOrThrow(worktree, "stash", "push", "--include-untracked", "-m", label);
	}

	public void removeWorktree(Path repo, Path worktreePath) {
		if (Files.isDirectory(worktreePath)) {
			git.runOrThrow(repo, "worktree", "remove", "--force", worktreePath.toString());
		}
		git.run(repo, "worktree", "prune");
	}

	public List<String> localBranches(Path repo) {
		var result = git.runOrThrow(repo, "for-each-ref", "refs/heads", "--format=%(refname:short)");
		return result.stdout().isBlank() ? List.of() : result.stdout().lines().toList();
	}

	public String defaultBranch(Path repo) {
		return pickDefaultBranch(localBranches(repo));
	}

	/**
	 * "main" if present, else "master", else the first local branch (alphabetical — could be
	 * any stray local/feature branch, so this last resort is a guess, not a real default),
	 * else "main" (worktree creation then fails with a clear git error).
	 *
	 * <p>Package-private (not private): unit-tested directly against a plain branch list,
	 * without a real git checkout — mirrors {@code frontend/src/protocol.ts}'s
	 * {@code pickDefaultBranch}, which the two session-creation dialogs also call instead of
	 * duplicating this choice client-side.
	 */
	static String pickDefaultBranch(List<String> branches) {
		if (branches.contains("main")) {
			return "main";
		}
		if (branches.contains("master")) {
			return "master";
		}
		return branches.isEmpty() ? "main" : branches.get(0);
	}

	public record RepoInfo(String name, String path) {
	}

	/** Git repos directly under a folder — the ecosystem/service picker (MetaController) and 7.4's list_services tool. */
	public List<RepoInfo> findRepos(Path root) {
		List<RepoInfo> repos = new ArrayList<>();
		if (root == null || !Files.isDirectory(root)) {
			return repos;
		}
		try (Stream<Path> children = Files.list(root)) {
			children.filter(c -> Files.exists(c.resolve(".git"))).sorted()
					.forEach(c -> repos.add(new RepoInfo(c.getFileName().toString(), c.toString())));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return repos;
	}

	/**
	 * {@code servicePath} is what identifies a unit of work (memory/discovery/orchestration key on
	 * it, and it's the session's cwd); {@code repoPath} is the nearest enclosing git root (worktree
	 * ops key on it). Equal for polyrepo. See docs/plan/phase-11-monorepo.md.
	 */
	public record ServiceInfo(String name, String servicePath, String repoPath) {
	}

	/**
	 * Every service under {@code ecosystemRoot}: layout (a) — the root itself is a git repo, so
	 * {@link ServiceDetector} runs on it directly and every result's {@code repoPath} is the root;
	 * layout (b)/polyrepo — one {@link ServiceDetector} pass per direct-child repo (via {@link
	 * #findRepos}), each result's {@code repoPath} the child. A repo with no detected workspace
	 * folders contributes exactly one service, itself. {@code name} is {@code servicePath} relative
	 * to {@code ecosystemRoot}, forward-slashed (decision 7) — polyrepo yields today's bare
	 * basenames.
	 */
	public List<ServiceInfo> findServices(Path ecosystemRoot, List<String> fallbackGlobs) {
		List<ServiceInfo> services = new ArrayList<>();
		if (ecosystemRoot == null || !Files.isDirectory(ecosystemRoot)) {
			return services;
		}
		if (Files.exists(ecosystemRoot.resolve(".git"))) {
			addServicesForRepo(services, ecosystemRoot, ecosystemRoot, fallbackGlobs);
			return services;
		}
		for (RepoInfo repo : findRepos(ecosystemRoot)) {
			addServicesForRepo(services, Path.of(repo.path()), ecosystemRoot, fallbackGlobs);
		}
		return services;
	}

	/**
	 * {@code servicePath} is either {@code repoRoot} itself (always allowed — the polyrepo /
	 * whole-repo case) or one of {@link #findServices}'s results under {@code ecosystemRoot} —
	 * shared by {@code SessionConfigFactory} (session creation) and {@code ServiceDiscoveryService}
	 * (manual rediscover/update), both of which need the same "is this a real, known service"
	 * check (docs/plan/phase-11-monorepo.md Step 5). {@code ecosystemRoot} may be null/blank
	 * (nothing configured) — then only the repo-root case passes.
	 */
	public boolean isKnownService(Path ecosystemRoot, List<String> fallbackGlobs, Path repoRoot, Path servicePath) {
		Path normalizedService = servicePath.toAbsolutePath().normalize();
		if (normalizedService.equals(repoRoot.toAbsolutePath().normalize())) {
			return true;
		}
		if (ecosystemRoot == null) {
			return false;
		}
		return findServices(ecosystemRoot, fallbackGlobs).stream()
				.map(info -> Path.of(info.servicePath()).toAbsolutePath().normalize())
				.anyMatch(normalizedService::equals);
	}

	private void addServicesForRepo(List<ServiceInfo> out, Path repoRoot, Path ecosystemRoot,
									 List<String> fallbackGlobs) {
		List<Path> detected = ServiceDetector.detect(repoRoot, fallbackGlobs);
		if (detected.isEmpty()) {
			out.add(new ServiceInfo(relativeName(ecosystemRoot, repoRoot), repoRoot.toString(), repoRoot.toString()));
			return;
		}
		for (Path service : detected) {
			// submodule edge: a detected folder with its own .git is its own repo, not a service of
			// this one (docs/plan/phase-11-monorepo.md Step 1)
			Path effectiveRepoRoot = Files.exists(service.resolve(".git")) ? service : repoRoot;
			out.add(new ServiceInfo(relativeName(ecosystemRoot, service), service.toString(),
					effectiveRepoRoot.toString()));
		}
	}

	/**
	 * {@code target} relative to {@code ecosystemRoot}, forward-slashed (decision 7) — falls back
	 * to {@code target}'s own folder name when they're equal (relativize would otherwise yield ""):
	 * layout (a) degenerates to this when its repo turns out to have no workspace markers, so the
	 * "one service = root" case still gets a sensible name instead of a blank picker entry.
	 */
	private static String relativeName(Path ecosystemRoot, Path target) {
		Path root = ecosystemRoot.toAbsolutePath().normalize();
		Path absTarget = target.toAbsolutePath().normalize();
		if (root.equals(absTarget)) {
			return absTarget.getFileName() == null ? "" : absTarget.getFileName().toString();
		}
		return root.relativize(absTarget).toString().replace(java.io.File.separatorChar, '/');
	}

	/** Walks up from {@code servicePath} until a {@code .git} is found; empty if none before the root. */
	public Optional<Path> repoRootOf(Path servicePath) {
		Path current = servicePath == null ? null : servicePath.toAbsolutePath().normalize();
		while (current != null) {
			if (Files.exists(current.resolve(".git"))) {
				return Optional.of(current);
			}
			current = current.getParent();
		}
		return Optional.empty();
	}

	/**
	 * SHA of the last commit touching {@code servicePath}'s subtree within {@code repo} — the
	 * staleness gate for a service's discovery profile (decision 6: per-subtree, not {@code HEAD},
	 * so an unrelated package's commit doesn't invalidate every other package's profile). {@code
	 * null} on failure or an empty repo, matching {@code rev-parse HEAD}'s existing null-means-always
	 * -regenerate contract.
	 */
	public String lastCommitTouching(Path repo, Path servicePath) {
		Path relPath = repo.toAbsolutePath().normalize().relativize(servicePath.toAbsolutePath().normalize());
		String rel = relPath.toString().isEmpty() ? "." : relPath.toString().replace(java.io.File.separatorChar, '/');
		var result = git.run(repo, "log", "-1", "--format=%H", "--", rel);
		return result.ok() && !result.stdout().isBlank() ? result.stdout().strip() : null;
	}
}
