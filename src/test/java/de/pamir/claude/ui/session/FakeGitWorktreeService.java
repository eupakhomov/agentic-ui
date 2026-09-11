package de.pamir.claude.ui.session;

import de.pamir.claude.ui.git.GitWorktreeService;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * {@link GitWorktreeService} double that never shells out to git — dirtyFiles is settable per
 * test, the mutating operations are no-ops. See docs/plan/phase-9-production-hardening.md T2.
 * {@code repoRootOf}/{@code findServices} results are settable too (default: not found/none) —
 * docs/plan/phase-11-monorepo.md Step 3's servicePath resolution.
 */
final class FakeGitWorktreeService extends GitWorktreeService {

	private List<String> dirty = List.of();
	private Path repoRootOfResult;
	private List<GitWorktreeService.ServiceInfo> knownServices = List.of();

	FakeGitWorktreeService() {
		super(null);
	}

	void setDirtyFiles(List<String> dirty) {
		this.dirty = dirty;
	}

	void setRepoRootOfResult(Path repoRoot) {
		this.repoRootOfResult = repoRoot;
	}

	void setKnownServices(List<GitWorktreeService.ServiceInfo> knownServices) {
		this.knownServices = knownServices;
	}

	@Override
	public List<String> dirtyFiles(Path worktree) {
		return dirty;
	}

	@Override
	public void commitAll(Path worktree, String message) {
		// no-op
	}

	@Override
	public void stashAll(Path worktree, String label) {
		// no-op
	}

	@Override
	public void removeWorktree(Path repo, Path worktreePath) {
		// no-op
	}

	@Override
	public void createWorktree(Path repo, Path worktreePath, String branch, String baseBranch) {
		// no-op — no real git worktree add
	}

	@Override
	public Optional<Path> repoRootOf(Path servicePath) {
		return Optional.ofNullable(repoRootOfResult);
	}

	@Override
	public List<GitWorktreeService.ServiceInfo> findServices(Path ecosystemRoot, List<String> fallbackGlobs) {
		return knownServices;
	}
}
