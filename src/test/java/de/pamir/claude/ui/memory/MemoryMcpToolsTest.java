package de.pamir.claude.ui.memory;

import de.pamir.claude.ui.library.EmbeddingClient;
import de.pamir.claude.ui.session.SessionEntity;
import de.pamir.claude.ui.session.SessionRepository;
import de.pamir.claude.ui.session.SessionState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * See docs/plan/phase-10-review-followups.md R2: a present-but-failing embedding provider must
 * degrade {@code memory_search} to the sparse/trigram arm instead of erroring the whole tool
 * call. No DB — {@link MemoryRepository}/{@link SessionRepository} are subclassed here to stub
 * just the methods {@code memorySearch} touches.
 */
class MemoryMcpToolsTest {

	private static final class FailingEmbeddingClient implements EmbeddingClient {
		@Override
		public boolean configured() {
			return true;
		}

		@Override
		public float[] embed(String text, boolean query) {
			throw new IllegalStateException("Voyage API key rejected (401)");
		}

		@Override
		public String model() {
			return "voyage-3.5-lite";
		}
	}

	private static final class StubSessionRepository extends SessionRepository {
		private final SessionEntity entity;

		StubSessionRepository(SessionEntity entity) {
			super(null, null);
			this.entity = entity;
		}

		@Override
		public SessionEntity get(UUID id) {
			return entity;
		}
	}

	private static final class StubMemoryRepository extends MemoryRepository {
		private final List<SearchHit> hits;
		float[] embeddingSeenByHybridSearch;
		String servicePathSeenByHybridSearch;
		String servicePathSeenByTagCounts;
		boolean called;

		StubMemoryRepository(List<SearchHit> hits) {
			super(null);
			this.hits = hits;
		}

		@Override
		public List<SearchHit> hybridSearch(String queryText, float[] queryEmbedding, String servicePath,
											 List<String> tags, int limit) {
			called = true;
			embeddingSeenByHybridSearch = queryEmbedding;
			servicePathSeenByHybridSearch = servicePath;
			return hits;
		}

		@Override
		public java.util.Map<String, Long> tagCounts(String servicePath) {
			servicePathSeenByTagCounts = servicePath;
			return java.util.Map.of();
		}
	}

	@Test
	void memorySearchDegradesToSparseArmWhenEmbeddingFails() {
		UUID sessionId = UUID.randomUUID();
		SessionEntity session = SessionEntity.builder()
				.id(sessionId).name("s").provider("claude").repoPath("/repo")
				.branch("b").baseBranch("main").worktreePath("/wt")
				.contextDirs(List.of()).permissionMode("default")
				.allowedTools(List.of()).disallowedTools(List.of())
				.state(SessionState.IDLE).kind("user").build();
		MemoryRepository.MemoryDoc doc = new MemoryRepository.MemoryDoc(UUID.randomUUID(), "service", "/repo",
				"docs/x.md", "some-memory", "a description", List.of("tag"), "content", "hash", "ACTIVE",
				Instant.now(), Instant.now());
		StubMemoryRepository docs = new StubMemoryRepository(List.of(new MemoryRepository.SearchHit(doc, 1.0)));
		MemoryMcpTools tools = new MemoryMcpTools(new StubSessionRepository(session), docs, null, new FailingEmbeddingClient());

		List<MemoryMcpTools.SearchResult> results = tools.memorySearch(sessionId.toString(), "some query", null);

		assertThat(docs.called).isTrue();
		assertThat(docs.embeddingSeenByHybridSearch).isNull(); // dense arm skipped, not propagated as a failure
		assertThat(results).extracting(MemoryMcpTools.SearchResult::name).containsExactly("some-memory");
	}

	/**
	 * docs/plan/phase-11-monorepo.md Step 5: memory scoping keys on the resolved {@code
	 * servicePath()}, not {@code repoPath()} — a session on a monorepo package must search/tag
	 * that package's own memory, not the whole repo's.
	 */
	@Test
	void memorySearchAndMemoryTagsScopeOnServicePathNotRepoPathForAMonorepoSession() {
		UUID sessionId = UUID.randomUUID();
		SessionEntity session = SessionEntity.builder()
				.id(sessionId).name("s").provider("claude").repoPath("/repo/mono")
				.servicePath("/repo/mono/packages/foo")
				.branch("b").baseBranch("main").worktreePath("/wt")
				.contextDirs(List.of()).permissionMode("default")
				.allowedTools(List.of()).disallowedTools(List.of())
				.state(SessionState.IDLE).kind("user").build();
		StubMemoryRepository docs = new StubMemoryRepository(List.of());
		MemoryMcpTools tools = new MemoryMcpTools(new StubSessionRepository(session), docs, null, new FailingEmbeddingClient());

		tools.memorySearch(sessionId.toString(), "some query", null);
		tools.memoryTags(sessionId.toString());

		assertThat(docs.servicePathSeenByHybridSearch).isEqualTo("/repo/mono/packages/foo");
		assertThat(docs.servicePathSeenByTagCounts).isEqualTo("/repo/mono/packages/foo");
	}
}
