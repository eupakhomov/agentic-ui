package de.pamir.claude.ui.integration;

import de.pamir.claude.ui.config.AppProperties;
import de.pamir.claude.ui.config.Settings;
import de.pamir.claude.ui.config.SettingsService;
import de.pamir.claude.ui.git.GitCommandRunner;
import de.pamir.claude.ui.journal.EventJournal;
import de.pamir.claude.ui.journal.JournalPublisher;
import de.pamir.claude.ui.session.SessionEntity;
import de.pamir.claude.ui.session.SessionState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mirrors {@code SerenaServiceTest}: {@code validate()} is stateless (explicit root/uvPath), so
 * these pass {@code null} settings and a hand-written {@link GraphifyService.ProcessRunner} fake
 * instead of shelling out to {@code uv}. The fake also records the command so the invariant prefix
 * (decision 3/7: {@code --no-dev --extra mcp --extra sql}) and the 180 s budget (decision 13) are
 * pinned by a test, not just by the doc.
 */
class GraphifyServiceTest {

	private static final AppProperties PROPS = new AppProperties("/repo", "/home/u/claude-worktrees", "", "", 4, "",
			"", "", "logs", 30, 65536, 1048576, Map.of());

	private static GraphifyService.ProcessRunner failIfCalled() {
		return (command, timeout) -> {
			throw new AssertionError("should not run a process: " + command);
		};
	}

	private static void writeGraphifyPyproject(Path dir) throws IOException {
		Files.writeString(dir.resolve("pyproject.toml"), "[project]\nname = \"graphifyy\"\nversion = \"0.9.62\"\n");
	}

	// --- validate() ---

	@Test
	void validateIsANoOpForABlankOrNullRoot() {
		GraphifyService graphify = new GraphifyService(null, PROPS, failIfCalled());

		graphify.validate("", "uv");
		graphify.validate(null, "uv");
	}

	@Test
	void validateRejectsARootThatIsNotADirectory(@TempDir Path tmp) {
		GraphifyService graphify = new GraphifyService(null, PROPS, failIfCalled());

		assertThatThrownBy(() -> graphify.validate(tmp.resolve("missing").toString(), "uv"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("not a directory");
	}

	@Test
	void validateRejectsARootWithoutAPyprojectToml(@TempDir Path tmp) {
		GraphifyService graphify = new GraphifyService(null, PROPS, failIfCalled());

		assertThatThrownBy(() -> graphify.validate(tmp.toString(), "uv"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("pyproject.toml");
	}

	@Test
	void validateRejectsAPyprojectThatDoesNotNameGraphifyy(@TempDir Path tmp) throws IOException {
		// the Serena checkout's pyproject must not pass as a graphify root
		Files.writeString(tmp.resolve("pyproject.toml"), "[project]\nname = \"serena-agent\"\n");
		GraphifyService graphify = new GraphifyService(null, PROPS, failIfCalled());

		assertThatThrownBy(() -> graphify.validate(tmp.toString(), "uv"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("graphifyy");
	}

	@Test
	void validateRunsTheVersionProbeWithTheInvariantPrefixAndThe180sBudget(@TempDir Path tmp) throws IOException {
		writeGraphifyPyproject(tmp);
		AtomicReference<List<String>> seen = new AtomicReference<>();
		AtomicReference<Integer> seenTimeout = new AtomicReference<>();
		GraphifyService graphify = new GraphifyService(null, PROPS, (command, timeout) -> {
			seen.set(command);
			seenTimeout.set(timeout);
			return new GitCommandRunner.GitResult(0, "graphify 0.9.62", "");
		});

		graphify.validate(tmp.toString(), "/opt/uv/bin/uv");

		assertThat(seen.get()).containsExactly("/opt/uv/bin/uv", "run", "--directory", tmp.toString(), "--no-dev",
				"--extra", "mcp", "--extra", "sql", "graphify", "--version");
		assertThat(seenTimeout.get()).isEqualTo(180);
	}

	@Test
	void validateDefaultsABlankUvPathToUv(@TempDir Path tmp) throws IOException {
		writeGraphifyPyproject(tmp);
		AtomicReference<List<String>> seen = new AtomicReference<>();
		GraphifyService graphify = new GraphifyService(null, PROPS, (command, timeout) -> {
			seen.set(command);
			return new GitCommandRunner.GitResult(0, "graphify 0.9.62", "");
		});

		graphify.validate(tmp.toString(), " ");

		assertThat(seen.get().getFirst()).isEqualTo("uv");
	}

	@Test
	void validateRejectsWhenTheProbeExitsNonZeroWithTheLastStderrLine(@TempDir Path tmp) throws IOException {
		writeGraphifyPyproject(tmp);
		GraphifyService graphify = new GraphifyService(null, PROPS, (command, timeout) ->
				new GitCommandRunner.GitResult(1, "", "Resolved 112 packages\nerror: Failed to build `nuitka`"));

		assertThatThrownBy(() -> graphify.validate(tmp.toString(), "uv"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("failed: error: Failed to build `nuitka`")
				.hasMessageContaining("graphify --version");
	}

	@Test
	void validateTellsTheUserWhatToRunByHandWhenTheProbeTimesOut(@TempDir Path tmp) throws IOException {
		writeGraphifyPyproject(tmp);
		GraphifyService graphify = new GraphifyService(null, PROPS, (command, timeout) -> {
			throw new GraphifyService.ProcessTimedOut("timed out");
		});

		assertThatThrownBy(() -> graphify.validate(tmp.toString(), "uv"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("did not finish within 180 s")
				.hasMessageContaining("uv run --directory " + tmp + " --no-dev --extra mcp --extra sql graphify --version")
				.hasMessageContaining("by hand");
	}

	@Test
	void validateRejectsWhenUvCannotEvenStart(@TempDir Path tmp) throws IOException {
		writeGraphifyPyproject(tmp);
		GraphifyService graphify = new GraphifyService(null, PROPS, (command, timeout) -> {
			throw new RuntimeException("no such file");
		});

		assertThatThrownBy(() -> graphify.validate(tmp.toString(), "uv"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("uv not found");
	}

	// --- configured()/root()/baseCommand()/graphDir() ---

	@Test
	void configuredReflectsWhetherTheRootSettingIsBlank() {
		MutableSettings settings = new MutableSettings();
		GraphifyService graphify = new GraphifyService(settings, PROPS, failIfCalled());

		assertThat(graphify.configured()).isFalse();

		settings.root = "/mnt/d/projects/graphify";
		assertThat(graphify.configured()).isTrue();
		assertThat(graphify.root()).isEqualTo("/mnt/d/projects/graphify");
		assertThat(graphify.baseCommand()).containsExactly("uv", "run", "--directory", "/mnt/d/projects/graphify",
				"--no-dev", "--extra", "mcp", "--extra", "sql");
	}

	@Test
	void graphDirLivesUnderTheWorktreeRootDotGraphifyPerSession() {
		GraphifyService graphify = new GraphifyService(null, PROPS, failIfCalled());
		UUID id = UUID.fromString("00000000-0000-0000-0000-000000000042");

		assertThat(graphify.graphDir(id))
				.isEqualTo(Path.of("/home/u/claude-worktrees/.graphify/00000000-0000-0000-0000-000000000042"));
		assertThat(graphify.graphFile(id).getFileName().toString()).isEqualTo("graph.json");
	}

	/** Same "fake over mock" shape as {@code SerenaServiceTest.MutableSettings}. */
	private static final class MutableSettings extends SettingsService {
		volatile String root = "";

		MutableSettings() {
			super(null, null, null);
		}

		@Override
		public Settings current() {
			return new Settings(false, "", "", "", true, true, 180, "", "", false, true, 60, "claude", "", "",
					false, false, "cheap", 5, 0, true, false, 14, "cheap", 70, "", "uv", root, "none");
		}
	}

	// --- build pipeline (Step 3) ---

	private final ObjectMapper mapper = new JsonMapper();

	/** Journals into a list — {@code code_intel_status} is the only type this service records. */
	private static final class RecordingJournal extends JournalPublisher {
		final List<JsonNode> events = new ArrayList<>();

		RecordingJournal() {
			super(null, null);
		}

		@Override
		public EventJournal.JournalEvent record(UUID sessionId, String type, JsonNode payload) {
			assertThat(type).isEqualTo("code_intel_status");
			events.add(payload);
			return null;
		}
	}

	/** Runs each submitted build on the calling thread, so tests observe a finished pipeline without waiting. */
	private static final class SameThreadExecutor extends AbstractExecutorService {
		@Override public void execute(Runnable command) { command.run(); }
		@Override public void shutdown() { }
		@Override public List<Runnable> shutdownNow() { return List.of(); }
		@Override public boolean isShutdown() { return false; }
		@Override public boolean isTerminated() { return false; }
		@Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
	}

	/** One scripted launch: what to append to the log, the exit code (null = hang until the timeout), and a hook run while "running". */
	private record Launch(List<String> command, Map<String, String> env, Path cwd, Path logFile) {
	}

	private static final class FakeLauncher implements GraphifyService.BuildLauncher {
		final List<Launch> launches = new ArrayList<>();
		final AtomicInteger kills = new AtomicInteger();
		String output = "[graphify watch] Rebuilt: 12 nodes, 34 edges, 3 communities\n";
		Integer exit = 0;
		/** Invoked from inside waitFor — the one moment a request can arrive "during" a build in a same-thread pipeline. */
		Runnable whileRunning = () -> { };

		@Override
		public GraphifyService.BuildProcess start(List<String> command, Map<String, String> env, Path cwd, Path logFile)
				throws IOException {
			launches.add(new Launch(command, env, cwd, logFile));
			Files.writeString(logFile, output, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
			Files.createDirectories(Path.of(env.get("GRAPHIFY_OUT")));
			if (exit != null && exit == 0) {
				Files.writeString(Path.of(env.get("GRAPHIFY_OUT"), "graph.json"), "{}");
			}
			return new GraphifyService.BuildProcess() {
				@Override
				public Integer waitFor(Duration timeout) {
					whileRunning.run();
					return exit;
				}

				@Override
				public void kill() {
					kills.incrementAndGet();
				}
			};
		}
	}

	private static AppProperties propsIn(Path tmp) {
		return new AppProperties("/repo", tmp.resolve("worktrees").toString(), "", "", 4, "", "", "",
				tmp.resolve("logs").toString(), 30, 65536, 1048576, Map.of());
	}

	private static SessionEntity graphifySession(Path tmp, UUID id) {
		return SessionEntity.builder().id(id).name("s").provider("claude").repoPath("/repo").branch("b").baseBranch("main")
				.worktreePath(tmp.resolve("worktrees").resolve(id.toString()).toString())
				.state(SessionState.IDLE).kind("user").codeIntel("graphify").build();
	}

	private GraphifyService pipeline(Path tmp, RecordingJournal journal, FakeLauncher launcher) {
		MutableSettings settings = new MutableSettings();
		settings.root = "/mnt/d/projects/graphify";
		return new GraphifyService(settings, propsIn(tmp), journal, mapper, failIfCalled(), launcher, new SameThreadExecutor());
	}

	private static List<String> statuses(RecordingJournal journal) {
		return journal.events.stream().map(e -> e.path("status").asText()).toList();
	}

	@Test
	void buildRunsUpdateOnTheCwdWithTheDecision7EnvAndJournalsBuildingThenReadyWithParsedCounts(@TempDir Path tmp) {
		RecordingJournal journal = new RecordingJournal();
		FakeLauncher launcher = new FakeLauncher();
		GraphifyService graphify = pipeline(tmp, journal, launcher);
		UUID id = UUID.randomUUID();
		SessionEntity session = graphifySession(tmp, id);

		graphify.build(session);

		assertThat(launcher.launches).hasSize(1);
		Launch launch = launcher.launches.getFirst();
		assertThat(launch.command()).containsExactly("uv", "run", "--directory", "/mnt/d/projects/graphify", "--no-dev",
				"--extra", "mcp", "--extra", "sql", "graphify", "update", session.cwdPath());
		assertThat(launch.cwd()).isEqualTo(Path.of(session.worktreePath()));
		assertThat(launch.env()).containsEntry("GRAPHIFY_OUT", graphify.graphDir(id).toString())
				.containsEntry("GRAPHIFY_VIZ_NODE_LIMIT", "0")
				.containsEntry("GRAPHIFY_NO_TIPS", "1")
				.containsEntry("GRAPHIFY_QUERY_LOG_DISABLE", "1");
		assertThat(launch.logFile()).isEqualTo(tmp.resolve("logs").resolve("graphify").resolve(id + ".log"));
		assertThat(statuses(journal)).containsExactly("BUILDING", "READY");
		JsonNode ready = journal.events.getLast();
		assertThat(ready.path("tool").asText()).isEqualTo("graphify");
		assertThat(ready.path("nodes").asInt()).isEqualTo(12);
		assertThat(ready.path("edges").asInt()).isEqualTo(34);
		assertThat(ready.has("durationMs")).isTrue();
		assertThat(graphify.status(id).status()).isEqualTo(GraphifyService.Status.READY);
		assertThat(graphify.status(id).nodes()).isEqualTo(12);
	}

	@Test
	void buildIsANoOpForASessionWithoutGraphify(@TempDir Path tmp) {
		RecordingJournal journal = new RecordingJournal();
		FakeLauncher launcher = new FakeLauncher();
		GraphifyService graphify = pipeline(tmp, journal, launcher);

		graphify.build(graphifySession(tmp, UUID.randomUUID()).toBuilder().codeIntel("serena").build());
		graphify.build(graphifySession(tmp, UUID.randomUUID()).toBuilder().codeIntel(null).build());

		assertThat(launcher.launches).isEmpty();
		assertThat(journal.events).isEmpty();
	}

	@Test
	void aNoChangeRunStaysReadyAndKeepsThePreviousCounts(@TempDir Path tmp) {
		RecordingJournal journal = new RecordingJournal();
		FakeLauncher launcher = new FakeLauncher();
		GraphifyService graphify = pipeline(tmp, journal, launcher);
		SessionEntity session = graphifySession(tmp, UUID.randomUUID());
		graphify.build(session);

		launcher.output = "[graphify watch] No code-graph changes detected; graph.json/GRAPH_REPORT.md left untouched.\n";
		graphify.refreshAfterTurn(session);

		assertThat(statuses(journal)).containsExactly("BUILDING", "READY", "BUILDING", "READY");
		assertThat(journal.events.getLast().path("nodes").asInt()).isEqualTo(12);
		assertThat(journal.events.getLast().path("edges").asInt()).isEqualTo(34);
	}

	@Test
	void aNonZeroExitJournalsFailedWithTheFailureLine(@TempDir Path tmp) {
		RecordingJournal journal = new RecordingJournal();
		FakeLauncher launcher = new FakeLauncher();
		launcher.output = "Re-extracting code files in /wt (no LLM needed)...\n[graphify watch] Rebuild failed: boom\n"
				+ "Nothing to update or rebuild failed — check output above.\n";
		launcher.exit = 1;
		GraphifyService graphify = pipeline(tmp, journal, launcher);
		UUID id = UUID.randomUUID();

		graphify.build(graphifySession(tmp, id));

		assertThat(statuses(journal)).containsExactly("BUILDING", "FAILED");
		assertThat(journal.events.getLast().path("message").asText()).contains("Rebuild failed: boom");
		assertThat(graphify.status(id).status()).isEqualTo(GraphifyService.Status.FAILED);
	}

	@Test
	void aHungBuildIsKilledAndJournaledAsTimedOut(@TempDir Path tmp) {
		RecordingJournal journal = new RecordingJournal();
		FakeLauncher launcher = new FakeLauncher();
		launcher.exit = null;
		GraphifyService graphify = pipeline(tmp, journal, launcher);

		graphify.build(graphifySession(tmp, UUID.randomUUID()));

		assertThat(launcher.kills.get()).isEqualTo(1);
		assertThat(statuses(journal)).containsExactly("BUILDING", "FAILED");
		assertThat(journal.events.getLast().path("message").asText()).contains("timed out");
	}

	@Test
	void requestsDuringABuildCoalesceIntoExactlyOneExtraRun(@TempDir Path tmp) {
		RecordingJournal journal = new RecordingJournal();
		FakeLauncher launcher = new FakeLauncher();
		GraphifyService graphify = pipeline(tmp, journal, launcher);
		SessionEntity session = graphifySession(tmp, UUID.randomUUID());
		AtomicInteger firstRun = new AtomicInteger();
		launcher.whileRunning = () -> {
			if (firstRun.getAndIncrement() == 0) { // two turns complete while the first build is running
				graphify.refreshAfterTurn(session);
				graphify.refreshAfterTurn(session);
			}
		};

		graphify.build(session);

		assertThat(launcher.launches).hasSize(2);
		assertThat(statuses(journal)).containsExactly("BUILDING", "READY", "BUILDING", "READY");
	}

	@Test
	void deleteDuringABuildKillsItSuppressesTheRerunAndRemovesTheGraphDir(@TempDir Path tmp) {
		RecordingJournal journal = new RecordingJournal();
		FakeLauncher launcher = new FakeLauncher();
		GraphifyService graphify = pipeline(tmp, journal, launcher);
		UUID id = UUID.randomUUID();
		SessionEntity session = graphifySession(tmp, id);
		launcher.whileRunning = () -> {
			graphify.refreshAfterTurn(session); // would coalesce into a re-run…
			graphify.delete(id); // …but the close wins
		};

		graphify.build(session);

		assertThat(launcher.launches).hasSize(1);
		assertThat(launcher.kills.get()).isEqualTo(1);
		assertThat(statuses(journal)).containsExactly("BUILDING"); // nothing reported for a closed session
		assertThat(graphify.graphDir(id)).doesNotExist();
		assertThat(graphify.status(id)).isNull();
	}

	@Test
	void deleteRemovesAnExistingGraphDirForAnUntrackedSession(@TempDir Path tmp) throws IOException {
		GraphifyService graphify = pipeline(tmp, new RecordingJournal(), new FakeLauncher());
		UUID id = UUID.randomUUID();
		Files.createDirectories(graphify.graphDir(id).resolve("cache"));
		Files.writeString(graphify.graphFile(id), "{}");

		graphify.delete(id);

		assertThat(graphify.graphDir(id)).doesNotExist();
	}

	@Test
	void ensureBuiltJournalsReadyWithoutABuildWhenTheGraphSurvivedARestart(@TempDir Path tmp) throws IOException {
		RecordingJournal journal = new RecordingJournal();
		FakeLauncher launcher = new FakeLauncher();
		GraphifyService graphify = pipeline(tmp, journal, launcher);
		UUID id = UUID.randomUUID();
		Files.createDirectories(graphify.graphDir(id));
		Files.writeString(graphify.graphFile(id), "{}");

		graphify.ensureBuilt(graphifySession(tmp, id));
		graphify.ensureBuilt(graphifySession(tmp, id)); // idempotent once tracked

		assertThat(launcher.launches).isEmpty();
		assertThat(statuses(journal)).containsExactly("READY");
		assertThat(graphify.status(id).status()).isEqualTo(GraphifyService.Status.READY);
	}

	@Test
	void ensureBuiltKicksABuildWhenTheGraphIsMissing(@TempDir Path tmp) {
		RecordingJournal journal = new RecordingJournal();
		FakeLauncher launcher = new FakeLauncher();
		GraphifyService graphify = pipeline(tmp, journal, launcher);

		graphify.ensureBuilt(graphifySession(tmp, UUID.randomUUID()));

		assertThat(launcher.launches).hasSize(1);
		assertThat(statuses(journal)).containsExactly("BUILDING", "READY");
	}

	@Test
	void cleanOrphanGraphsRemovesDirsOfInactiveSessionsOnly(@TempDir Path tmp) throws IOException {
		GraphifyService graphify = pipeline(tmp, new RecordingJournal(), new FakeLauncher());
		UUID active = UUID.randomUUID();
		UUID gone = UUID.randomUUID();
		Files.createDirectories(graphify.graphDir(active));
		Files.createDirectories(graphify.graphDir(gone));
		Path stranger = graphify.graphDir(gone).getParent().resolve("not-a-uuid");
		Files.createDirectories(stranger);

		List<String> removed = graphify.cleanOrphanGraphs(Set.of(active));

		assertThat(removed).containsExactly(graphify.graphDir(gone).toString());
		assertThat(graphify.graphDir(active)).exists();
		assertThat(graphify.graphDir(gone)).doesNotExist();
		assertThat(stranger).exists();
	}

	@Test
	void cleanOrphanGraphsIsANoOpWithoutAGraphifyRootDir(@TempDir Path tmp) {
		GraphifyService graphify = pipeline(tmp, new RecordingJournal(), new FakeLauncher());

		assertThat(graphify.cleanOrphanGraphs(Set.of())).isEmpty();
	}
}
