package de.pamir.agentic.ui.git;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class GitCommandRunner {

	private static final Logger log = LoggerFactory.getLogger(GitCommandRunner.class);
	private static final int TIMEOUT_SECONDS = 60;

	public record GitResult(int exitCode, String stdout, String stderr) {

		public boolean ok() {
			return exitCode == 0;
		}
	}

	public GitResult run(Path workingDir, String... args) {
		List<String> command = new ArrayList<>();
		command.add("git");
		command.addAll(List.of(args));
		try {
			Process process = new ProcessBuilder(command).directory(workingDir.toFile()).start();
			String stdout = new String(process.getInputStream().readAllBytes());
			String stderr = new String(process.getErrorStream().readAllBytes());
			if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				throw new GitException("git timed out: " + String.join(" ", command));
			}
			log.debug("git {} -> {}", String.join(" ", args), process.exitValue());
			return new GitResult(process.exitValue(), stdout.strip(), stderr.strip());
		} catch (IOException e) {
			throw new GitException("git failed to start: " + e.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new GitException("git interrupted");
		}
	}

	/** Raw stdout bytes of a git command, capped: reading stops at {@code maxBytes + 1} and the process is killed. */
	public record BytesResult(int exitCode, byte[] stdout, boolean truncated) {

		public boolean ok() {
			return exitCode == 0;
		}
	}

	/**
	 * For content that must not be {@code strip()}ped or decoded with the platform charset
	 * ({@code git show rev:path}). Returns at most {@code maxBytes} bytes and flags truncation.
	 */
	public BytesResult runBytes(Path workingDir, int maxBytes, String... args) {
		List<String> command = new ArrayList<>();
		command.add("git");
		command.addAll(List.of(args));
		try {
			Process process = new ProcessBuilder(command).directory(workingDir.toFile())
					.redirectError(ProcessBuilder.Redirect.DISCARD).start();
			byte[] read = process.getInputStream().readNBytes(maxBytes + 1);
			boolean truncated = read.length > maxBytes;
			if (truncated) {
				process.destroyForcibly();
				process.waitFor(5, TimeUnit.SECONDS);
				return new BytesResult(0, java.util.Arrays.copyOf(read, maxBytes), true);
			}
			if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				throw new GitException("git timed out: " + String.join(" ", command));
			}
			return new BytesResult(process.exitValue(), read, false);
		} catch (IOException e) {
			throw new GitException("git failed to start: " + e.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new GitException("git interrupted");
		}
	}

	public GitResult runOrThrow(Path workingDir, String... args) {
		GitResult result = run(workingDir, args);
		if (!result.ok()) {
			throw new GitException("git " + String.join(" ", args) + " failed: " + result.stderr());
		}
		return result;
	}
}
