package de.pamir.claude.ui.library;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LibraryRepository#hybridSearch} against a live Postgres (docs/plan/
 * phase-9-production-hardening.md O3/T3) — the dense+sparse+trgm RRF fusion promoted from the
 * old dense-only {@code searchByEmbedding}, same shape as {@code MemoryRepositoryDbTest}. Every
 * asset here is tagged with a random rare token so matches can't collide with real dev-database
 * content; rolled back after each test.
 */
@Tag("integration")
@SpringBootTest
@Transactional
class LibraryRepositoryDbTest {

	@Autowired
	private LibraryRepository assets;

	private String rareToken() {
		return "xyzzy" + UUID.randomUUID().toString().replace("-", "");
	}

	private float[] unitVector(int hotIndex) {
		float[] v = new float[1024];
		v[hotIndex] = 1.0f;
		return v;
	}

	@Test
	void sparseArmFindsAnAssetWithNoEmbeddingByDescription() {
		String token = rareToken();
		var asset = assets.insert(null, "skill", "skill-" + token, "a description mentioning " + token,
				"/loc/" + token, null, "hash1", List.of());

		List<LibraryRepository.SearchHit> hits = assets.hybridSearch(token, null, null, 10);

		assertThat(hits).extracting(h -> h.asset().id()).contains(asset.id());
	}

	@Test
	void trgmArmFindsAnAssetByNameSimilarity() {
		String token = rareToken();
		var asset = assets.insert(null, "agent", token + "-tool", "unrelated description", "/loc/" + token,
				null, "hash2", List.of());

		// deliberately misspelled — trigram similarity, not an exact FTS match
		List<LibraryRepository.SearchHit> hits = assets.hybridSearch(token + "-toool", null, null, 10);

		assertThat(hits).extracting(h -> h.asset().id()).contains(asset.id());
	}

	@Test
	void denseArmFindsAnEmbeddedAssetByCosineDistance() {
		String token = rareToken();
		var asset = assets.insert(null, "skill", "skill-" + token, "description " + token, "/loc/" + token,
				null, "hash3", List.of());
		float[] embedding = unitVector(5);
		assets.upsertEmbedding(asset.id(), embedding, "test-model");

		// query text deliberately doesn't match (sparse/trgm arms empty); only the dense arm,
		// searching by the identical embedding, can surface this asset
		List<LibraryRepository.SearchHit> hits = assets.hybridSearch("completely unrelated query", embedding, null, 10);

		assertThat(hits).extracting(h -> h.asset().id()).contains(asset.id());
	}

	@Test
	void kindFilterExcludesTheOtherKind() {
		String token = rareToken();
		var skill = assets.insert(null, "skill", "skill-" + token, "description " + token, "/loc/s-" + token,
				null, "hash4", List.of());
		var agent = assets.insert(null, "agent", "agent-" + token, "description " + token, "/loc/a-" + token,
				null, "hash5", List.of());

		List<UUID> skillHits = assets.hybridSearch(token, null, "skill", 10).stream()
				.map(h -> h.asset().id()).toList();

		assertThat(skillHits).contains(skill.id());
		assertThat(skillHits).doesNotContain(agent.id());
	}

	@Test
	void archivedAssetsAreExcludedFromSearch() {
		String token = rareToken();
		var asset = assets.insert(null, "skill", "skill-" + token, "description " + token, "/loc/" + token,
				null, "hash6", List.of());
		assets.updateStatus(asset.id(), "ARCHIVED");

		List<UUID> hits = assets.hybridSearch(token, null, null, 10).stream().map(h -> h.asset().id()).toList();

		assertThat(hits).doesNotContain(asset.id());
	}
}
