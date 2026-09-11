package de.pamir.claude.ui.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * pickDefaultBranch's main/master preference — see docs/plan (Quick Session picking a stray
 * local branch like "ACC-1011-open-items-population" as the base when the repo has no "main"
 * and that branch happened to sort alphabetically before "master").
 *
 * <p>{@code findServices}/{@code repoRootOf}/{@code lastCommitTouching} below (real {@code git
 * init} fixtures under {@code @TempDir}, docs/plan/phase-11-monorepo.md Step 1) exercise layouts
 * (a)/(b)/polyrepo, the submodule edge case, the "no markers = one service" fallback, and the
 * per-subtree staleness SHA.
 */
class GitWorktreeServiceTest {

	private static final List<String> DEFAULT_GLOBS = List.of("packages/*", "services/*", "apps/*", "libs/*");

	private final GitCommandRunner git = new GitCommandRunner();
	private final GitWorktreeService worktrees = new GitWorktreeService(git);

	@Test
	void prefersMainWhenPresent() {
		assertThat(GitWorktreeService.pickDefaultBranch(List.of("ACC-1011-open-items-population", "main", "master")))
				.isEqualTo("main");
	}

	@Test
	void fallsBackToMasterWhenNoMain() {
		assertThat(GitWorktreeService.pickDefaultBranch(List.of("ACC-1011-open-items-population", "master")))
				.isEqualTo("master");
	}

	@Test
	void fallsBackToTheFirstBranchWhenNeitherMainNorMasterExist() {
		assertThat(GitWorktreeService.pickDefaultBranch(List.of("develop", "feature/x")))
				.isEqualTo("develop");
	}

	@Test
	void fallsBackToMainForAnEmptyRepo() {
		assertThat(GitWorktreeService.pickDefaultBranch(List.of())).isEqualTo("main");
	}

	private void initRepo(Path dir) throws IOException {
		Files.createDirectories(dir);
		git.runOrThrow(dir, "init", "-q");
		git.runOrThrow(dir, "config", "user.email", "test@test.local");
		git.runOrThrow(dir, "config", "user.name", "Test");
	}

	private void commit(Path dir, String message) {
		git.runOrThrow(dir, "add", "-A");
		git.runOrThrow(dir, "commit", "-q", "--allow-empty", "-m", message);
	}

	@Test
	void findServicesLayoutAEcosystemRootIsTheMonorepo(@TempDir Path tmp) throws IOException {
		Path mono = tmp.resolve("mono");
		initRepo(mono);
		Files.writeString(mono.resolve("package.json"), "{\"workspaces\": [\"packages/*\"]}");
		Files.createDirectories(mono.resolve("packages/foo"));
		Files.createDirectories(mono.resolve("packages/bar"));
		commit(mono, "init");

		assertThat(worktrees.findServices(mono, DEFAULT_GLOBS)).containsExactlyInAnyOrder(
				new GitWorktreeService.ServiceInfo("packages/bar", mono.resolve("packages/bar").toString(), mono.toString()),
				new GitWorktreeService.ServiceInfo("packages/foo", mono.resolve("packages/foo").toString(), mono.toString()));
	}

	@Test
	void findServicesLayoutBMixesAMonorepoAndAPlainRepo(@TempDir Path tmp) throws IOException {
		Path eco = tmp.resolve("eco");
		Files.createDirectories(eco);
		Path mono = eco.resolve("mono");
		initRepo(mono);
		Files.writeString(mono.resolve("package.json"), "{\"workspaces\": [\"packages/*\"]}");
		Files.createDirectories(mono.resolve("packages/foo"));
		commit(mono, "init");
		Path solo = eco.resolve("solo");
		initRepo(solo);
		commit(solo, "init");

		assertThat(worktrees.findServices(eco, DEFAULT_GLOBS)).containsExactlyInAnyOrder(
				new GitWorktreeService.ServiceInfo("mono/packages/foo", mono.resolve("packages/foo").toString(), mono.toString()),
				new GitWorktreeService.ServiceInfo("solo", solo.toString(), solo.toString()));
	}

	@Test
	void findServicesOnAPolyrepoMatchesFindReposExactlyNameForName(@TempDir Path tmp) throws IOException {
		Path eco = tmp.resolve("eco");
		Files.createDirectories(eco);
		Path foo = eco.resolve("foo");
		initRepo(foo);
		commit(foo, "init");
		Path bar = eco.resolve("bar");
		initRepo(bar);
		commit(bar, "init");

		List<GitWorktreeService.RepoInfo> repos = worktrees.findRepos(eco);
		List<GitWorktreeService.ServiceInfo> services = worktrees.findServices(eco, DEFAULT_GLOBS);

		assertThat(services).hasSameSizeAs(repos);
		for (GitWorktreeService.RepoInfo repo : repos) {
			assertThat(services).anySatisfy(s -> {
				assertThat(s.name()).isEqualTo(repo.name());
				assertThat(s.servicePath()).isEqualTo(repo.path());
				assertThat(s.repoPath()).isEqualTo(repo.path());
			});
		}
	}

	@Test
	void findServicesReportsASubmoduleFolderAsItsOwnRepo(@TempDir Path tmp) throws IOException {
		Path mono = tmp.resolve("mono");
		initRepo(mono);
		Files.writeString(mono.resolve("package.json"), "{\"workspaces\": [\"packages/*\"]}");
		Path sub = mono.resolve("packages/sub");
		initRepo(sub); // a real nested repo stands in for a git submodule (.git present either way)
		commit(sub, "init");
		commit(mono, "init");

		assertThat(worktrees.findServices(mono, DEFAULT_GLOBS)).containsExactly(
				new GitWorktreeService.ServiceInfo("packages/sub", sub.toString(), sub.toString()));
	}

	@Test
	void findServicesOnARepoWithNoWorkspaceMarkersYieldsOneServiceEqualToTheRoot(@TempDir Path tmp) throws IOException {
		Path repo = tmp.resolve("repo");
		initRepo(repo);
		commit(repo, "init");

		// name falls back to the folder's own basename ("repo") rather than an empty relativize()
		// result, since here the ecosystem root passed in IS the one-and-only service.
		assertThat(worktrees.findServices(repo, DEFAULT_GLOBS))
				.containsExactly(new GitWorktreeService.ServiceInfo("repo", repo.toString(), repo.toString()));
	}

	@Test
	void repoRootOfWalksUpFromANestedFolder(@TempDir Path tmp) throws IOException {
		Path repo = tmp.resolve("repo");
		initRepo(repo);
		Files.createDirectories(repo.resolve("packages/foo"));

		assertThat(worktrees.repoRootOf(repo.resolve("packages/foo"))).contains(repo);
	}

	@Test
	void repoRootOfIsEmptyForANonRepoFolder(@TempDir Path tmp) throws IOException {
		Path notARepo = tmp.resolve("not-a-repo");
		Files.createDirectories(notARepo);

		assertThat(worktrees.repoRootOf(notARepo)).isEmpty();
	}

	@Test
	void lastCommitTouchingDiffersPerPackageAfterASinglePackageCommit(@TempDir Path tmp) throws IOException {
		Path mono = tmp.resolve("mono");
		initRepo(mono);
		Files.createDirectories(mono.resolve("packages/foo"));
		Files.createDirectories(mono.resolve("packages/bar"));
		Files.writeString(mono.resolve("packages/foo/a.txt"), "a");
		Files.writeString(mono.resolve("packages/bar/b.txt"), "b");
		commit(mono, "init");
		String fooShaBefore = worktrees.lastCommitTouching(mono, mono.resolve("packages/foo"));
		String barShaBefore = worktrees.lastCommitTouching(mono, mono.resolve("packages/bar"));

		Files.writeString(mono.resolve("packages/bar/b.txt"), "b2");
		commit(mono, "touch bar only");

		assertThat(worktrees.lastCommitTouching(mono, mono.resolve("packages/foo"))).isEqualTo(fooShaBefore);
		assertThat(worktrees.lastCommitTouching(mono, mono.resolve("packages/bar"))).isNotEqualTo(barShaBefore);
	}

	@Test
	void isKnownServiceAlwaysAllowsTheRepoRootItselfEvenWithNoEcosystemConfigured() {
		Path repoRoot = Path.of("/repo");

		assertThat(worktrees.isKnownService(null, DEFAULT_GLOBS, repoRoot, repoRoot)).isTrue();
	}

	@Test
	void isKnownServiceRejectsANonRootPathWhenNoEcosystemIsConfigured() {
		Path repoRoot = Path.of("/repo");
		Path servicePath = Path.of("/repo/packages/foo");

		assertThat(worktrees.isKnownService(null, DEFAULT_GLOBS, repoRoot, servicePath)).isFalse();
	}

	@Test
	void isKnownServiceAcceptsAPackageFoundByFindServicesUnderTheEcosystemRoot(@TempDir Path tmp) throws IOException {
		Path mono = tmp.resolve("mono");
		initRepo(mono);
		Files.writeString(mono.resolve("package.json"), "{\"workspaces\": [\"packages/*\"]}");
		Files.createDirectories(mono.resolve("packages/foo"));
		commit(mono, "init");

		assertThat(worktrees.isKnownService(mono, DEFAULT_GLOBS, mono, mono.resolve("packages/foo"))).isTrue();
		assertThat(worktrees.isKnownService(mono, DEFAULT_GLOBS, mono, mono.resolve("packages/unknown"))).isFalse();
	}
}
