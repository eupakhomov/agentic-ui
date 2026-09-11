package de.pamir.claude.ui.discovery;

import de.pamir.claude.ui.config.Settings;
import de.pamir.claude.ui.config.SettingsService;
import de.pamir.claude.ui.git.GitCommandRunner;
import de.pamir.claude.ui.git.GitWorktreeService;
import de.pamir.claude.ui.library.EmbeddingClient;
import de.pamir.claude.ui.session.SystemTurnClient;
import de.pamir.claude.ui.session.SystemTurnLane;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ServiceDiscoveryService} against a real {@link GitWorktreeService} (real {@code git
 * init}/commits under {@code @TempDir}, decision 6's per-subtree staleness) and in-memory fakes
 * for everything else — no DB, no real system turn. See docs/plan/phase-11-monorepo.md Step 5.
 */
class ServiceDiscoveryServiceTest {

	private static void initRepo(Path dir, GitCommandRunner git) throws IOException {
		Files.createDirectories(dir);
		git.runOrThrow(dir, "init", "-q");
		git.runOrThrow(dir, "config", "user.email", "test@test.local");
		git.runOrThrow(dir, "config", "user.name", "Test");
	}

	private static void commit(Path dir, String message, GitCommandRunner git) {
		git.runOrThrow(dir, "add", "-A");
		git.runOrThrow(dir, "commit", "-q", "--allow-empty", "-m", message);
	}

	private static SettingsService fakeSettings(String ecosystemRoot) {
		Settings fixed = new Settings(false, "", ecosystemRoot, "packages/*,services/*,apps/*,libs/*", true, 180,
				"", "", false, true, 60, "claude", "", "", false, false, "cheap", 5, 0, true, true, 14, "cheap");
		return new SettingsService(null, null, null) {
			@Override
			public Settings current() {
				return fixed;
			}
		};
	}

	private static SystemTurnClient countingTurnClient(AtomicInteger calls) {
		ObjectMapper mapper = new JsonMapper();
		return new SystemTurnClient(null, mapper) {
			@Override
			public JsonNode json(String prompt, String modelOverride, SystemTurnLane lane, Duration timeout) {
				calls.incrementAndGet();
				ObjectNode node = mapper.createObjectNode();
				node.put("description", "a generated description");
				node.putArray("tags").add("tag");
				return node;
			}
		};
	}

	private static final class NoopEmbeddingClient implements EmbeddingClient {
		@Override
		public boolean configured() {
			return false;
		}

		@Override
		public float[] embed(String text, boolean query) {
			throw new UnsupportedOperationException();
		}

		@Override
		public String model() {
			return "none";
		}
	}

	private static final class FakeServiceProfileRepository extends ServiceProfileRepository {
		private final Map<String, ServiceProfile> byServicePath = new HashMap<>();
		final List<String> bumped = new java.util.ArrayList<>();

		FakeServiceProfileRepository() {
			super(null);
		}

		@Override
		public Optional<ServiceProfile> findByServicePath(String servicePath) {
			return Optional.ofNullable(byServicePath.get(servicePath));
		}

		@Override
		public ServiceProfile upsert(String servicePath, String repoPath, String name, String description,
									  List<String> tags, String lastCommitSha, UUID sessionId) {
			ServiceProfile p = new ServiceProfile(UUID.randomUUID(), servicePath, repoPath, name, description, tags,
					lastCommitSha, Instant.now(), sessionId, Instant.now(), Instant.now());
			byServicePath.put(servicePath, p);
			return p;
		}

		@Override
		public void bumpDiscoveredAt(String servicePath) {
			bumped.add(servicePath);
			ServiceProfile existing = byServicePath.get(servicePath);
			if (existing != null) {
				byServicePath.put(servicePath, new ServiceProfile(existing.id(), existing.servicePath(),
						existing.repoPath(), existing.name(), existing.description(), existing.tags(),
						existing.lastCommitSha(), Instant.now(), existing.sessionId(), existing.createdAt(),
						Instant.now()));
			}
		}
	}

	@Test
	void rediscoverOfAllServicesAfterASinglePackageCommitOnlyRegeneratesThatPackage(@TempDir Path tmp) throws IOException {
		GitCommandRunner git = new GitCommandRunner();
		GitWorktreeService worktrees = new GitWorktreeService(git);
		Path mono = tmp.resolve("mono");
		initRepo(mono, git);
		Files.writeString(mono.resolve("package.json"), "{\"workspaces\": [\"packages/*\"]}");
		Files.createDirectories(mono.resolve("packages/foo"));
		Files.createDirectories(mono.resolve("packages/bar"));
		Files.writeString(mono.resolve("packages/foo/a.txt"), "a");
		Files.writeString(mono.resolve("packages/bar/b.txt"), "b");
		commit(mono, "init", git);

		String fooPath = mono.resolve("packages/foo").toString();
		String barPath = mono.resolve("packages/bar").toString();
		AtomicInteger turnCalls = new AtomicInteger();
		FakeServiceProfileRepository profiles = new FakeServiceProfileRepository();
		ServiceDiscoveryService discovery = new ServiceDiscoveryService(fakeSettings(mono.toString()),
				countingTurnClient(turnCalls), worktrees, profiles, new NoopEmbeddingClient());

		// seed both profiles
		discovery.rediscover(fooPath);
		discovery.rediscover(barPath);
		assertThat(turnCalls.get()).isEqualTo(2);
		String fooShaBefore = profiles.findByServicePath(fooPath).orElseThrow().lastCommitSha();

		// a commit touching only bar
		Files.writeString(mono.resolve("packages/bar/b.txt"), "b2");
		commit(mono, "touch bar only", git);

		discovery.rediscover(fooPath);
		discovery.rediscover(barPath);

		// foo: no new system turn, SHA unchanged, but discoveredAt was bumped
		assertThat(turnCalls.get()).isEqualTo(3); // exactly one more call, for bar only
		assertThat(profiles.findByServicePath(fooPath).orElseThrow().lastCommitSha()).isEqualTo(fooShaBefore);
		assertThat(profiles.bumped).contains(fooPath);
		assertThat(profiles.bumped).doesNotContain(barPath);
	}

	@Test
	void rediscoverOnAFolderThatIsNotAKnownServiceIsRejected(@TempDir Path tmp) throws IOException {
		GitCommandRunner git = new GitCommandRunner();
		GitWorktreeService worktrees = new GitWorktreeService(git);
		Path repo = tmp.resolve("repo");
		initRepo(repo, git);
		commit(repo, "init", git);
		Path notAService = repo.resolve("random-subfolder");
		Files.createDirectories(notAService);
		ServiceDiscoveryService discovery = new ServiceDiscoveryService(fakeSettings(""),
				countingTurnClient(new AtomicInteger()), worktrees, new FakeServiceProfileRepository(),
				new NoopEmbeddingClient());

		assertThatThrownBy(() -> discovery.rediscover(notAService.toString()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("not a known service");
	}

	@Test
	void rediscoverOnAPathOutsideAnyGitRepoIsRejected(@TempDir Path tmp) {
		GitCommandRunner git = new GitCommandRunner();
		GitWorktreeService worktrees = new GitWorktreeService(git);
		ServiceDiscoveryService discovery = new ServiceDiscoveryService(fakeSettings(""),
				countingTurnClient(new AtomicInteger()), worktrees, new FakeServiceProfileRepository(),
				new NoopEmbeddingClient());

		assertThatThrownBy(() -> discovery.rediscover(tmp.resolve("not-a-repo").toString()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("not a known service");
	}
}
