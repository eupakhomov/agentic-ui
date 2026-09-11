package de.pamir.claude.ui.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixture-tree coverage for every manifest row in docs/plan/phase-11-monorepo.md Step 1's
 * detection table, plus the glob-fallback row and the "no markers" empty-result case. Submodule
 * handling and the "one service = repo root" interpretation of an empty result live in {@link
 * GitWorktreeServiceTest} ({@code findServices}), since {@link ServiceDetector} itself has no
 * concept of a repo.
 */
class ServiceDetectorTest {

	private static final List<String> DEFAULT_GLOBS = List.of("packages/*", "services/*", "apps/*", "libs/*");

	private static void mkdirs(Path... dirs) throws IOException {
		for (Path dir : dirs) {
			Files.createDirectories(dir);
		}
	}

	private static void write(Path file, String content) throws IOException {
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
	}

	@Test
	void npmArrayWorkspaces(@TempDir Path tmp) throws IOException {
		write(tmp.resolve("package.json"), "{\"workspaces\": [\"packages/*\"]}");
		mkdirs(tmp.resolve("packages/foo"), tmp.resolve("packages/bar"));

		assertThat(ServiceDetector.detect(tmp, DEFAULT_GLOBS))
				.containsExactly(tmp.resolve("packages/bar"), tmp.resolve("packages/foo"));
	}

	@Test
	void yarnObjectWorkspaces(@TempDir Path tmp) throws IOException {
		write(tmp.resolve("package.json"), "{\"workspaces\": {\"packages\": [\"packages/*\"], \"nohoist\": []}}");
		mkdirs(tmp.resolve("packages/foo"), tmp.resolve("packages/bar"));

		assertThat(ServiceDetector.detect(tmp, DEFAULT_GLOBS))
				.containsExactly(tmp.resolve("packages/bar"), tmp.resolve("packages/foo"));
	}

	@Test
	void pnpmWorkspaceWithExclusion(@TempDir Path tmp) throws IOException {
		write(tmp.resolve("pnpm-workspace.yaml"), """
				packages:
				  - 'packages/*'
				  - '!packages/excluded'
				""");
		mkdirs(tmp.resolve("packages/foo"), tmp.resolve("packages/bar"), tmp.resolve("packages/excluded"));

		assertThat(ServiceDetector.detect(tmp, DEFAULT_GLOBS))
				.containsExactly(tmp.resolve("packages/bar"), tmp.resolve("packages/foo"));
	}

	@Test
	void mavenModules(@TempDir Path tmp) throws IOException {
		write(tmp.resolve("pom.xml"), """
				<project>
				  <modules>
				    <module>module-a</module>
				    <module>module-b</module>
				  </modules>
				</project>
				""");
		mkdirs(tmp.resolve("module-a"), tmp.resolve("module-b"));

		assertThat(ServiceDetector.detect(tmp, DEFAULT_GLOBS))
				.containsExactly(tmp.resolve("module-a"), tmp.resolve("module-b"));
	}

	@Test
	void mavenModulePointingAtAPomFileTakesItsParentDir(@TempDir Path tmp) throws IOException {
		write(tmp.resolve("pom.xml"), "<project><modules><module>module-a/pom.xml</module></modules></project>");
		mkdirs(tmp.resolve("module-a"));

		assertThat(ServiceDetector.detect(tmp, DEFAULT_GLOBS)).containsExactly(tmp.resolve("module-a"));
	}

	@Test
	void gradleGroovyInclude(@TempDir Path tmp) throws IOException {
		write(tmp.resolve("settings.gradle"), "include ':a', ':b:c'\n");
		mkdirs(tmp.resolve("a"), tmp.resolve("b/c"));

		assertThat(ServiceDetector.detect(tmp, DEFAULT_GLOBS))
				.containsExactly(tmp.resolve("a"), tmp.resolve("b/c"));
	}

	@Test
	void gradleKotlinInclude(@TempDir Path tmp) throws IOException {
		write(tmp.resolve("settings.gradle.kts"), "include(\":a\", \":b:c\")\n");
		mkdirs(tmp.resolve("a"), tmp.resolve("b/c"));

		assertThat(ServiceDetector.detect(tmp, DEFAULT_GLOBS))
				.containsExactly(tmp.resolve("a"), tmp.resolve("b/c"));
	}

	@Test
	void cargoMultiLineMembersWithExclude(@TempDir Path tmp) throws IOException {
		write(tmp.resolve("Cargo.toml"), """
				[workspace]
				members = [
				    "crates/a",
				    "crates/b",
				]
				exclude = ["crates/c"]
				""");
		mkdirs(tmp.resolve("crates/a"), tmp.resolve("crates/b"), tmp.resolve("crates/c"));

		assertThat(ServiceDetector.detect(tmp, DEFAULT_GLOBS))
				.containsExactly(tmp.resolve("crates/a"), tmp.resolve("crates/b"));
	}

	@Test
	void goWorkBlockForm(@TempDir Path tmp) throws IOException {
		write(tmp.resolve("go.work"), """
				go 1.21

				use (
					./a
					./b
				)
				""");
		mkdirs(tmp.resolve("a"), tmp.resolve("b"));

		assertThat(ServiceDetector.detect(tmp, DEFAULT_GLOBS)).containsExactly(tmp.resolve("a"), tmp.resolve("b"));
	}

	@Test
	void goWorkSingleLineForm(@TempDir Path tmp) throws IOException {
		write(tmp.resolve("go.work"), "use ./a\nuse ./b\n");
		mkdirs(tmp.resolve("a"), tmp.resolve("b"));

		assertThat(ServiceDetector.detect(tmp, DEFAULT_GLOBS)).containsExactly(tmp.resolve("a"), tmp.resolve("b"));
	}

	@Test
	void globFallbackKeepsOnlyFoldersWithAManifestMarker(@TempDir Path tmp) throws IOException {
		write(tmp.resolve("packages/foo/package.json"), "{}");
		mkdirs(tmp.resolve("packages/bar")); // no manifest inside -> dropped

		assertThat(ServiceDetector.detect(tmp, DEFAULT_GLOBS)).containsExactly(tmp.resolve("packages/foo"));
	}

	@Test
	void noWorkspaceMarkersYieldsAnEmptyList(@TempDir Path tmp) {
		assertThat(ServiceDetector.detect(tmp, DEFAULT_GLOBS)).isEmpty();
	}

	@Test
	void aManifestThatParsesToZeroFoldersFallsThroughToTheNextRow(@TempDir Path tmp) throws IOException {
		// package.json exists but declares no workspaces glob that matches anything real
		write(tmp.resolve("package.json"), "{\"workspaces\": [\"nothing-here/*\"]}");
		write(tmp.resolve("pom.xml"), "<project><modules><module>module-a</module></modules></project>");
		mkdirs(tmp.resolve("module-a"));

		assertThat(ServiceDetector.detect(tmp, DEFAULT_GLOBS)).containsExactly(tmp.resolve("module-a"));
	}
}
