package de.pamir.agentic.ui.integration;

import de.pamir.agentic.ui.config.AppProperties;
import de.pamir.agentic.ui.config.SettingsService;
import de.pamir.agentic.ui.git.GitCommandRunner;
import de.pamir.agentic.ui.journal.JournalPublisher;
import de.pamir.agentic.ui.session.SessionEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CodeGraph (third one-of code-intelligence tool) MCP server integration — docs/plan/
 * phase-14-codegraph.md. Follows {@link SerenaService}'s shape (settings + validate, no
 * build-state machine) rather than {@link GraphifyService}'s executor/coalescing shape:
 * codegraph indexes once, synchronously, at session creation (decision 2) and then keeps
 * itself current via its own file watcher — there is no refresh pipeline to run here.
 * {@link de.pamir.agentic.ui.session.SessionConfigFactory} layers the per-session MCP
 * entry; {@link #index}/{@link #ensureIndexed} (Step 2) live here.
 *
 * <p>Posture from the security review (decisions 5, 10): telemetry, update check and the
 * shared daemon are off on every process this class spawns ({@link #POSTURE_ENV}, set
 * explicitly on the entry rather than relying on the backend's own environment); the
 * official installer/upgrade/hooks/skill surface is never touched — {@code init}, {@code
 * serve --mcp} and {@code version} are the whole contract, run from a reviewed local
 * checkout on the backend's own {@code node} (decision 1).
 */
@Service
public class CodegraphService {

	private static final Logger log = LoggerFactory.getLogger(CodegraphService.class);
	/** Decision 9: two Node startups budgeted — the WASM-flag relaunch spawns a second process. */
	static final int VALIDATE_TIMEOUT_SECONDS = 30;
	/** Hard cap on one {@code codegraph init} run (Step 2) — 0.8s for this repo, budget for a much bigger one. */
	static final Duration INDEX_TIMEOUT = Duration.ofMinutes(5);
	private static final int LOG_TAIL_LINES = 20;
	private static final Pattern INIT_COUNTS = Pattern.compile("([\\d,]+) nodes, ([\\d,]+) edges");
	private static final String EXPECTED_PACKAGE_NAME = "@colbymchenry/codegraph";
	private static final String INDEX_DIR_NAME = ".codegraph";
	private static final String INDEX_DB_NAME = "codegraph.db";

	/** Decision 5: telemetry, update check and the shared daemon off on every process we spawn. */
	static final Map<String, String> POSTURE_ENV = Map.of(
			"CODEGRAPH_TELEMETRY", "0",
			"DO_NOT_TRACK", "1",
			"CODEGRAPH_NO_UPDATE_CHECK", "1",
			"CODEGRAPH_NO_DAEMON", "1");

	/**
	 * Decision 7: our own provider-neutral nudge, appended through the {@code extraSystemPrompt}
	 * seam for both providers — codegraph's own {@code SERVER_INSTRUCTIONS} already reach the
	 * agent through the MCP {@code initialize} response, so this only says what the server can't
	 * know: that the index was built by us at session start, that a FAILED chip means fall back
	 * to Read/Grep, and not to run codegraph's own maintenance commands.
	 */
	public static final String SYSTEM_PROMPT_BLOCK = """
			A CodeGraph index of this project's code (symbols, calls, imports, inheritance; not \
			docs or configs) was built at session start and is served by the `codegraph` MCP server; \
			its own watcher keeps it current with your edits (about a second behind). Use \
			`codegraph_explore` first for any question about structure, callers/callees, how two \
			parts connect, or to read the source of a symbol you can name — it returns verbatim \
			line-numbered source with call paths and blast radius, so read only the files it points \
			to. If it reports the project isn't indexed, the index build failed on our side: use \
			Read/Grep for the rest of the session. Never run `codegraph init`, `sync`, `install` or \
			`upgrade` yourself.""";

	/** Seam over process execution so the {@code version} probe can be faked in tests. */
	interface ProcessRunner {
		GitCommandRunner.GitResult run(List<String> command, Map<String, String> env, int timeoutSeconds);
	}

	/** A started index process — the seam a test fakes to script exit codes, output and hangs. */
	interface IndexProcess {
		/** Blocks until exit or the timeout; {@code null} on timeout (the caller then {@link #kill}s). */
		Integer waitFor(Duration timeout) throws InterruptedException;

		void kill();
	}

	/** Seam over launching {@code codegraph init}: command + env, cwd, and the log file to append to. */
	interface IndexLauncher {
		IndexProcess start(List<String> command, Map<String, String> env, Path cwd, Path logFile) throws IOException;
	}

	/** Thrown by the real runner when the validate timeout fires — worded differently from a start failure. */
	static final class ProcessTimedOut extends IllegalStateException {
		ProcessTimedOut(String message) {
			super(message);
		}
	}

	private enum Status { BUILDING, READY, FAILED }

	private record BuildResult(Status status, Integer nodes, Integer edges, String message) {
	}

	private final SettingsService settings;
	private final AppProperties props;
	private final ProcessRunner runner;
	private final IndexLauncher launcher;
	private final JournalPublisher journal;
	private final ObjectMapper mapper;

	@Autowired
	public CodegraphService(SettingsService settings, AppProperties props, JournalPublisher journal, ObjectMapper mapper) {
		this(settings, props, journal, mapper, CodegraphService::runProcess, CodegraphService::launch);
	}

	CodegraphService(SettingsService settings, AppProperties props, ProcessRunner runner) {
		this(settings, props, null, null, runner, null);
	}

	CodegraphService(SettingsService settings, AppProperties props, JournalPublisher journal, ObjectMapper mapper,
					  ProcessRunner runner, IndexLauncher launcher) {
		this.settings = settings;
		this.props = props;
		this.journal = journal;
		this.mapper = mapper;
		this.runner = runner;
		this.launcher = launcher;
	}

	public boolean configured() {
		return !settings.current().mcpCodegraphRoot().isBlank();
	}

	/** The configured codegraph checkout root — only meaningful when {@link #configured()}. */
	public String root() {
		return settings.current().mcpCodegraphRoot();
	}

	/** {@code ["node", "<root>/dist/bin/codegraph.js"]} — callers append the subcommand. */
	public List<String> baseCommand() {
		return baseCommand(root());
	}

	/** Decision 5's posture env, for the MCP entry ({@link de.pamir.agentic.ui.session.SessionConfigFactory}). */
	public Map<String, String> postureEnv() {
		return POSTURE_ENV;
	}

	static List<String> baseCommand(String root) {
		return List.of("node", root + "/dist/bin/codegraph.js");
	}

	/** {@code <cwd>/.codegraph} — decision 4: forced inside the worktree, unlike graphify's out-of-tree dir. */
	public Path indexDir(String cwdPath) {
		return Path.of(cwdPath, INDEX_DIR_NAME);
	}

	/** {@code <cwd>/.codegraph/codegraph.db} — what {@link #ensureIndexed} checks for. */
	public Path indexDbFile(String cwdPath) {
		return indexDir(cwdPath).resolve(INDEX_DB_NAME);
	}

	// ------------------------------------------------------------------ Step 2: index-at-create

	/**
	 * Synchronous, blocking (decision 2 — unlike {@link GraphifyService#build}'s fire-and-forget):
	 * runs {@code codegraph init <cwdPath> --yes}, journals BUILDING before and READY/FAILED after
	 * (counts parsed from the init output's "N nodes, M edges" line when present, thousands
	 * separators stripped). Never throws into the caller — {@code SessionService.create()} must
	 * proceed with an unindexed, still-usable session on failure/timeout. A no-op (mirroring
	 * {@link GraphifyService#build}'s {@code isGraphify} guard) for a session whose {@code
	 * codeIntel} isn't {@code codegraph}.
	 */
	public void index(SessionEntity session) {
		if (!isCodegraph(session)) {
			return;
		}
		UUID id = session.id();
		String cwdPath = session.cwdPath();
		Instant started = Instant.now();
		transition(id, new BuildResult(Status.BUILDING, null, null, null), null);
		Path logFile = Path.of(props.logDir(), "codegraph", id + ".log");
		List<String> command = new ArrayList<>(baseCommand());
		command.add("init");
		command.add(cwdPath);
		command.add("--yes");
		Integer exit;
		String failure = null;
		try {
			Files.createDirectories(logFile.getParent());
			Files.writeString(logFile, "\n==== " + started + " " + String.join(" ", command) + "\n",
					StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			IndexProcess process = launcher.start(command, indexEnv(), Path.of(cwdPath), logFile);
			exit = process.waitFor(INDEX_TIMEOUT);
			if (exit == null) {
				process.kill();
				failure = "timed out after " + INDEX_TIMEOUT.toMinutes() + " min";
			}
		} catch (IOException e) {
			exit = null;
			failure = "could not start: " + e.getMessage();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return;
		}
		long durationMs = Duration.between(started, Instant.now()).toMillis();
		List<String> tail = tail(logFile);
		if (failure == null && exit != 0) {
			failure = failureLine(tail, exit);
		}
		if (failure != null) {
			log.warn("codegraph init for {} failed: {}", id, failure);
			transition(id, new BuildResult(Status.FAILED, null, null, failure), durationMs);
			return;
		}
		Integer nodes = null;
		Integer edges = null;
		for (String line : tail) {
			Matcher m = INIT_COUNTS.matcher(line);
			if (m.find()) {
				nodes = Integer.parseInt(m.group(1).replace(",", ""));
				edges = Integer.parseInt(m.group(2).replace(",", ""));
			}
		}
		transition(id, new BuildResult(Status.READY, nodes, edges, null), durationMs);
	}

	/**
	 * Resume/wake path (decision 2): READY (re-journaled, no counts) iff {@code <cwd>/.codegraph/
	 * codegraph.db} exists, else {@link #index} runs again — covers a session whose first index
	 * failed or timed out, and a backend restart. No in-memory state to lose, unlike graphify:
	 * every call here re-checks the filesystem directly. A no-op for a session whose {@code
	 * codeIntel} isn't {@code codegraph}.
	 */
	public void ensureIndexed(SessionEntity session) {
		if (!isCodegraph(session)) {
			return;
		}
		if (Files.isRegularFile(indexDbFile(session.cwdPath()))) {
			transition(session.id(), new BuildResult(Status.READY, null, null, null), null);
			return;
		}
		index(session);
	}

	private static boolean isCodegraph(SessionEntity session) {
		return SettingsService.CODE_INTEL_CODEGRAPH.equals(session.codeIntel());
	}

	private static Map<String, String> indexEnv() {
		Map<String, String> env = new LinkedHashMap<>(POSTURE_ENV);
		env.put("NO_COLOR", "1");
		env.put("CI", "1");
		return env;
	}

	/** Journals a {@code code_intel_status} event (docs/PROTOCOL.md) — no in-memory state kept. */
	private void transition(UUID id, BuildResult result, Long durationMs) {
		if (journal == null) {
			return;
		}
		ObjectNode payload = mapper.createObjectNode()
				.put("tool", SettingsService.CODE_INTEL_CODEGRAPH)
				.put("status", result.status().name());
		if (result.nodes() != null) {
			payload.put("nodes", result.nodes());
		}
		if (result.edges() != null) {
			payload.put("edges", result.edges());
		}
		if (durationMs != null) {
			payload.put("durationMs", durationMs);
		}
		if (result.message() != null) {
			payload.put("message", result.message());
		}
		try {
			journal.record(id, "code_intel_status", payload);
		} catch (RuntimeException e) {
			log.warn("could not journal code_intel_status for {}: {}", id, e.getMessage());
		}
	}

	/**
	 * The most specific complaint in the log tail: an {@code error} line beats whatever was
	 * printed last (mirrors {@link GraphifyService#failureLine}, simplified — codegraph's own
	 * failure output doesn't have graphify's two-tier "Rebuild failed"/generic trailer split).
	 */
	static String failureLine(List<String> tail, int exit) {
		return tail.stream().filter(l -> l.toLowerCase(Locale.ROOT).contains("error")).reduce((a, b) -> b)
				.orElse(tail.isEmpty() ? "exit " + exit : tail.getLast());
	}

	private static List<String> tail(Path logFile) {
		Deque<String> last = new ArrayDeque<>();
		try (var lines = Files.lines(logFile, StandardCharsets.UTF_8)) {
			lines.forEach(l -> {
				if (l.isBlank()) {
					return;
				}
				if (last.size() == LOG_TAIL_LINES) {
					last.removeFirst();
				}
				last.addLast(l.strip());
			});
		} catch (IOException | java.io.UncheckedIOException e) {
			return List.of();
		}
		return new ArrayList<>(last);
	}

	private static IndexProcess launch(List<String> command, Map<String, String> env, Path cwd, Path logFile)
			throws IOException {
		ProcessBuilder pb = new ProcessBuilder(command)
				.directory(cwd.toFile())
				.redirectErrorStream(true)
				.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
		pb.environment().putAll(env);
		Process process = pb.start();
		return new IndexProcess() {
			@Override
			public Integer waitFor(Duration timeout) throws InterruptedException {
				return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS) ? process.exitValue() : null;
			}

			@Override
			public void kill() {
				process.destroyForcibly();
			}
		};
	}

	// ------------------------------------------------------------------ Step 1: settings validation

	/**
	 * Validates an explicit {@code root} value (not the persisted setting), so a {@code PATCH
	 * /api/settings} can be checked before it's applied. A blank root always passes — clearing
	 * must never be blocked here. Otherwise: a directory whose {@code package.json} names {@code
	 * @colbymchenry/codegraph}, {@code dist/bin/codegraph.js} is a regular file (else the error
	 * names the exact build commands to run in the checkout), and {@code node <root>/dist/bin/
	 * codegraph.js version} exits 0 within {@link #VALIDATE_TIMEOUT_SECONDS}. Unlike graphify,
	 * the probe installs nothing. Throws {@link IllegalArgumentException} (mapped to a 400) with
	 * the specific reason.
	 */
	public void validate(String root) {
		if (root == null || root.isBlank()) {
			return;
		}
		Path dir = Path.of(root);
		if (!Files.isDirectory(dir)) {
			throw new IllegalArgumentException("CodeGraph root is not a directory: " + root);
		}
		Path packageJson = dir.resolve("package.json");
		if (!Files.isRegularFile(packageJson)) {
			throw new IllegalArgumentException("CodeGraph root has no package.json: " + packageJson);
		}
		String content;
		try {
			content = Files.readString(packageJson);
		} catch (IOException e) {
			throw new IllegalArgumentException("could not read " + packageJson + ": " + e.getMessage());
		}
		if (!content.contains("\"name\": \"" + EXPECTED_PACKAGE_NAME + "\"")) {
			throw new IllegalArgumentException(
					packageJson + " does not look like a codegraph checkout (expected `\"name\": \""
							+ EXPECTED_PACKAGE_NAME + "\"`)");
		}
		Path bin = dir.resolve("dist/bin/codegraph.js");
		if (!Files.isRegularFile(bin)) {
			throw new IllegalArgumentException(bin + " does not exist — build the checkout first: "
					+ "npm ci --ignore-scripts && npx tsc && npm run copy-assets");
		}
		List<String> command = new ArrayList<>(baseCommand(root));
		command.add("version");
		String rendered = String.join(" ", command);
		GitCommandRunner.GitResult result;
		try {
			result = runner.run(command, POSTURE_ENV, VALIDATE_TIMEOUT_SECONDS);
		} catch (ProcessTimedOut e) {
			throw new IllegalArgumentException(
					"`" + rendered + "` did not finish within " + VALIDATE_TIMEOUT_SECONDS + " s");
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("node not found or failed to start: " + e.getMessage());
		}
		if (!result.ok()) {
			throw new IllegalArgumentException("`" + rendered + "` failed: "
					+ (result.stderr().isBlank() ? result.stdout() : lastLine(result.stderr())));
		}
	}

	private static String lastLine(String text) {
		String[] lines = text.strip().split("\n");
		return lines[lines.length - 1].strip();
	}

	/**
	 * Drains stdout/stderr on their own (virtual) threads before waiting (same reasoning as
	 * {@link GraphifyService}'s runner — reading a stream to EOF first would block until the
	 * process exits on its own, defeating the timeout).
	 */
	private static GitCommandRunner.GitResult runProcess(List<String> command, Map<String, String> env, int timeoutSeconds) {
		try {
			ProcessBuilder pb = new ProcessBuilder(command);
			pb.environment().putAll(env);
			Process process = pb.start();
			var stdout = drain(process.getInputStream());
			var stderr = drain(process.getErrorStream());
			if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				throw new ProcessTimedOut("timed out: " + String.join(" ", command));
			}
			return new GitCommandRunner.GitResult(process.exitValue(), stdout.join().strip(), stderr.join().strip());
		} catch (IOException e) {
			throw new IllegalStateException("failed to start: " + String.join(" ", command) + " (" + e.getMessage() + ")");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted: " + String.join(" ", command));
		}
	}

	private static CompletableFuture<String> drain(InputStream in) {
		CompletableFuture<String> out = new CompletableFuture<>();
		Thread.ofVirtual().start(() -> {
			try (in) {
				out.complete(new String(in.readAllBytes()));
			} catch (IOException e) {
				out.complete("");
			}
		});
		return out;
	}
}
