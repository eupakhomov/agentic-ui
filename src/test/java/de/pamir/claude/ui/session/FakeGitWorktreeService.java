package de.pamir.claude.ui.session;

import de.pamir.claude.ui.git.GitWorktreeService;

import java.nio.file.Path;
import java.util.List;

/**
 * {@link GitWorktreeService} double that never shells out to git — dirtyFiles is settable per
 * test, the mutating operations are no-ops. See docs/plan/phase-9-production-hardening.md T2.
 */
final class FakeGitWorktreeService extends GitWorktreeService {

	private List<String> dirty = List.of();

	FakeGitWorktreeService() {
		super(null);
	}

	void setDirtyFiles(List<String> dirty) {
		this.dirty = dirty;
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
}
