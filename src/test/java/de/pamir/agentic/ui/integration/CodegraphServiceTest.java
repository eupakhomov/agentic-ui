package de.pamir.agentic.ui.integration;

import de.pamir.agentic.ui.config.AppProperties;
import de.pamir.agentic.ui.config.Settings;
import de.pamir.agentic.ui.config.SettingsService;
import de.pamir.agentic.ui.git.GitCommandRunner;
import de.pamir.agentic.ui.journal.EventJournal;
import de.pamir.agentic.ui.journal.JournalPublisher;
import de.pamir.agentic.ui.session.SessionEntity;
import de.pamir.agentic.ui.session.SessionState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mirrors {@code SerenaServiceTest}/{@code GraphifyServiceTest}: {@code validate()} is stateless
 * (an explicit root, never touches {@code SettingsService}), so these pass {@code null} settings
 * and a hand-written {@link CodegraphService.ProcessRunner} fake instead of shelling out to
 * {@code node}. Unlike both — decision 2 — {@link #index}/{@link #ensureIndexed} run synchronously
 * on the calling thread, so the "build pipeline" tests here need no executor fake.
 */
class CodegraphServiceTest {

	private static final AppProperties PROPS = new AppProperties("/repo", "/home/u/agentic-worktrees", "", "", 4, "",
			"", "", "logs", 30, 65536, 1048576, Map.of());

	private static CodegraphService.ProcessRunner failIfCalled() {
		return (command, env, timeout) -> {
			throw new AssertionError("should not run a process: " + command);
		};
	}

	private static void writePackageJson(Path dir) throws IOException {
		Files.writeString(dir.resolve("package.json"), "{\n  \"name\": \"@colbymchenry/codegraph\",\n  \"version\": \"1.6.0\"\n}\n");
	}

	private static void writeBuiltCheckout(Path dir) throws IOException {
		writePackageJson(dir);
		Files.createDirectories(dir.resolve("dist/bin"));
		Files.writeString(dir.resolve("dist/bin/codegraph.js"), "// stub\n");
	}

	// --- validate() ---

	@Test
	void validateIsANoOpForABlankOrNullRoot() {
		CodegraphService codegraph = new CodegraphService(null, PROPS, failIfCalled());

		codegraph.validate("");
		codegraph.validate(null);
	}

	@Test
	void validateRejectsARootThatIsNotADirectory(@TempDir Path tmp) {
		CodegraphService codegraph = new CodegraphService(null, PROPS, failIfCalled());

		assertThatThrownBy(() -> codegraph.validate(tmp.resolve("missing").toString()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("not a directory");
	}

	@Test
	void validateRejectsARootWithoutAPackageJson(@TempDir Path tmp) {
		CodegraphService codegraph = new CodegraphService(null, PROPS, failIfCalled());

		assertThatThrownBy(() -> codegraph.validate(tmp.toString()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("package.json");
	}

	@Test
	void validateRejectsAPackageJsonThatDoesNotNameCodegraph(@TempDir Path tmp) throws IOException {
		Files.writeString(tmp.resolve("package.json"), "{\"name\": \"something-else\"}");
		CodegraphService codegraph = new CodegraphService(null, PROPS, failIfCalled());

		assertThatThrownBy(() -> codegraph.validate(tmp.toString()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("@colbymchenry/codegraph");
	}

	@Test
	void validateRejectsAnUnbuiltCheckoutNamingTheBuildCommands(@TempDir Path tmp) throws IOException {
		writePackageJson(tmp);
		CodegraphService codegraph = new CodegraphService(null, PROPS, failIfCalled());

		assertThatThrownBy(() -> codegraph.validate(tmp.toString()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("npm ci --ignore-scripts && npx tsc && npm run copy-assets");
	}

	@Test
	void validateRunsTheVersionProbeOnNodeWithThePostureEnvAnd30sBudget(@TempDir Path tmp) throws IOException {
		writeBuiltCheckout(tmp);
		AtomicReference<List<String>> seenCommand = new AtomicReference<>();
		AtomicReference<Map<String, String>> seenEnv = new AtomicReference<>();
		AtomicReference<Integer> seenTimeout = new AtomicReference<>();
		CodegraphService codegraph = new CodegraphService(null, PROPS, (command, env, timeout) -> {
			seenCommand.set(command);
			seenEnv.set(env);
			seenTimeout.set(timeout);
			return new GitCommandRunner.GitResult(0, "1.6.0", "");
		});

		codegraph.validate(tmp.toString());

		assertThat(seenCommand.get()).containsExactly("node", tmp + "/dist/bin/codegraph.js", "version");
		assertThat(seenEnv.get()).containsEntry("CODEGRAPH_TELEMETRY", "0").containsEntry("DO_NOT_TRACK", "1")
				.containsEntry("CODEGRAPH_NO_UPDATE_CHECK", "1").containsEntry("CODEGRAPH_NO_DAEMON", "1");
		assertThat(seenTimeout.get()).isEqualTo(30);
	}

	@Test
	void validateRejectsWhenTheProbeExitsNonZero(@TempDir Path tmp) throws IOException {
		writeBuiltCheckout(tmp);
		CodegraphService codegraph = new CodegraphService(null, PROPS, (command, env, timeout) ->
				new GitCommandRunner.GitResult(1, "", "command not found"));

		assertThatThrownBy(() -> codegraph.validate(tmp.toString()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("command not found");
	}

	@Test
	void validateReportsATimeout(@TempDir Path tmp) throws IOException {
		writeBuiltCheckout(tmp);
		CodegraphService codegraph = new CodegraphService(null, PROPS, (command, env, timeout) -> {
			throw new CodegraphService.ProcessTimedOut("timed out");
		});

		assertThatThrownBy(() -> codegraph.validate(tmp.toString()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("did not finish within 30 s");
	}

	@Test
	void validateRejectsWhenNodeCannotEvenStart(@TempDir Path tmp) throws IOException {
		writeBuiltCheckout(tmp);
		CodegraphService codegraph = new CodegraphService(null, PROPS, (command, env, timeout) -> {
			throw new RuntimeException("no such file");
		});

		assertThatThrownBy(() -> codegraph.validate(tmp.toString()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("node not found");
	}

	// --- configured()/root()/baseCommand()/indexDir() ---

	@Test
	void configuredReflectsWhetherTheRootSettingIsBlank() {
		MutableSettings settings = new MutableSettings();
		CodegraphService codegraph = new CodegraphService(settings, PROPS, failIfCalled());

		assertThat(codegraph.configured()).isFalse();

		settings.root = "/mnt/d/projects/codegraph";
		assertThat(codegraph.configured()).isTrue();
		assertThat(codegraph.root()).isEqualTo("/mnt/d/projects/codegraph");
		assertThat(codegraph.baseCommand()).containsExactly("node", "/mnt/d/projects/codegraph/dist/bin/codegraph.js");
	}

	@Test
	void indexDirLivesInsideTheCwdUnlikeGraphifysOutOfTreeDir() {
		CodegraphService codegraph = new CodegraphService(null, PROPS, failIfCalled());

		assertThat(codegraph.indexDir("/worktree")).isEqualTo(Path.of("/worktree/.codegraph"));
		assertThat(codegraph.indexDbFile("/worktree")).isEqualTo(Path.of("/worktree/.codegraph/codegraph.db"));
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
					false, false, "cheap", 5, 0, true, false, 14, "cheap", 70, "", "uv", "", root, "none");
		}
	}

	// --- index()/ensureIndexed() (Step 2) ---

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

	/** One scripted launch, recorded for assertions. */
	private record Launch(List<String> command, Map<String, String> env, Path cwd, Path logFile) {
	}

	private static final class FakeLauncher implements CodegraphService.IndexLauncher {
		final List<Launch> launches = new ArrayList<>();
		final AtomicInteger kills = new AtomicInteger();
		String output = "209 files -> 5,466 nodes, 11,788 edges in 0.8s\n";
		Integer exit = 0;
		boolean hang = false;

		@Override
		public CodegraphService.IndexProcess start(List<String> command, Map<String, String> env, Path cwd, Path logFile)
				throws IOException {
			launches.add(new Launch(command, env, cwd, logFile));
			if (!hang) {
				Files.writeString(logFile, output, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			}
			return new CodegraphService.IndexProcess() {
				@Override
				public Integer waitFor(Duration timeout) {
					return hang ? null : exit;
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

	private static SessionEntity codegraphSession(Path tmp, UUID id) {
		return SessionEntity.builder().id(id).name("s").provider("claude").repoPath("/repo").branch("b").baseBranch("main")
				.worktreePath(tmp.resolve("worktrees").resolve(id.toString()).toString())
				.state(SessionState.IDLE).kind("user").codeIntel("codegraph").build();
	}

	private CodegraphService pipeline(Path tmp, RecordingJournal journal, FakeLauncher launcher) {
		MutableSettings settings = new MutableSettings();
		settings.root = "/mnt/d/projects/codegraph";
		return new CodegraphService(settings, propsIn(tmp), journal, mapper, failIfCalled(), launcher);
	}

	private static List<String> statuses(RecordingJournal journal) {
		return journal.events.stream().map(e -> e.path("status").asText()).toList();
	}

	@Test
	void indexRunsInitOnTheCwdWithThePostureEnvAndJournalsBuildingThenReadyWithParsedCounts(@TempDir Path tmp) {
		RecordingJournal journal = new RecordingJournal();
		FakeLauncher launcher = new FakeLauncher();
		CodegraphService codegraph = pipeline(tmp, journal, launcher);
		UUID id = UUID.randomUUID();
		SessionEntity session = codegraphSession(tmp, id);

		codegraph.index(session);

		assertThat(launcher.launches).hasSize(1);
		Launch launch = launcher.launches.getFirst();
		assertThat(launch.command()).containsExactly("node", "/mnt/d/projects/codegraph/dist/bin/codegraph.js",
				"init", session.cwdPath(), "--yes");
		assertThat(launch.cwd()).isEqualTo(Path.of(session.cwdPath()));
		assertThat(launch.env()).containsEntry("CODEGRAPH_TELEMETRY", "0").containsEntry("DO_NOT_TRACK", "1")
				.containsEntry("CODEGRAPH_NO_UPDATE_CHECK", "1").containsEntry("CODEGRAPH_NO_DAEMON", "1")
				.containsEntry("NO_COLOR", "1").containsEntry("CI", "1");
		assertThat(launch.logFile()).isEqualTo(tmp.resolve("logs").resolve("codegraph").resolve(id + ".log"));
		assertThat(statuses(journal)).containsExactly("BUILDING", "READY");
		JsonNode ready = journal.events.getLast();
		assertThat(ready.path("tool").asText()).isEqualTo("codegraph");
		assertThat(ready.path("nodes").asInt()).isEqualTo(5466);
		assertThat(ready.path("edges").asInt()).isEqualTo(11788);
		assertThat(ready.has("durationMs")).isTrue();
	}

	@Test
	void indexJournalsFailedWithTheLastOutputLineOnANonZeroExit(@TempDir Path tmp) {
		RecordingJournal journal = new RecordingJournal();
		FakeLauncher launcher = new FakeLauncher();
		launcher.output = "some progress\nerror: could not parse foo.ts\n";
		launcher.exit = 1;
		CodegraphService codegraph = pipeline(tmp, journal, launcher);
		SessionEntity session = codegraphSession(tmp, UUID.randomUUID());

		codegraph.index(session);

		assertThat(statuses(journal)).containsExactly("BUILDING", "FAILED");
		assertThat(journal.events.getLast().path("message").asText()).contains("could not parse foo.ts");
	}

	@Test
	void indexJournalsFailedAndKillsTheProcessOnATimeout(@TempDir Path tmp) {
		RecordingJournal journal = new RecordingJournal();
		FakeLauncher launcher = new FakeLauncher();
		launcher.hang = true;
		CodegraphService codegraph = pipeline(tmp, journal, launcher);
		SessionEntity session = codegraphSession(tmp, UUID.randomUUID());

		codegraph.index(session);

		assertThat(statuses(journal)).containsExactly("BUILDING", "FAILED");
		assertThat(journal.events.getLast().path("message").asText()).contains("timed out after 5 min");
		assertThat(launcher.kills.get()).isEqualTo(1);
	}

	@Test
	void indexNeverThrows(@TempDir Path tmp) {
		RecordingJournal journal = new RecordingJournal();
		CodegraphService codegraph = new CodegraphService(mutableSettingsWithRoot(), propsIn(tmp), journal, mapper,
				failIfCalled(), (command, env, cwd, logFile) -> {
					throw new IOException("no such file or directory");
				});
		SessionEntity session = codegraphSession(tmp, UUID.randomUUID());

		codegraph.index(session); // must not throw

		assertThat(statuses(journal)).containsExactly("BUILDING", "FAILED");
		assertThat(journal.events.getLast().path("message").asText()).contains("could not start");
	}

	@Test
	void ensureIndexedSkipsAFreshIndexAndJournalsReadyWithoutRunningInit(@TempDir Path tmp) throws IOException {
		RecordingJournal journal = new RecordingJournal();
		FakeLauncher launcher = new FakeLauncher();
		CodegraphService codegraph = pipeline(tmp, journal, launcher);
		UUID id = UUID.randomUUID();
		SessionEntity session = codegraphSession(tmp, id);
		Files.createDirectories(codegraph.indexDir(session.cwdPath()));
		Files.writeString(codegraph.indexDbFile(session.cwdPath()), "not really sqlite");

		codegraph.ensureIndexed(session);

		assertThat(launcher.launches).isEmpty();
		assertThat(statuses(journal)).containsExactly("READY");
	}

	@Test
	void ensureIndexedRunsInitWhenTheDbFileIsMissing(@TempDir Path tmp) {
		RecordingJournal journal = new RecordingJournal();
		FakeLauncher launcher = new FakeLauncher();
		CodegraphService codegraph = pipeline(tmp, journal, launcher);
		SessionEntity session = codegraphSession(tmp, UUID.randomUUID());

		codegraph.ensureIndexed(session);

		assertThat(launcher.launches).hasSize(1);
		assertThat(statuses(journal)).containsExactly("BUILDING", "READY");
	}

	private static MutableSettings mutableSettingsWithRoot() {
		MutableSettings settings = new MutableSettings();
		settings.root = "/mnt/d/projects/codegraph";
		return settings;
	}
}
