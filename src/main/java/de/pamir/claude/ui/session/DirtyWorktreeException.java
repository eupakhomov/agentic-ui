package de.pamir.claude.ui.session;

import java.util.List;

/** Close was refused because the worktree has uncommitted changes (dirty=fail). */
public class DirtyWorktreeException extends RuntimeException {

	private final List<String> dirtyFiles;

	public DirtyWorktreeException(List<String> dirtyFiles) {
		super("worktree has uncommitted changes");
		this.dirtyFiles = dirtyFiles;
	}

	public List<String> dirtyFiles() {
		return dirtyFiles;
	}
}
