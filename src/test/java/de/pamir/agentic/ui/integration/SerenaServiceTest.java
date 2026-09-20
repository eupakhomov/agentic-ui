package de.pamir.agentic.ui.integration;

import de.pamir.agentic.ui.config.Settings;
import de.pamir.agentic.ui.config.SettingsService;
import de.pamir.agentic.ui.git.GitCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code validate()} is stateless (explicit root/uvPath, never touches SettingsService), so most
 * of these tests pass {@code null} for settings and a hand-written {@link SerenaService.ProcessRunner}
 * fake instead of shelling out to a real {@code uv} (this file's "fake over mock" convention — see
 * {@code SettingsServiceTest}/{@code TicketImportServiceTest}). {@link #ccSystemPromptOverride}'s
 * memoization tests use a small mutable {@link SettingsService} subclass to simulate the root
 * setting changing between calls.
 */
class SerenaServiceTest {

	private static SerenaService.ProcessRunner failIfCalled() {
		return command -> {
			throw new AssertionError("should not run a process: " + command);
		};
	}

	// --- validate() ---

	@Test
	void validateIsANoOpForABlankOrNullRoot() {
		SerenaService serena = new SerenaService(null, failIfCalled());

		serena.validate("", "uv");
		serena.validate(null, "uv");
	}

	@Test
	void validateRejectsARootThatIsNotADirectory(@TempDir Path tmp) {
		SerenaService serena = new SerenaService(null, failIfCalled());
		Path notADir = tmp.resolve("missing");

		assertThatThrownBy(() -> serena.validate(notADir.toString(), "uv"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("not a directory");
	}

	@Test
	void validateRejectsARootWithoutAPyprojectToml(@TempDir Path tmp) {
		SerenaService serena = new SerenaService(null, failIfCalled());

		assertThatThrownBy(() -> serena.validate(tmp.toString(), "uv"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("pyproject.toml");
	}

	@Test
	void validateRejectsAPyprojectThatDoesNotNameSerenaAgent(@TempDir Path tmp) throws IOException {
		Files.writeString(tmp.resolve("pyproject.toml"), "[project]\nname = \"something-else\"\n");
		SerenaService serena = new SerenaService(null, failIfCalled());

		assertThatThrownBy(() -> serena.validate(tmp.toString(), "uv"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("serena-agent");
	}

	@Test
	void validatePassesWithAMatchingPyprojectAndAWorkingUvProbe(@TempDir Path tmp) throws IOException {
		Files.writeString(tmp.resolve("pyproject.toml"), "[project]\nname = \"serena-agent\"\n");
		SerenaService serena = new SerenaService(null, command -> new GitCommandRunner.GitResult(0, "uv 0.12.13", ""));

		serena.validate(tmp.toString(), "uv");
	}

	@Test
	void validateRejectsWhenTheUvProbeExitsNonZero(@TempDir Path tmp) throws IOException {
		Files.writeString(tmp.resolve("pyproject.toml"), "[project]\nname = \"serena-agent\"\n");
		SerenaService serena = new SerenaService(null, command -> new GitCommandRunner.GitResult(1, "", "command not found"));

		assertThatThrownBy(() -> serena.validate(tmp.toString(), "uv"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("command not found");
	}

	@Test
	void validateRejectsWhenUvCannotEvenStart(@TempDir Path tmp) throws IOException {
		Files.writeString(tmp.resolve("pyproject.toml"), "[project]\nname = \"serena-agent\"\n");
		SerenaService serena = new SerenaService(null, command -> {
			throw new RuntimeException("no such file");
		});

		assertThatThrownBy(() -> serena.validate(tmp.toString(), "uv"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("uv not found");
	}

	// --- configured()/root()/uvCommand() ---

	@Test
	void configuredReflectsWhetherTheRootSettingIsBlank() {
		MutableSettings settings = new MutableSettings();
		SerenaService serena = new SerenaService(settings, failIfCalled());

		assertThat(serena.configured()).isFalse();

		settings.root = "/mnt/d/projects/serena";
		assertThat(serena.configured()).isTrue();
		assertThat(serena.root()).isEqualTo("/mnt/d/projects/serena");
	}

	// --- ccSystemPromptOverride() memoization ---

	@Test
	void ccSystemPromptOverrideReturnsNullWithoutRunningAnythingWhenNotConfigured() {
		MutableSettings settings = new MutableSettings();
		AtomicInteger calls = new AtomicInteger();
		SerenaService serena = new SerenaService(settings, command -> {
			calls.incrementAndGet();
			return new GitCommandRunner.GitResult(0, "", "");
		});

		assertThat(serena.ccSystemPromptOverride()).isNull();
		assertThat(calls.get()).isZero();
	}

	@Test
	void ccSystemPromptOverrideMemoizesPerRootAndRecapturesWhenRootChanges() {
		MutableSettings settings = new MutableSettings();
		settings.root = "/root-a";
		AtomicInteger calls = new AtomicInteger();
		SerenaService.ProcessRunner runner = command -> {
			calls.incrementAndGet();
			return new GitCommandRunner.GitResult(0, "override for " + command.get(3), "");
		};
		SerenaService serena = new SerenaService(settings, runner);

		assertThat(serena.ccSystemPromptOverride()).isEqualTo("override for /root-a");
		assertThat(serena.ccSystemPromptOverride()).isEqualTo("override for /root-a");
		assertThat(calls.get()).isEqualTo(1);

		settings.root = "/root-b";
		assertThat(serena.ccSystemPromptOverride()).isEqualTo("override for /root-b");
		assertThat(calls.get()).isEqualTo(2);
	}

	@Test
	void ccSystemPromptOverrideReturnsNullOnAFailedCaptureWithoutThrowing() {
		MutableSettings settings = new MutableSettings();
		settings.root = "/root-a";
		SerenaService serena = new SerenaService(settings, command -> new GitCommandRunner.GitResult(1, "", "boom"));

		assertThat(serena.ccSystemPromptOverride()).isNull();
	}

	/** A {@link SettingsService} whose {@code current()} reflects mutable fields, for the
	 * memoization tests above — same "fake over mock" style as {@code SessionConfigFactoryTest}'s
	 * fixed-snapshot subclasses, but mutable since these tests need the root to change mid-test. */
	private static final class MutableSettings extends SettingsService {
		volatile String root = "";
		volatile String uvPath = "uv";

		MutableSettings() {
			super(null, null, null);
		}

		@Override
		public Settings current() {
			return new Settings(false, "", "", "", true, true, 180, "", "", false, true, 60, "claude", "", "",
					false, false, "cheap", 5, 0, true, false, 14, "cheap", 70, root, uvPath, "", "", "none");
		}
	}
}
