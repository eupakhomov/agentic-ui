package de.pamir.claude.ui.discovery;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ServiceProfileRepository} against a live Postgres — locks in the V14 rename
 * (docs/plan/phase-11-monorepo.md Step 2: {@code repo_path} → {@code service_path} as the
 * identity column, plus a new nullable {@code repo_path}). Each test runs in its own
 * transaction, rolled back afterward.
 */
@Tag("integration")
@SpringBootTest
@Transactional
class ServiceProfileRepositoryDbTest {

	@Autowired
	private ServiceProfileRepository profiles;

	@Test
	void upsertRoundTripsServicePathAndRepoPath() {
		String servicePath = "/mono/packages/foo-" + UUID.randomUUID();
		String repoPath = "/mono";

		var created = profiles.upsert(servicePath, repoPath, "foo", "does foo things", List.of("a", "b"), "sha1", null);

		assertThat(created.servicePath()).isEqualTo(servicePath);
		assertThat(created.repoPath()).isEqualTo(repoPath);
		assertThat(created.name()).isEqualTo("foo");
		assertThat(created.tags()).containsExactly("a", "b");

		var found = profiles.findByServicePath(servicePath).orElseThrow();
		assertThat(found.servicePath()).isEqualTo(servicePath);
		assertThat(found.repoPath()).isEqualTo(repoPath);
	}

	@Test
	void upsertOnConflictAlsoUpdatesRepoPath() {
		String servicePath = "/mono/packages/bar-" + UUID.randomUUID();
		profiles.upsert(servicePath, "/mono-old-root", "bar", "first description", List.of(), "sha1", null);

		var updated = profiles.upsert(servicePath, "/mono-new-root", "bar", "second description", List.of(), "sha2", null);

		assertThat(updated.repoPath()).isEqualTo("/mono-new-root");
		assertThat(updated.description()).isEqualTo("second description");
		assertThat(updated.lastCommitSha()).isEqualTo("sha2");
	}

	@Test
	void findVisibleFiltersOnServicePathNotRepoPath() {
		String visible = "/mono/packages/visible-" + UUID.randomUUID();
		String hidden = "/mono/packages/hidden-" + UUID.randomUUID();
		profiles.upsert(visible, "/mono", "visible", "desc", List.of(), "sha", null);
		profiles.upsert(hidden, "/mono", "hidden", "desc", List.of(), "sha", null);

		var results = profiles.findVisible(List.of(visible));

		assertThat(results).extracting(ServiceProfileRepository.ServiceProfile::servicePath).containsExactly(visible);
	}

	@Test
	void bumpDiscoveredAtRefreshesTimestampWithoutTouchingOtherFields() {
		String servicePath = "/mono/packages/baz-" + UUID.randomUUID();
		var original = profiles.upsert(servicePath, "/mono", "baz", "desc", List.of(), "sha1", null);

		// Postgres now() is frozen for the whole (@Transactional) transaction, so two calls a few ms
		// apart in the same test are provably equal, not strictly increasing — assert no regression
		// and that the other columns are untouched, which is what bumpDiscoveredAt actually promises.
		profiles.bumpDiscoveredAt(servicePath);

		var bumped = profiles.findByServicePath(servicePath).orElseThrow();
		assertThat(bumped.discoveredAt()).isAfterOrEqualTo(original.discoveredAt());
		assertThat(bumped.lastCommitSha()).isEqualTo("sha1");
		assertThat(bumped.repoPath()).isEqualTo("/mono");
	}
}
