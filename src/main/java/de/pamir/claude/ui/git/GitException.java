package de.pamir.claude.ui.git;

/** A git invocation failed; message carries the command context and git's stderr. */
public class GitException extends RuntimeException {

	public GitException(String message) {
		super(message);
	}
}
