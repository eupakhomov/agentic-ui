package de.pamir.claude.ui.library;

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

@Repository
public class LibraryRepository {

	public record AssetEntity(UUID id, UUID sourceId, String kind, String name, String description,
							   String location, String sourcePath, String contentHash, String status,
							   List<String> tags, Instant createdAt, Instant updatedAt) {
	}

	/** {@code score} is an RRF fusion score (higher = more relevant) — see {@link #hybridSearch}. */
	public record SearchHit(AssetEntity asset, double score) {
	}

	private static final int ARM_LIMIT = 50;
	private static final double RRF_K = 60.0;

	private static final String SELECT = """
			SELECT a.*, coalesce(array_agg(t.tag ORDER BY t.tag) FILTER (WHERE t.tag IS NOT NULL), '{}') AS tags
			FROM library_asset a LEFT JOIN asset_tag t ON t.asset_id = a.id""";

	private final JdbcClient jdbc;
	private final RowMapper<AssetEntity> rowMapper;

	public LibraryRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
		this.rowMapper = this::mapRow;
	}

	public AssetEntity insert(UUID sourceId, String kind, String name, String description, String location,
							   String sourcePath, String contentHash, List<String> tags) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
						INSERT INTO library_asset (id, source_id, kind, name, description, location, source_path, content_hash)
						VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
				.params(id, sourceId, kind, name, description, location, sourcePath, contentHash).update();
		replaceTags(id, tags);
		return get(id);
	}

	public AssetEntity get(UUID id) {
		return find(id).orElseThrow(() -> new NoSuchElementException("asset " + id + " not found"));
	}

	public Optional<AssetEntity> find(UUID id) {
		return jdbc.sql(SELECT + " WHERE a.id = ? GROUP BY a.id").params(id).query(rowMapper).optional();
	}

	public List<AssetEntity> findAll(String kind, String status, String query) {
		return findAll(kind, status, query, null, null);
	}

	/** limit/offset are optional — omitting both preserves the "return everything" behavior. */
	public List<AssetEntity> findAll(String kind, String status, String query, Integer limit, Integer offset) {
		StringBuilder sql = new StringBuilder(SELECT + " WHERE 1=1");
		List<Object> params = new ArrayList<>();
		if (kind != null && !kind.isBlank()) {
			sql.append(" AND a.kind = ?");
			params.add(kind);
		}
		if (status != null && !status.isBlank()) {
			sql.append(" AND a.status = ?");
			params.add(status);
		}
		if (query != null && !query.isBlank()) {
			sql.append(" AND (a.name ILIKE ? OR a.description ILIKE ?"
					+ " OR EXISTS (SELECT 1 FROM asset_tag q WHERE q.asset_id = a.id AND q.tag ILIKE ?))");
			String like = "%" + query.strip() + "%";
			params.add(like);
			params.add(like);
			params.add(like);
		}
		sql.append(" GROUP BY a.id ORDER BY a.name");
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

	public List<AssetEntity> findBySource(UUID sourceId) {
		return jdbc.sql(SELECT + " WHERE a.source_id = ? GROUP BY a.id ORDER BY a.name")
				.params(sourceId).query(rowMapper).list();
	}

	public void updateMeta(UUID id, String name, String description) {
		int updated = jdbc.sql("UPDATE library_asset SET name = ?, description = ?, updated_at = now() WHERE id = ?")
				.params(name, description, id).update();
		if (updated == 0) {
			throw new NoSuchElementException("asset " + id + " not found");
		}
	}

	public void updateStatus(UUID id, String status) {
		int updated = jdbc.sql("UPDATE library_asset SET status = ?, updated_at = now() WHERE id = ?")
				.params(status, id).update();
		if (updated == 0) {
			throw new NoSuchElementException("asset " + id + " not found");
		}
	}

	public void updateHash(UUID id, String contentHash) {
		jdbc.sql("UPDATE library_asset SET content_hash = ?, updated_at = now() WHERE id = ?")
				.params(contentHash, id).update();
	}

	public void replaceTags(UUID id, List<String> tags) {
		jdbc.sql("DELETE FROM asset_tag WHERE asset_id = ?").params(id).update();
		if (tags != null) {
			tags.stream().map(String::strip).filter(t -> !t.isEmpty()).distinct().forEach(tag ->
					jdbc.sql("INSERT INTO asset_tag (asset_id, tag) VALUES (?, ?)").params(id, tag).update());
		}
	}

	public boolean delete(UUID id) {
		return jdbc.sql("DELETE FROM library_asset WHERE id = ?").params(id).update() > 0;
	}

	// --- embeddings ---

	public void upsertEmbedding(UUID assetId, float[] embedding, String model) {
		jdbc.sql("""
						INSERT INTO asset_embedding (asset_id, embedding, model) VALUES (?, ?::vector, ?)
						ON CONFLICT (asset_id) DO UPDATE SET embedding = EXCLUDED.embedding,
							model = EXCLUDED.model, embedded_at = now()""")
				.params(assetId, PgVector.literal(embedding), model).update();
	}

	/**
	 * Dense (pgvector cosine, over {@code asset_embedding}) + sparse (Postgres FTS over name+
	 * description) + trigram (name) search, fused with Reciprocal Rank Fusion — same shape as
	 * {@code MemoryRepository.hybridSearch} (docs/plan/phase-9-production-hardening.md O3).
	 * {@code queryEmbedding} null skips the dense arm (Voyage unconfigured, or an asset was
	 * simply never embedded) — sparse/trigram still work, so search is never all-or-nothing.
	 */
	public List<SearchHit> hybridSearch(String queryText, float[] queryEmbedding, String kind, int limit) {
		// bare-table filter (sparse/trgm arms query "library_asset" directly); "a." for the dense
		// arm, which joins it as alias a
		String barefilter = kind != null && !kind.isBlank() ? " AND status = 'ACTIVE' AND kind = ?"
				: " AND status = 'ACTIVE'";
		String aliasedFilter = kind != null && !kind.isBlank() ? " AND a.status = 'ACTIVE' AND a.kind = ?"
				: " AND a.status = 'ACTIVE'";
		List<Object> filterParams = kind != null && !kind.isBlank() ? List.of(kind) : List.of();

		StringBuilder sql = new StringBuilder("WITH ");
		List<Object> params = new ArrayList<>();
		List<String> arms = new ArrayList<>();
		if (queryEmbedding != null) {
			sql.append("""
							dense AS (
								SELECT id, rnk FROM (
									SELECT a.id, row_number() OVER (ORDER BY e.embedding <=> ?::vector) AS rnk
									FROM asset_embedding e JOIN library_asset a ON a.id = e.asset_id
									WHERE true""").append(aliasedFilter).append("""
							) x ORDER BY rnk LIMIT %d
						),
						""".formatted(ARM_LIMIT));
			params.add(PgVector.literal(queryEmbedding));
			params.addAll(filterParams);
			arms.add("dense");
		}
		sql.append("""
						sparse AS (
							SELECT id, rnk FROM (
								SELECT id, row_number() OVER (
									ORDER BY ts_rank_cd(tsv, websearch_to_tsquery('english', ?)) DESC) AS rnk
								FROM library_asset WHERE tsv @@ websearch_to_tsquery('english', ?)""")
				.append(barefilter).append("""
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
								SELECT id, row_number() OVER (ORDER BY similarity(name, ?) DESC) AS rnk
								FROM library_asset WHERE name % ?""")
				.append(barefilter).append("""
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
				.append("""
						SELECT a.*, coalesce(array_agg(t.tag ORDER BY t.tag) FILTER (WHERE t.tag IS NOT NULL), '{}') AS tags,
							f.score AS score
						FROM fused f JOIN library_asset a ON a.id = f.id
						LEFT JOIN asset_tag t ON t.asset_id = a.id
						GROUP BY a.id, f.score
						ORDER BY f.score DESC LIMIT ?""");
		params.add(limit);
		return jdbc.sql(sql.toString()).params(params)
				.query((rs, n) -> new SearchHit(mapRow(rs, n), rs.getDouble("score"))).list();
	}

	private AssetEntity mapRow(ResultSet rs, int rowNum) throws SQLException {
		String[] tags = (String[]) rs.getArray("tags").getArray();
		return new AssetEntity(
				rs.getObject("id", UUID.class),
				rs.getObject("source_id", UUID.class),
				rs.getString("kind"),
				rs.getString("name"),
				rs.getString("description"),
				rs.getString("location"),
				rs.getString("source_path"),
				rs.getString("content_hash"),
				rs.getString("status"),
				List.of(tags),
				rs.getTimestamp("created_at").toInstant(),
				rs.getTimestamp("updated_at").toInstant());
	}
}
