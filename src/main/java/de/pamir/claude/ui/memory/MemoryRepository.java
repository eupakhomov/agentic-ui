package de.pamir.claude.ui.memory;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * CRUD + hybrid (dense + sparse) search over semantic-memory docs, and their wikilink
 * graph. The Markdown file is the source of truth (see docs/plan/phase-5.3-memory-
 * reflection.md); this table is an index rebuilt from the file on every write/sync.
 */
@Repository
public class MemoryRepository {

	public record MemoryDoc(UUID id, String scope, String servicePath, String relPath, String name,
							 String description, List<String> tags, String content, String contentHash,
							 String status, Instant createdAt, Instant updatedAt) {
	}

	public record IndexEntry(UUID id, String scope, String servicePath, String name, String description,
							  List<String> tags) {
	}

	public record LinkRef(String slug, UUID docId, String name, String description, boolean dangling) {
	}

	public record SearchHit(MemoryDoc doc, double score) {
	}

	private static final String SELECT = "SELECT id, scope, service_path, rel_path, name, description, tags, "
			+ "content, content_hash, status, created_at, updated_at FROM memory_doc";
	private static final int ARM_LIMIT = 50;
	private static final double RRF_K = 60.0;

	private final JdbcClient jdbc;
	private final RowMapper<MemoryDoc> rowMapper;

	public MemoryRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
		this.rowMapper = this::mapRow;
	}

	// --- CRUD ---

	public MemoryDoc insert(String scope, String servicePath, String relPath, String name, String description,
							 List<String> tags, String content, String contentHash) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
						INSERT INTO memory_doc (id, scope, service_path, rel_path, name, description, tags,
							content, content_hash)
						VALUES (?, ?, ?, ?, ?, ?, ?::text[], ?, ?)""")
				.params(id, scope, servicePath, relPath, name, description, toArrayLiteral(tags), content, contentHash)
				.update();
		return get(id);
	}

	public MemoryDoc get(UUID id) {
		return find(id).orElseThrow(() -> new NoSuchElementException("memory doc " + id + " not found"));
	}

	public Optional<MemoryDoc> find(UUID id) {
		return jdbc.sql(SELECT + " WHERE id = ?").params(id).query(rowMapper).optional();
	}

	public Optional<MemoryDoc> findByRelPath(String relPath) {
		return jdbc.sql(SELECT + " WHERE rel_path = ?").params(relPath).query(rowMapper).optional();
	}

	public Optional<MemoryDoc> findByScopeAndName(String scope, String servicePath, String name) {
		return jdbc.sql(SELECT + " WHERE scope = ? AND service_path IS NOT DISTINCT FROM ? AND name = ?")
				.params(scope, servicePath, name).query(rowMapper).optional();
	}

	/**
	 * Resolves a wikilink target: same scope as the linking doc first, then ecosystem, then any
	 * other service (matches decision 9's resolution order — the "other services" tier is honest
	 * link resolution only; session-scoped tool reads filter it back out, see MemoryDocService).
	 */
	public Optional<MemoryDoc> resolveSlug(String slug, String fromScope, String fromServicePath) {
		return jdbc.sql(SELECT + " " + """
						 WHERE status = 'ACTIVE' AND name = ?
						 ORDER BY CASE
							WHEN scope = ? AND service_path IS NOT DISTINCT FROM ? THEN 0
							WHEN scope = 'ecosystem' THEN 1
							ELSE 2 END
						 LIMIT 1""")
				.params(slug, fromScope, fromServicePath).query(rowMapper).optional();
	}

	/**
	 * Looks up a doc by name restricted to what a session may actually read: its own service
	 * scope plus ecosystem — never another service, unlike {@link #resolveSlug}, which is allowed
	 * to cross into other services for honest wikilink resolution (decision 12b).
	 */
	public Optional<MemoryDoc> findVisibleByName(String servicePath, String name) {
		return jdbc.sql(SELECT + " " + """
						 WHERE status = 'ACTIVE' AND name = ?
						   AND (scope = 'ecosystem' OR (scope = 'service' AND service_path = ?))
						 ORDER BY CASE WHEN scope = 'service' THEN 0 ELSE 1 END LIMIT 1""")
				.params(name, servicePath).query(rowMapper).optional();
	}

	/** ACTIVE docs visible to a session: its own service scope plus ecosystem — name/description/tags only. */
	public List<IndexEntry> findIndex(String servicePath) {
		return jdbc.sql("""
						SELECT id, scope, service_path, name, description, tags FROM memory_doc
						WHERE status = 'ACTIVE' AND (scope = 'ecosystem' OR (scope = 'service' AND service_path = ?))
						ORDER BY scope, name""")
				.params(servicePath)
				.query((rs, n) -> new IndexEntry(rs.getObject("id", UUID.class), rs.getString("scope"),
						rs.getString("service_path"), rs.getString("name"), rs.getString("description"),
						List.of(tagList(rs)))).list();
	}

	/** Tag → count across a session's visible scopes ("what drawers exist" — memory_tags tool). */
	public java.util.Map<String, Long> tagCounts(String servicePath) {
		java.util.Map<String, Long> counts = new java.util.LinkedHashMap<>();
		for (IndexEntry e : findIndex(servicePath)) {
			for (String tag : e.tags()) {
				counts.merge(tag, 1L, Long::sum);
			}
		}
		return counts;
	}

	/** Plain filtered listing for the UI browser — no ranking. */
	public List<MemoryDoc> findAll(String scope, String servicePath, String status, String query, Integer limit,
									Integer offset) {
		StringBuilder sql = new StringBuilder(SELECT + " WHERE 1=1");
		List<Object> params = new ArrayList<>();
		if (scope != null && !scope.isBlank()) {
			sql.append(" AND scope = ?");
			params.add(scope);
		}
		if (servicePath != null && !servicePath.isBlank()) {
			sql.append(" AND service_path = ?");
			params.add(servicePath);
		}
		sql.append(" AND status = ?");
		params.add(status == null || status.isBlank() ? "ACTIVE" : status);
		if (query != null && !query.isBlank()) {
			sql.append(" AND (name ILIKE ? OR description ILIKE ?)");
			String like = "%" + query.strip() + "%";
			params.add(like);
			params.add(like);
		}
		sql.append(" ORDER BY scope, name");
		if (limit != null) {
			sql.append(" LIMIT ?");
			params.add(limit);
		}
		if (offset != null) {
			sql.append(" OFFSET ?");
			params.add(offset);
		}
		return jdbc.sql(sql.toString()).params(params).query(rowMapper).list();
	}

	public void updateContent(UUID id, String description, List<String> tags, String content, String contentHash) {
		int updated = jdbc.sql("""
						UPDATE memory_doc SET description = ?, tags = ?::text[], content = ?, content_hash = ?,
							updated_at = now() WHERE id = ?""")
				.params(description, toArrayLiteral(tags), content, contentHash, id).update();
		if (updated == 0) {
			throw new NoSuchElementException("memory doc " + id + " not found");
		}
	}

	public void updateStatus(UUID id, String status) {
		int updated = jdbc.sql("UPDATE memory_doc SET status = ?, updated_at = now() WHERE id = ?")
				.params(status, id).update();
		if (updated == 0) {
			throw new NoSuchElementException("memory doc " + id + " not found");
		}
	}

	public void upsertEmbedding(UUID id, float[] embedding, String model) {
		jdbc.sql("UPDATE memory_doc SET embedding = ?::vector, embedding_model = ? WHERE id = ?")
				.params(toVectorLiteral(embedding), model, id).update();
	}

	// --- links ---

	/** Replaces the full outgoing-link set for a doc, resolving each slug against the current index. */
	public void replaceLinks(UUID fromDocId, List<String> toSlugs, String fromScope, String fromServicePath) {
		jdbc.sql("DELETE FROM memory_link WHERE from_doc_id = ?").params(fromDocId).update();
		for (String slug : toSlugs.stream().distinct().toList()) {
			UUID targetId = resolveSlug(slug, fromScope, fromServicePath).map(MemoryDoc::id).orElse(null);
			jdbc.sql("INSERT INTO memory_link (from_doc_id, to_slug, to_doc_id) VALUES (?, ?, ?) "
							+ "ON CONFLICT (from_doc_id, to_slug) DO UPDATE SET to_doc_id = EXCLUDED.to_doc_id")
					.params(fromDocId, slug, targetId).update();
		}
	}

	/** Re-resolves dangling links across the whole graph that target this slug (called after a doc is (re)created). */
	public void reresolveDangling(MemoryDoc created) {
		record Candidate(UUID fromDocId, String fromScope, String fromServicePath) {
		}
		List<Candidate> candidates = jdbc.sql("""
						SELECT l.from_doc_id, d.scope AS from_scope, d.service_path AS from_service_path
						FROM memory_link l JOIN memory_doc d ON d.id = l.from_doc_id
						WHERE l.to_slug = ? AND l.to_doc_id IS NULL""")
				.params(created.name())
				.query((rs, n) -> new Candidate(rs.getObject("from_doc_id", UUID.class), rs.getString("from_scope"),
						rs.getString("from_service_path")))
				.list();
		for (Candidate c : candidates) {
			Optional<MemoryDoc> resolved = resolveSlug(created.name(), c.fromScope(), c.fromServicePath());
			if (resolved.isPresent() && resolved.get().id().equals(created.id())) {
				jdbc.sql("UPDATE memory_link SET to_doc_id = ? WHERE from_doc_id = ? AND to_slug = ?")
						.params(created.id(), c.fromDocId(), created.name()).update();
			}
		}
	}

	/** Links go dangling (not deleted) when their target is archived, so restoring the target re-resolves them. */
	public void danglePointingAt(UUID docId) {
		jdbc.sql("UPDATE memory_link SET to_doc_id = NULL WHERE to_doc_id = ?").params(docId).update();
	}

	public List<LinkRef> outgoing(UUID docId) {
		return jdbc.sql("""
						SELECT l.to_slug, l.to_doc_id, d.name, d.description
						FROM memory_link l LEFT JOIN memory_doc d ON d.id = l.to_doc_id
						WHERE l.from_doc_id = ? ORDER BY l.to_slug""")
				.params(docId).query(this::mapLinkRef).list();
	}

	public List<LinkRef> backlinks(UUID docId) {
		return jdbc.sql("""
						SELECT d.name AS to_slug, d.id AS to_doc_id, d.name, d.description
						FROM memory_link l JOIN memory_doc d ON d.id = l.from_doc_id
						WHERE l.to_doc_id = ? ORDER BY d.name""")
				.params(docId).query(this::mapLinkRef).list();
	}

	private LinkRef mapLinkRef(ResultSet rs, int rowNum) throws SQLException {
		UUID toDocId = (UUID) rs.getObject("to_doc_id");
		return new LinkRef(rs.getString("to_slug"), toDocId, rs.getString("name"), rs.getString("description"),
				toDocId == null);
	}

	// --- hybrid search ---

	/**
	 * Dense (pgvector cosine) + sparse (Postgres FTS) + trigram (exact-identifier) search, fused
	 * with Reciprocal Rank Fusion. {@code queryEmbedding} null skips the dense arm (Voyage
	 * unconfigured — sparse/trigram still work). Visibility: null scope+servicePath searches
	 * everything (UI); otherwise service scope + ecosystem for that service (agent tools).
	 */
	public List<SearchHit> hybridSearch(String queryText, float[] queryEmbedding, String servicePath,
										 List<String> tags, int limit) {
		StringBuilder filter = new StringBuilder(" AND status = 'ACTIVE'");
		List<Object> filterParams = new ArrayList<>();
		if (servicePath != null && !servicePath.isBlank()) {
			filter.append(" AND (scope = 'ecosystem' OR (scope = 'service' AND service_path = ?))");
			filterParams.add(servicePath);
		}
		if (tags != null && !tags.isEmpty()) {
			filter.append(" AND tags && ?::text[]");
			filterParams.add(toArrayLiteral(tags));
		}
		String filterSql = filter.toString();

		StringBuilder sql = new StringBuilder("WITH ");
		List<Object> params = new ArrayList<>();
		List<String> arms = new ArrayList<>();
		if (queryEmbedding != null) {
			sql.append("""
							dense AS (
								SELECT id, rnk FROM (
									SELECT id, row_number() OVER (ORDER BY embedding <=> ?::vector) AS rnk
									FROM memory_doc WHERE embedding IS NOT NULL""").append(filterSql).append("""
								) x ORDER BY rnk LIMIT %d
							),
							""".formatted(ARM_LIMIT));
			params.add(toVectorLiteral(queryEmbedding));
			params.addAll(filterParams);
			arms.add("dense");
		}
		sql.append("""
						sparse AS (
							SELECT id, rnk FROM (
								SELECT id, row_number() OVER (
									ORDER BY ts_rank_cd(tsv, websearch_to_tsquery('english', ?)) DESC) AS rnk
								FROM memory_doc WHERE tsv @@ websearch_to_tsquery('english', ?)""")
				.append(filterSql).append("""
						) x ORDER BY rnk LIMIT %d
					),
					""".formatted(ARM_LIMIT));
		params.add(queryText);
		params.add(queryText);
		params.addAll(filterParams);
		arms.add("sparse");

		sql.append("""
						trgm AS (
							SELECT id, rnk FROM (
								SELECT id, row_number() OVER (ORDER BY similarity(content, ?) DESC) AS rnk
								FROM memory_doc WHERE content % ?""")
				.append(filterSql).append("""
						) x ORDER BY rnk LIMIT %d
					),
					""".formatted(ARM_LIMIT));
		params.add(queryText);
		params.add(queryText);
		params.addAll(filterParams);
		arms.add("trgm");

		sql.append("fused AS (SELECT id, SUM(1.0 / (").append(RRF_K).append(" + rnk)) AS score FROM (")
				.append(String.join(" UNION ALL ", arms.stream().map(a -> "SELECT * FROM " + a).toList()))
				.append(") u GROUP BY id) ")
				.append("SELECT d.id, d.scope, d.service_path, d.rel_path, d.name, d.description, d.tags, ")
				.append("d.content, d.content_hash, d.status, d.created_at, d.updated_at, f.score AS score ")
				.append("FROM fused f JOIN memory_doc d ON d.id = f.id ORDER BY f.score DESC LIMIT ?");
		params.add(limit);
		return jdbc.sql(sql.toString()).params(params)
				.query((rs, n) -> new SearchHit(mapRow(rs, n), rs.getDouble("score"))).list();
	}

	// --- mapping ---

	private static String[] tagList(ResultSet rs) throws SQLException {
		var arr = rs.getArray("tags");
		return arr == null ? new String[0] : (String[]) arr.getArray();
	}

	private MemoryDoc mapRow(ResultSet rs, int rowNum) throws SQLException {
		return new MemoryDoc(
				rs.getObject("id", UUID.class),
				rs.getString("scope"),
				rs.getString("service_path"),
				rs.getString("rel_path"),
				rs.getString("name"),
				rs.getString("description"),
				List.of(tagList(rs)),
				rs.getString("content"),
				rs.getString("content_hash"),
				rs.getString("status"),
				rs.getTimestamp("created_at").toInstant(),
				rs.getTimestamp("updated_at").toInstant());
	}

	private static String toVectorLiteral(float[] embedding) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < embedding.length; i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append(embedding[i]);
		}
		return sb.append(']').toString();
	}

	/** Postgres array literal, e.g. {"tag1","tag2"} — bound as plain text and cast with ::text[]. */
	private static String toArrayLiteral(List<String> values) {
		if (values == null || values.isEmpty()) {
			return "{}";
		}
		StringBuilder sb = new StringBuilder("{");
		for (int i = 0; i < values.size(); i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append('"').append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
		}
		return sb.append('}').toString();
	}
}
