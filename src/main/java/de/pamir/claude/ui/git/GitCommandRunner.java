package de.pamir.claude.ui.git;

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

	public GitResult runOrThrow(Path workingDir, String... args) {
		GitResult result = run(workingDir, args);
		if (!result.ok()) {
			throw new GitException("git " + String.join(" ", args) + " failed: " + result.stderr());
		}
		return result;
	}
}
