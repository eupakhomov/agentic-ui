package de.pamir.agentic.ui.integration;

import de.pamir.agentic.ui.config.AppProperties;
import de.pamir.agentic.ui.config.SettingsService;
import de.pamir.agentic.ui.git.GitCommandRunner;
import de.pamir.agentic.ui.journal.JournalPublisher;
import de.pamir.agentic.ui.session.SessionEntity;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.ObjectMapper;

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
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Graphify (knowledge-graph code intelligence) MCP server integration — docs/plan/phase-13-
 * graphify.md. Mirrors {@link SerenaService}'s shape: the persisted "MCP servers" root setting
 * (sharing Serena's {@code mcp.uv-path}), save-time validation, and the fixed command/env/path
 * conventions every graphify process in this app is built from. {@link
 * de.pamir.agentic.ui.session.SessionConfigFactory} layers the per-session MCP entry; the
 * background build pipeline (Step 3: {@link #build}, {@link #refreshAfterTurn}, {@link
 * #ensureBuilt}, {@link #delete}) lives here.
 *
 * <p>Build lifecycle (decisions 2, 8, 11): one {@code graphify update <cwdPath>} run per request,
 * on a 2-thread executor (builds are I/O-bound tree walks — more in parallel only slow each other
 * down on DrvFS), single-flight per session with coalescing: a request while that session's
 * build is running sets a dirty flag and the running build re-runs exactly once when it
 * finishes. Status is in-memory plus a journaled {@code code_intel_status} event per
 * transition (so the widget chip replays after a restart) — no DB column; after a restart
 * {@link #ensureBuilt} derives READY from the graph file's existence, else kicks a build.
 *
 * <p>Posture from the security review (decision 14): the {@code update} CLI and the {@code
 * graphify-mcp} server are the whole contract — never graphify's own skill, {@code install},
 * hooks, semantic backend or {@code graph.html}. Every process is driven from a reviewed local
 * checkout via {@code uv run --directory <root> --no-dev --extra mcp --extra sql} (decision 3;
 * {@code --no-dev} keeps {@code uv run} from installing nuitka/pyright/…), and the output dir is
 * always {@code GRAPHIFY_OUT} outside the worktree (decision 6).
 */
@Service
public class GraphifyService {

	/**
	 * Decision 13: Serena's 60 s is too short for a cold first env sync (~110 wheels) — the
	 * {@code --version} probe on save is deliberately also that first sync.
	 */
	static final int VALIDATE_TIMEOUT_SECONDS = 180;
	/** Hard cap on one {@code graphify update} run (Step 3) — this repo takes ~80 s on DrvFS, seconds on macOS. */
	static final Duration BUILD_TIMEOUT = Duration.ofMinutes(15);
	private static final int LOG_TAIL_LINES = 20;
	private static final Pattern REBUILT = Pattern.compile("Rebuilt: (\\d+) nodes, (\\d+) edges");
	private static final Logger log = LoggerFactory.getLogger(GraphifyService.class);
	/** graphify's PyPI/package name is {@code graphifyy} (double y) — confirmed in Step 0. */
	private static final String EXPECTED_PACKAGE_NAME = "graphifyy";
	private static final String GRAPH_DIR_NAME = ".graphify";
	public static final String GRAPH_FILE = "graph.json";

	/**
	 * Decision 10: our own provider-neutral nudge, appended through the {@code extraSystemPrompt}
	 * seam for both providers (graphify's own {@code claude-md.md} text is CLI-flavoured and assumes
	 * its hooks). Docs are not in the graph (decision 4 — code-only).
	 */
	public static final String SYSTEM_PROMPT_BLOCK = """
			A graphify knowledge graph of this project's code (AST-derived: files, classes, functions, \
			calls, imports, inheritance; communities = subsystems) is available through the `graphify` \
			MCP server. For any question about structure, callers/callees, how two parts connect, or \
			where something lives, query it first — `query_graph` (question → scoped subgraph), \
			`get_node`, `get_neighbors`, `shortest_path`, `get_community`, `god_nodes`, `graph_stats` — \
			then read exactly the files it points to, instead of grepping or reading files one by one. \
			The graph is rebuilt in the background after each of your turns and may lag your latest \
			edits by a few seconds; right after session start it may still be building — a tool error \
			saying graph.json is not found means wait briefly and retry. Documentation files are not in \
			the graph.""";

	/** Seam over process execution so the {@code --version} probe can be faked in tests. */
	interface ProcessRunner {
		GitCommandRunner.GitResult run(List<String> command, int timeoutSeconds);
	}

	/** A started build process — the seam a test fakes to script exit codes, output and hangs. */
	interface BuildProcess {
		/** Blocks until exit or the timeout; {@code null} on timeout (the caller then {@link #kill}s). */
		Integer waitFor(Duration timeout) throws InterruptedException;

		void kill();
	}

	/** Seam over launching a build: command + env, cwd, and the log file stdout/stderr append to. */
	interface BuildLauncher {
		BuildProcess start(List<String> command, Map<String, String> env, Path cwd, Path logFile) throws IOException;
	}

	public enum Status { BUILDING, READY, FAILED }

	/** One session's current graph-build state (decision 11 — in memory only). */
	public record BuildState(Status status, Instant since, Integer nodes, Integer edges, String message) {
	}

	/** Per-session bookkeeping; every field guarded by the entry's own monitor. */
	private static final class Entry {
		BuildState state;
		boolean running;
		boolean dirty;
		boolean deleted;
		BuildProcess process;
	}

	/** Thrown by the real runner when the hard timeout fires — validation words that case differently. */
	static final class ProcessTimedOut extends IllegalStateException {
		ProcessTimedOut(String message) {
			super(message);
		}
	}

	private final SettingsService settings;
	private final AppProperties props;
	private final ProcessRunner runner;
	private final BuildLauncher launcher;
	private final JournalPublisher journal;
	private final ObjectMapper mapper;
	private final ExecutorService builds;
	private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();

	@Autowired
	public GraphifyService(SettingsService settings, AppProperties props, JournalPublisher journal, ObjectMapper mapper) {
		this(settings, props, journal, mapper, GraphifyService::runProcess, GraphifyService::launch,
				Executors.newFixedThreadPool(2, r -> {
					Thread t = new Thread(r, "graphify-build");
					t.setDaemon(true);
					return t;
				}));
	}

	GraphifyService(SettingsService settings, AppProperties props, ProcessRunner runner) {
		this(settings, props, null, null, runner, null, null);
	}

	GraphifyService(SettingsService settings, AppProperties props, JournalPublisher journal, ObjectMapper mapper,
					ProcessRunner runner, BuildLauncher launcher, ExecutorService builds) {
		this.settings = settings;
		this.props = props;
		this.journal = journal;
		this.mapper = mapper;
		this.runner = runner;
		this.launcher = launcher;
		this.builds = builds;
	}

	@PreDestroy
	void shutdown() {
		if (builds != null) {
			builds.shutdownNow();
		}
		entries.forEach((id, entry) -> {
			synchronized (entry) {
				if (entry.process != null) {
					entry.process.kill();
				}
			}
		});
	}

	public boolean configured() {
		return !settings.current().mcpGraphifyRoot().isBlank();
	}

	/** The configured graphify checkout root — only meaningful when {@link #configured()}. */
	public String root() {
		return settings.current().mcpGraphifyRoot();
	}

	public String uvCommand() {
		return settings.current().mcpUvPath();
	}

	/** Decision 6: {@code <worktree-root>/.graphify/<sessionId>} — outside every worktree, dot-prefixed so the orphan scan skips it. */
	public Path graphDir(UUID sessionId) {
		return Path.of(props.worktreeRoot(), GRAPH_DIR_NAME, sessionId.toString());
	}

	/** {@code <graphDir>/graph.json} — what the MCP server is pointed at and what the build produces. */
	public Path graphFile(UUID sessionId) {
		return graphDir(sessionId).resolve(GRAPH_FILE);
	}

	/**
	 * The invariant prefix of every graphify process: {@code <uv> run --directory <root> --no-dev
	 * --extra mcp --extra sql}. Callers append the graphify entry point ({@code graphify update …},
	 * {@code graphify-mcp …}).
	 */
	public List<String> baseCommand() {
		return baseCommand(root(), uvCommand());
	}

	static List<String> baseCommand(String root, String uvPath) {
		return List.of(uv(uvPath), "run", "--directory", root, "--no-dev", "--extra", "mcp", "--extra", "sql");
	}

	// ------------------------------------------------------------------ build pipeline (Step 3)

	/** Kicks (or coalesces into) a background build of this session's graph; returns immediately. */
	public void build(SessionEntity session) {
		if (!isGraphify(session)) {
			return;
		}
		Entry entry = entries.computeIfAbsent(session.id(), k -> new Entry());
		synchronized (entry) {
			if (entry.deleted) {
				return;
			}
			if (entry.running) {
				entry.dirty = true; // decision 8: exactly one more run once the current one finishes
				return;
			}
			entry.running = true;
			entry.dirty = false;
		}
		builds.submit(() -> runUntilClean(session, entry));
	}

	/** Post-turn refresh (decision 8): same as {@link #build}, named for the call site. */
	public void refreshAfterTurn(SessionEntity session) {
		build(session);
	}

	/**
	 * Resume/wake path (decision 11): after a backend restart the in-memory state is gone —
	 * READY iff the graph file exists (re-journaled once so a chip that replayed a stale
	 * BUILDING sees the truth), else a build is kicked. A no-op for a session already tracked.
	 */
	public void ensureBuilt(SessionEntity session) {
		if (!isGraphify(session)) {
			return;
		}
		if (entries.containsKey(session.id())) {
			return;
		}
		Path graph = graphFile(session.id());
		if (Files.isRegularFile(graph)) {
			Entry entry = entries.computeIfAbsent(session.id(), k -> new Entry());
			Instant since;
			try {
				since = Files.getLastModifiedTime(graph).toInstant();
			} catch (IOException e) {
				since = Instant.now();
			}
			transition(session.id(), entry, new BuildState(Status.READY, since, null, null, null), null);
			return;
		}
		build(session);
	}

	/** Current in-memory state, or null when this session has no graph tracked (never built, or restarted since). */
	public BuildState status(UUID sessionId) {
		Entry entry = entries.get(sessionId);
		if (entry == null) {
			return null;
		}
		synchronized (entry) {
			return entry.state;
		}
	}

	/**
	 * Close path: kills a running build, blocks any coalesced re-run, and removes the graph dir
	 * (best-effort, logged). Safe for a session that never had a graph.
	 */
	public void delete(UUID sessionId) {
		Entry entry = entries.computeIfAbsent(sessionId, k -> new Entry());
		synchronized (entry) {
			entry.deleted = true;
			entry.dirty = false;
			if (entry.process != null) {
				entry.process.kill();
			}
		}
		deleteRecursively(graphDir(sessionId));
		entries.remove(sessionId);
	}

	/** Orphan sweep ({@code POST /api/maintenance/orphans/clean}): every graph dir whose id is not in {@code activeIds}. */
	public List<String> cleanOrphanGraphs(java.util.Set<UUID> activeIds) {
		Path root = Path.of(props.worktreeRoot(), GRAPH_DIR_NAME);
		if (!Files.isDirectory(root)) {
			return List.of();
		}
		List<String> removed = new ArrayList<>();
		try (var children = Files.list(root)) {
			for (Path child : children.filter(Files::isDirectory).sorted().toList()) {
				UUID id;
				try {
					id = UUID.fromString(child.getFileName().toString());
				} catch (IllegalArgumentException e) {
					continue; // not ours to touch
				}
				if (activeIds.contains(id)) {
					continue;
				}
				delete(id);
				removed.add(child.toString());
			}
		} catch (IOException e) {
			log.warn("could not list {}: {}", root, e.getMessage());
		}
		return removed;
	}

	private static boolean isGraphify(SessionEntity session) {
		return SettingsService.CODE_INTEL_GRAPHIFY.equals(session.codeIntel());
	}

	/** The executor task: one run, then again while a request arrived mid-run (coalesced to one). */
	private void runUntilClean(SessionEntity session, Entry entry) {
		try {
			while (true) {
				runOnce(session, entry);
				synchronized (entry) {
					if (!entry.dirty || entry.deleted) {
						entry.running = false;
						return;
					}
					entry.dirty = false;
				}
			}
		} catch (RuntimeException e) {
			log.error("graphify build loop for {} died", session.id(), e);
			synchronized (entry) {
				entry.running = false;
			}
		}
	}

	private void runOnce(SessionEntity session, Entry entry) {
		UUID id = session.id();
		Instant started = Instant.now();
		transition(id, entry, new BuildState(Status.BUILDING, started, previousNodes(entry), previousEdges(entry), null), null);
		Path graphDir = graphDir(id);
		Path logFile = Path.of(props.logDir(), "graphify", id + ".log");
		List<String> command = new ArrayList<>(baseCommand());
		command.add("graphify");
		command.add("update");
		command.add(session.cwdPath());
		Integer exit;
		String failure = null;
		try {
			Files.createDirectories(graphDir);
			Files.createDirectories(logFile.getParent());
			Files.writeString(logFile, "\n==== " + started + " " + String.join(" ", command) + "\n",
					StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			BuildProcess process = launcher.start(command, buildEnv(graphDir), Path.of(session.worktreePath()), logFile);
			synchronized (entry) {
				if (entry.deleted) {
					process.kill();
					return;
				}
				entry.process = process;
			}
			try {
				exit = process.waitFor(BUILD_TIMEOUT);
			} finally {
				synchronized (entry) {
					entry.process = null;
				}
			}
			if (exit == null) {
				process.kill();
				failure = "timed out after " + BUILD_TIMEOUT.toMinutes() + " min";
			}
		} catch (IOException e) {
			exit = null;
			failure = "could not start: " + e.getMessage();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return;
		}
		synchronized (entry) {
			if (entry.deleted) {
				return; // closed mid-build: nothing to report, the dir is gone
			}
		}
		long durationMs = Duration.between(started, Instant.now()).toMillis();
		List<String> tail = tail(logFile);
		if (failure == null && exit != 0) {
			failure = failureLine(tail, exit);
		}
		if (failure != null) {
			log.warn("graphify build for {} failed: {}", id, failure);
			transition(id, entry, new BuildState(Status.FAILED, Instant.now(), previousNodes(entry), previousEdges(entry),
					failure), durationMs);
			return;
		}
		Integer nodes = previousNodes(entry);
		Integer edges = previousEdges(entry);
		for (String line : tail) {
			Matcher m = REBUILT.matcher(line);
			if (m.find()) { // absent on a no-change run: counts carry over
				nodes = Integer.parseInt(m.group(1));
				edges = Integer.parseInt(m.group(2));
			}
		}
		transition(id, entry, new BuildState(Status.READY, Instant.now(), nodes, edges, null), durationMs);
	}

	/**
	 * The most specific complaint in the log tail: graphify's own {@code Rebuild failed: <exc>} or an
	 * {@code error:} line beats its generic "Nothing to update or rebuild failed" trailer, which
	 * beats whatever was printed last.
	 */
	static String failureLine(List<String> tail, int exit) {
		return tail.stream().filter(l -> l.contains("Rebuild failed") || l.startsWith("error")).reduce((a, b) -> b)
				.or(() -> tail.stream().filter(l -> l.contains("failed")).reduce((a, b) -> b))
				.orElse(tail.isEmpty() ? "exit " + exit : tail.getLast());
	}

	/** Decision 7: output outside the worktree, no HTML, no tips, query log off (belt and braces — it's off by default). */
	Map<String, String> buildEnv(Path graphDir) {
		Map<String, String> env = new LinkedHashMap<>();
		env.put("GRAPHIFY_OUT", graphDir.toAbsolutePath().toString());
		env.put("GRAPHIFY_VIZ_NODE_LIMIT", "0");
		env.put("GRAPHIFY_NO_TIPS", "1");
		env.put("GRAPHIFY_QUERY_LOG_DISABLE", "1");
		return env;
	}

	private static Integer previousNodes(Entry entry) {
		synchronized (entry) {
			return entry.state == null ? null : entry.state.nodes();
		}
	}

	private static Integer previousEdges(Entry entry) {
		synchronized (entry) {
			return entry.state == null ? null : entry.state.edges();
		}
	}

	/** Records the new state and journals a {@code code_intel_status} event (docs/PROTOCOL.md) for it. */
	private void transition(UUID id, Entry entry, BuildState state, Long durationMs) {
		synchronized (entry) {
			entry.state = state;
		}
		if (journal == null) {
			return;
		}
		ObjectNode payload = mapper.createObjectNode()
				.put("tool", SettingsService.CODE_INTEL_GRAPHIFY)
				.put("status", state.status().name());
		if (state.nodes() != null) {
			payload.put("nodes", state.nodes());
		}
		if (state.edges() != null) {
			payload.put("edges", state.edges());
		}
		if (durationMs != null) {
			payload.put("durationMs", durationMs);
		}
		if (state.message() != null) {
			payload.put("message", state.message());
		}
		try {
			journal.record(id, "code_intel_status", payload);
		} catch (RuntimeException e) {
			log.warn("could not journal code_intel_status for {}: {}", id, e.getMessage());
		}
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

	private static void deleteRecursively(Path dir) {
		if (!Files.exists(dir)) {
			return;
		}
		try (var walk = Files.walk(dir)) {
			walk.sorted(Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (IOException ignored) {
					// best-effort cleanup
				}
			});
		} catch (IOException e) {
			log.warn("could not remove graph dir {}: {}", dir, e.getMessage());
		}
	}

	private static BuildProcess launch(List<String> command, Map<String, String> env, Path cwd, Path logFile)
			throws IOException {
		ProcessBuilder pb = new ProcessBuilder(command)
				.directory(cwd.toFile())
				.redirectErrorStream(true)
				.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
		pb.environment().putAll(env);
		Process process = pb.start();
		return new BuildProcess() {
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

	/**
	 * Validates explicit {@code root}/{@code uvPath} values (not the persisted setting), so a
	 * {@code PATCH /api/settings} can be checked before it's applied. A blank {@code root} always
	 * passes — clearing must never be blocked here (the controller separately refuses blanking the
	 * root of the *selected* tool). Otherwise: a directory whose {@code pyproject.toml} names
	 * {@code graphifyy}, and {@code … graphify --version} exiting 0 within {@link
	 * #VALIDATE_TIMEOUT_SECONDS} — which is also the first env sync (decision 13). Throws {@link
	 * IllegalArgumentException} (mapped to a 400) with the specific reason.
	 */
	public void validate(String root, String uvPath) {
		if (root == null || root.isBlank()) {
			return;
		}
		Path dir = Path.of(root);
		if (!Files.isDirectory(dir)) {
			throw new IllegalArgumentException("Graphify root is not a directory: " + root);
		}
		Path pyproject = dir.resolve("pyproject.toml");
		if (!Files.isRegularFile(pyproject)) {
			throw new IllegalArgumentException("Graphify root has no pyproject.toml: " + pyproject);
		}
		String content;
		try {
			content = Files.readString(pyproject);
		} catch (IOException e) {
			throw new IllegalArgumentException("could not read " + pyproject + ": " + e.getMessage());
		}
		if (!content.contains("name = \"" + EXPECTED_PACKAGE_NAME + "\"")) {
			throw new IllegalArgumentException(
					pyproject + " does not look like a graphify checkout (expected `name = \""
							+ EXPECTED_PACKAGE_NAME + "\"`)");
		}
		List<String> command = new ArrayList<>(baseCommand(root, uvPath));
		command.add("graphify");
		command.add("--version");
		String rendered = String.join(" ", command);
		GitCommandRunner.GitResult result;
		try {
			result = runner.run(command, VALIDATE_TIMEOUT_SECONDS);
		} catch (ProcessTimedOut e) {
			throw new IllegalArgumentException("`" + rendered + "` did not finish within " + VALIDATE_TIMEOUT_SECONDS
					+ " s — a cold first env sync can take longer; run that exact command once by hand, then save again");
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("uv not found or failed to start: " + uv(uvPath) + " (" + e.getMessage() + ")");
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

	private static String uv(String uvPath) {
		return (uvPath == null || uvPath.isBlank()) ? "uv" : uvPath;
	}

	/**
	 * Unlike {@link SerenaService}'s runner this drains stdout/stderr on their own (virtual)
	 * threads *before* waiting, so the timeout really fires while {@code uv} is still streaming a
	 * long sync — reading a stream to EOF first would block until the process exits on its own.
	 */
	private static GitCommandRunner.GitResult runProcess(List<String> command, int timeoutSeconds) {
		try {
			Process process = new ProcessBuilder(command).start();
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
