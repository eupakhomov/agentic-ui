package de.pamir.claude.ui.memory;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MemoryRepository#hybridSearch} against a live Postgres (docs/plan/
 * phase-9-production-hardening.md T3) — the 3-arm RRF fusion SQL (dense/sparse/trgm) and its
 * scope-visibility filter are both easy to get subtly wrong and worth proving against the real
 * engine. Every doc here is tagged with a random rare token so matches can't be confused with
 * whatever real memory content already lives in the dev database; rolled back after each test.
 */
@Tag("integration")
@SpringBootTest
@Transactional
class MemoryRepositoryDbTest {

	@Autowired
	private MemoryRepository memory;

	private String rareToken() {
		return "xyzzy" + UUID.randomUUID().toString().replace("-", "");
	}

	private float[] unitVector(int hotIndex) {
		float[] v = new float[1024];
		v[hotIndex] = 1.0f;
		return v;
	}

	@Test
	void sparseAndTrgmArmsFindADocWithNoEmbedding() {
		String token = rareToken();
		var doc = memory.insert("ecosystem", null, "rel/" + UUID.randomUUID() + ".md", "doc-" + token,
				"a test doc about " + token, List.of(), "body mentioning " + token + " for search", "hash1");

		List<MemoryRepository.SearchHit> hits = memory.hybridSearch(token, null, null, null, 10);

		assertThat(hits).extracting(h -> h.doc().id()).contains(doc.id());
	}

	@Test
	void denseArmFindsAnEmbeddedDocByCosineDistance() {
		String token = rareToken();
		var doc = memory.insert("ecosystem", null, "rel/" + UUID.randomUUID() + ".md", "doc-" + token,
				"a test doc about " + token, List.of(), "body mentioning " + token, "hash2");
		float[] embedding = unitVector(3);
		memory.upsertEmbedding(doc.id(), embedding, "test-model");

		// Query text deliberately doesn't match (sparse/trgm arms empty); only the dense arm,
		// searching by the identical embedding (cosine distance 0), can surface this doc.
		List<MemoryRepository.SearchHit> hits =
				memory.hybridSearch("completely unrelated query text", embedding, null, null, 10);

		assertThat(hits).extracting(h -> h.doc().id()).contains(doc.id());
	}

	@Test
	void servicePathFilterIncludesEcosystemAndOwnServiceButExcludesOtherServices() {
		String token = rareToken();
		String serviceA = "/services/a-" + UUID.randomUUID();
		String serviceB = "/services/b-" + UUID.randomUUID();

		var ecosystemDoc = memory.insert("ecosystem", null, "rel/" + UUID.randomUUID() + ".md",
				"eco-" + token, "ecosystem doc " + token, List.of(), "content " + token, "h-eco");
		var serviceADoc = memory.insert("service", serviceA, "rel/" + UUID.randomUUID() + ".md",
				"a-" + token, "service A doc " + token, List.of(), "content " + token, "h-a");
		var serviceBDoc = memory.insert("service", serviceB, "rel/" + UUID.randomUUID() + ".md",
				"b-" + token, "service B doc " + token, List.of(), "content " + token, "h-b");

		List<UUID> visibleFromA = memory.hybridSearch(token, null, serviceA, null, 10).stream()
				.map(h -> h.doc().id()).toList();

		assertThat(visibleFromA).contains(ecosystemDoc.id(), serviceADoc.id());
		assertThat(visibleFromA).doesNotContain(serviceBDoc.id());
	}

	@Test
	void tagFilterExcludesDocsWithoutTheRequestedTag() {
		String token = rareToken();
		var tagged = memory.insert("ecosystem", null, "rel/" + UUID.randomUUID() + ".md", "tagged-" + token,
				"doc " + token, List.of("keep-" + token), "content " + token, "h-tag1");
		var untagged = memory.insert("ecosystem", null, "rel/" + UUID.randomUUID() + ".md", "untagged-" + token,
				"doc " + token, List.of(), "content " + token, "h-tag2");

		List<UUID> filtered = memory.hybridSearch(token, null, null, List.of("keep-" + token), 10).stream()
				.map(h -> h.doc().id()).toList();

		assertThat(filtered).contains(tagged.id());
		assertThat(filtered).doesNotContain(untagged.id());
	}

	@Test
	void archivedDocsAreExcludedFromSearch() {
		String token = rareToken();
		var doc = memory.insert("ecosystem", null, "rel/" + UUID.randomUUID() + ".md", "archived-" + token,
				"doc " + token, List.of(), "content " + token, "h-arch");
		memory.updateStatus(doc.id(), "ARCHIVED");

		List<UUID> hits = memory.hybridSearch(token, null, null, null, 10).stream()
				.map(h -> h.doc().id()).toList();

		assertThat(hits).doesNotContain(doc.id());
	}
}
