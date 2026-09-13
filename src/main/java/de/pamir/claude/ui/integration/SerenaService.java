package de.pamir.claude.ui.integration;

import de.pamir.claude.ui.config.SettingsService;
import de.pamir.claude.ui.git.GitCommandRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Serena (symbolic code tools) MCP server integration (docs/plan/phase-12-linear-cache-serena-
 * context.md Track B): the persisted "MCP servers" root/uv-path settings, save-time validation,
 * and the one-off capture of Serena's Claude-Code system-prompt override text. {@link
 * de.pamir.claude.ui.session.SessionConfigFactory} is the caller that actually layers the
 * per-session MCP entry — this class just knows whether/how Serena is configured.
 *
 * <p>Confirmed live against the checkout at {@code /mnt/d/projects/serena} (Step B0): its
 * {@code pyproject.toml} names the package {@code serena-agent}, not {@code serena} — validation
 * below checks for that.
 */
@Service
public class SerenaService {

	private static final Logger log = LoggerFactory.getLogger(SerenaService.class);
	private static final int TIMEOUT_SECONDS = 60;
	private static final String EXPECTED_PACKAGE_NAME = "serena-agent";

	/** Seam over process execution so {@code uv --version}/the prompt-override capture can be faked in tests. */
	interface ProcessRunner {
		GitCommandRunner.GitResult run(List<String> command);
	}

	private final SettingsService settings;
	private final ProcessRunner runner;

	/** Memoizes {@link #ccSystemPromptOverride()} per root value — re-captured only when the root setting changes. */
	private volatile String lastPromptRoot;
	private volatile String lastPromptOverride;

	@Autowired
	public SerenaService(SettingsService settings) {
		this(settings, SerenaService::runProcess);
	}

	SerenaService(SettingsService settings, ProcessRunner runner) {
		this.settings = settings;
		this.runner = runner;
	}

	public boolean configured() {
		return !settings.current().mcpSerenaRoot().isBlank();
	}

	/** The configured Serena checkout root — only meaningful when {@link #configured()}. */
	public String root() {
		return settings.current().mcpSerenaRoot();
	}

	public String uvCommand() {
		return settings.current().mcpUvPath();
	}

	/**
	 * Validates explicit {@code root}/{@code uvPath} values (not the persisted setting), so a
	 * {@code PATCH /api/settings} can be checked before it's applied. A blank {@code root} always
	 * passes — clearing/disabling Serena must never be blocked by validation. Throws {@link
	 * IllegalArgumentException} (mapped to a 400) with the specific reason otherwise.
	 */
	public void validate(String root, String uvPath) {
		if (root == null || root.isBlank()) {
			return;
		}
		Path dir = Path.of(root);
		if (!Files.isDirectory(dir)) {
			throw new IllegalArgumentException("Serena root is not a directory: " + root);
		}
		Path pyproject = dir.resolve("pyproject.toml");
		if (!Files.isRegularFile(pyproject)) {
			throw new IllegalArgumentException("Serena root has no pyproject.toml: " + pyproject);
		}
		String content;
		try {
			content = Files.readString(pyproject);
		} catch (IOException e) {
			throw new IllegalArgumentException("could not read " + pyproject + ": " + e.getMessage());
		}
		if (!content.contains("name = \"" + EXPECTED_PACKAGE_NAME + "\"")) {
			throw new IllegalArgumentException(
					pyproject + " does not look like a Serena checkout (expected `name = \""
							+ EXPECTED_PACKAGE_NAME + "\"`)");
		}
		String uv = uv(uvPath);
		GitCommandRunner.GitResult result;
		try {
			result = runner.run(List.of(uv, "--version"));
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("uv not found or failed to start: " + uv + " (" + e.getMessage() + ")");
		}
		if (!result.ok()) {
			throw new IllegalArgumentException("`" + uv + " --version` failed: "
					+ (result.stderr().isBlank() ? result.stdout() : result.stderr()));
		}
	}

	/**
	 * Claude sessions get Serena's system-prompt override appended (decision 3) — captured once
	 * per root value via {@code uv run --directory <root> serena prompts
	 * print-cc-system-prompt-override}, memoized until the root setting changes. Never throws: a
	 * failed capture logs a WARN and returns null, since the prompt is an optimization, not a
	 * dependency — the session still gets the MCP entry either way.
	 */
	public String ccSystemPromptOverride() {
		if (!configured()) {
			return null;
		}
		String root = root();
		if (root.equals(lastPromptRoot)) {
			return lastPromptOverride;
		}
		String result = capturePromptOverride(root);
		lastPromptRoot = root;
		lastPromptOverride = result;
		return result;
	}

	private String capturePromptOverride(String root) {
		try {
			GitCommandRunner.GitResult result = runner.run(
					List.of(uvCommand(), "run", "--directory", root, "serena", "prompts",
							"print-cc-system-prompt-override"));
			if (!result.ok()) {
				log.warn("serena prompts print-cc-system-prompt-override failed (exit {}): {}",
						result.exitCode(), result.stderr());
				return null;
			}
			return result.stdout();
		} catch (RuntimeException e) {
			log.warn("failed to capture Serena's system-prompt override", e);
			return null;
		}
	}

	private static String uv(String uvPath) {
		return (uvPath == null || uvPath.isBlank()) ? "uv" : uvPath;
	}

	private static GitCommandRunner.GitResult runProcess(List<String> command) {
		try {
			Process process = new ProcessBuilder(command).start();
			String stdout = new String(process.getInputStream().readAllBytes());
			String stderr = new String(process.getErrorStream().readAllBytes());
			if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				throw new IllegalStateException("timed out: " + String.join(" ", command));
			}
			return new GitCommandRunner.GitResult(process.exitValue(), stdout.strip(), stderr.strip());
		} catch (IOException e) {
			throw new IllegalStateException("failed to start: " + String.join(" ", command) + " (" + e.getMessage() + ")");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted: " + String.join(" ", command));
		}
	}
}
