package de.pamir.claude.ui.web;

import de.pamir.claude.ui.config.AppProperties;
import de.pamir.claude.ui.config.Settings;
import de.pamir.claude.ui.config.SettingsService;
import de.pamir.claude.ui.git.GitWorktreeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MetaController#services()} against a faked {@link GitWorktreeService} — locks in the
 * docs/plan/phase-11-monorepo.md Step 3 behavior: a plain polyrepo ecosystem's response shape is
 * unchanged (just the two added fields), a monorepo's packages carry {@code monorepo: true} and a
 * shared {@code repoPath}, and the configured-default-repo fallback goes through the same
 * detection without duplicating anything the ecosystem scan already surfaced.
 */
class MetaControllerTest {

	private static SettingsService fakeSettings(String ecosystemRoot) {
		Settings fixed = new Settings(false, "", ecosystemRoot, "packages/*,services/*,apps/*,libs/*", true, 180,
				"", "", false, true, 60, "claude", "", "", true, false, "cheap", 5, 0, true, true, 14, "cheap");
		return new SettingsService(null, null, null) {
			@Override
			public Settings current() {
				return fixed;
			}
		};
	}

	private static AppProperties propsWithRepo(String repoPath) {
		return new AppProperties(repoPath, "/worktrees", "/skills", "/memory", 4, "", "", "", "logs", 30, 65536,
				1048576, Map.of());
	}

	private static GitWorktreeService fakeWorktrees(Map<String, List<GitWorktreeService.ServiceInfo>> byRoot) {
		return new GitWorktreeService(null) {
			@Override
			public List<ServiceInfo> findServices(Path ecosystemRoot, List<String> fallbackGlobs) {
				return byRoot.getOrDefault(ecosystemRoot.toString(), List.of());
			}
		};
	}

	@Test
	void servicesListsPolyrepoServicesUnchangedInShape() {
		GitWorktreeService worktrees = fakeWorktrees(Map.of("/eco", List.of(
				new GitWorktreeService.ServiceInfo("foo", "/eco/foo", "/eco/foo"),
				new GitWorktreeService.ServiceInfo("bar", "/eco/bar", "/eco/bar"))));
		MetaController controller = new MetaController(propsWithRepo("/does-not-exist"), fakeSettings("/eco"), worktrees);

		MetaController.ServicesResponse response = controller.services();

		assertThat(response.services()).extracting(MetaController.ServiceInfo::name).containsExactly("foo", "bar");
		assertThat(response.services()).allSatisfy(s -> {
			assertThat(s.monorepo()).isFalse();
			assertThat(s.repoPath()).isEqualTo(s.path());
		});
	}

	@Test
	void servicesListsMonorepoPackagesWithTheMonorepoFlagAndSharedRepoPath() {
		GitWorktreeService worktrees = fakeWorktrees(Map.of("/eco", List.of(
				new GitWorktreeService.ServiceInfo("packages/foo", "/eco/packages/foo", "/eco"),
				new GitWorktreeService.ServiceInfo("packages/bar", "/eco/packages/bar", "/eco"))));
		MetaController controller = new MetaController(propsWithRepo("/does-not-exist"), fakeSettings("/eco"), worktrees);

		MetaController.ServicesResponse response = controller.services();

		assertThat(response.services()).extracting(MetaController.ServiceInfo::name)
				.containsExactly("packages/foo", "packages/bar");
		assertThat(response.services()).allSatisfy(s -> {
			assertThat(s.monorepo()).isTrue();
			assertThat(s.repoPath()).isEqualTo("/eco");
		});
	}

	@Test
	void servicesFallsBackToTheConfiguredDefaultRepoWhenNotAlreadyListed(@TempDir Path tmp) throws IOException {
		Files.createDirectories(tmp.resolve(".git"));
		String servicePath = tmp + "/packages/foo";
		GitWorktreeService worktrees = fakeWorktrees(Map.of(tmp.toString(),
				List.of(new GitWorktreeService.ServiceInfo("packages/foo", servicePath, tmp.toString()))));
		MetaController controller = new MetaController(propsWithRepo(tmp.toString()), fakeSettings(""), worktrees);

		MetaController.ServicesResponse response = controller.services();

		assertThat(response.services()).extracting(MetaController.ServiceInfo::name).containsExactly("packages/foo");
		assertThat(response.services().get(0).monorepo()).isTrue();
		assertThat(response.services().get(0).repoPath()).isEqualTo(tmp.toString());
	}

	@Test
	void servicesSkipsTheDefaultRepoFallbackWhenAlreadySurfacedByTheEcosystemScan(@TempDir Path tmp) throws IOException {
		Files.createDirectories(tmp.resolve(".git"));
		String servicePath = tmp + "/packages/foo";
		// The default-repo fallback pass would ALSO find "packages/foo" (named without the "mono/"
		// prefix) if not correctly skipped — proves the dedup checks repoPath, not path, since a
		// monorepo's packages never share their path with the repo root itself.
		GitWorktreeService worktrees = fakeWorktrees(Map.of(
				"/eco", List.of(new GitWorktreeService.ServiceInfo("mono/packages/foo", servicePath, tmp.toString())),
				tmp.toString(), List.of(new GitWorktreeService.ServiceInfo("packages/foo", servicePath, tmp.toString()))));
		MetaController controller = new MetaController(propsWithRepo(tmp.toString()), fakeSettings("/eco"), worktrees);

		MetaController.ServicesResponse response = controller.services();

		assertThat(response.services()).hasSize(1);
		assertThat(response.services().get(0).name()).isEqualTo("mono/packages/foo");
	}
}
