package de.pamir.claude.ui.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Filesystem-only, no Spring/DB — see docs/plan/phase-9-production-hardening.md T1. */
class MemoryPathsTest {

	private final MemoryPaths paths = new MemoryPaths();

	@TempDir
	Path tempDir;

	private String root() {
		return tempDir.toString();
	}

	@Test
	void ecosystemAndServicesRootAreFixedSubdirs() {
		assertThat(paths.ecosystemDir(root())).isEqualTo(tempDir.resolve("ecosystem"));
		assertThat(paths.servicesRoot(root())).isEqualTo(tempDir.resolve("services"));
	}

	@Test
	void serviceDirCreatesASlugDirWithMarkerAndIsStableForTheSameRepo() throws Exception {
		Path dir1 = paths.serviceDir(root(), "/home/user/projects/my-service");

		assertThat(Files.isDirectory(dir1)).isTrue();
		assertThat(dir1.getFileName().toString()).isEqualTo("my-service");
		assertThat(Files.readString(dir1.resolve(".repo-path"))).isEqualTo("/home/user/projects/my-service");

		Path dir2 = paths.serviceDir(root(), "/home/user/projects/my-service");
		assertThat(dir2).isEqualTo(dir1);
	}

	@Test
	void serviceDirDisambiguatesABasenameCollisionWithAHashSuffix() {
		Path dirA = paths.serviceDir(root(), "/home/alice/projects/shared-name");
		Path dirB = paths.serviceDir(root(), "/home/bob/projects/shared-name");

		assertThat(dirB).isNotEqualTo(dirA);
		assertThat(dirB.getFileName().toString()).startsWith("shared-name-");

		// repeated call for the same (later) repo path returns the same fallback dir
		assertThat(paths.serviceDir(root(), "/home/bob/projects/shared-name")).isEqualTo(dirB);
	}

	@Test
	void relPathIsForwardSlashRelativeToTheVaultRoot() {
		Path file = tempDir.resolve("services").resolve("my-service").resolve("foo.md");

		assertThat(paths.relPath(root(), file)).isEqualTo("services/my-service/foo.md");
	}

	@Test
	void resolveRoundTripsWithRelPath() {
		Path file = tempDir.resolve("services").resolve("x").resolve("y.md");

		assertThat(paths.resolve(root(), paths.relPath(root(), file))).isEqualTo(file);
	}
}
