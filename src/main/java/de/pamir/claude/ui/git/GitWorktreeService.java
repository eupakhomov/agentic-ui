package de.pamir.claude.ui.git;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
}
